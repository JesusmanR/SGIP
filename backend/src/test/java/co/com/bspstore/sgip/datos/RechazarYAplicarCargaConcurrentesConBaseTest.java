package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.FilaDeCarga;
import co.com.bspstore.sgip.logica.EstadoDeCargaInvalidoException;
import co.com.bspstore.sgip.logica.ServicioImportacion;
import co.com.bspstore.sgip.modelo.Producto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

/**
 * Rechazar y aplicar la misma carga a la vez.
 *
 * <b>Rechazar mientras se aplica.</b> {@code aplicar} bloquea la cabecera de la carga y la
 * tiene bloqueada hasta confirmar. Si {@code rechazar} la lee sin bloquear (una lectura
 * consistente que ve «validada») y después actualiza la fila sin condición, su UPDATE
 * espera a que {@code aplicar} confirme y, al soltarse, escribe «rechazada» sobre una carga
 * ya aplicada: los costos están en el catálogo y la carga dice que se descartó. La prueba
 * detiene a {@code aplicar} con la cabecera bloqueada, lanza {@code rechazar}, espera (con
 * tope) a comprobar en {@code performance_schema.data_lock_waits} que de verdad se quedó
 * esperando la fila de la carga, y entonces deja confirmar a {@code aplicar}. Gana una
 * sola: la que perdió recibe el error de estado (409) y la carga queda aplicada.
 *
 * <b>Aplicar después de rechazar.</b> La dirección contraria: {@code aplicar} espera la
 * cabecera que {@code rechazar} acaba de cerrar y, ya bloqueada, la ve rechazada y no
 * toca ningún costo. Esta ya estaba bien; la prueba la deja fijada.
 *
 * Crea su propia empresa y lo borra todo al terminar.
 */
@Tag("conBase")
@SpringBootTest
class RechazarYAplicarCargaConcurrentesConBaseTest {

    private static final int PRODUCTOS = 5;
    private static final String COSTO_ANTERIOR = "100.00";
    private static final String COSTO_DE_LA_CARGA = "150.00";

    @Autowired ServicioImportacion servicio;
    @MockitoSpyBean RepositorioImportacion importacion;
    @Autowired ProductoRepositorio repositorioProductos;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired JdbcClient jdbc;

    private EmpresaDePrueba empresa;
    private List<Integer> productos;

    @BeforeEach
    void prepararDatosPropios() {
        empresa = EmpresaDePrueba.crear("Rechazar y aplicar", plataforma, jdbc, categorias, proveedores);
        empresa.entrar();
        productos = new ArrayList<>();
        for (int i = 0; i < PRODUCTOS; i++) {
            String codigo = "R" + i + "-" + empresa.marca;
            Producto p = new Producto(empresa.empresaId, empresa.categoriaId, empresa.proveedorId,
                    codigo, codigo, "Producto " + i, new BigDecimal(COSTO_ANTERIOR));
            p.fijarExistenciaMinima(0);
            productos.add(repositorioProductos.save(p).getId());
        }
    }

    @AfterEach
    void borrarLoQueCreo() {
        if (empresa != null) {
            empresa.borrar(jdbc);
        }
    }

    private Long cargaValidada() {
        Long carga = importacion.insertarCarga(empresa.empresaId, empresa.proveedorId, empresa.usuarioId,
                "prueba.xlsx", PRODUCTOS, PRODUCTOS, 0);
        List<FilaDeCarga> filas = new ArrayList<>();
        int fila = 2;
        for (Integer producto : productos) {
            filas.add(new FilaDeCarga(fila++, producto, "REF" + producto, "Producto",
                    new BigDecimal(COSTO_DE_LA_CARGA), new BigDecimal(COSTO_ANTERIOR), true, null, null));
        }
        importacion.insertarDetalles(empresa.empresaId, carga, filas);
        return carga;
    }

    private Callable<Throwable> haciendo(SecurityContext contexto, Runnable accion) {
        return () -> {
            SecurityContextHolder.setContext(contexto);
            try {
                accion.run();
                return null;
            } catch (Throwable t) {
                return t;
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    /** Espera, con tope, a que un hilo esté esperando de verdad la fila de la carga. */
    private void esperarAQueSeQuedeEsperandoLaCarga(Future<Throwable> quien) throws InterruptedException {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < limite) {
            if (quien.isDone()) {
                fail("Terminó sin esperar a que el otro soltara la carga: no hay bloqueo que los ponga en fila.");
            }
            int esperando = jdbc.sql("""
                            SELECT COUNT(*)
                              FROM performance_schema.data_lock_waits w
                              JOIN performance_schema.data_locks l
                                ON l.engine_lock_id = w.requesting_engine_lock_id
                             WHERE l.object_name = 'cargas_precios'
                            """)
                    .query(Integer.class).single();
            if (esperando > 0) {
                return;
            }
            Thread.sleep(20);
        }
        fail("Nadie se quedó esperando la fila de la carga.");
    }

    private String estadoDe(Long carga) {
        return jdbc.sql("SELECT estado FROM cargas_precios WHERE empresa_id = :e AND carga_id = :c")
                .param("e", empresa.empresaId).param("c", carga).query(String.class).single();
    }

    private void todosLosCostosSon(String esperado, String mensaje) {
        List<BigDecimal> costos = jdbc.sql("SELECT costo FROM productos WHERE empresa_id = :e")
                .param("e", empresa.empresaId).query(BigDecimal.class).list();
        assertEquals(PRODUCTOS, costos.size());
        for (BigDecimal costo : costos) {
            assertEquals(0, new BigDecimal(esperado).compareTo(costo), mensaje + ": " + costos);
        }
    }

    @Test
    void rechazarMientrasSeAplicaNuncaDejaUnaCargaAplicadaMarcadaRechazada() throws Exception {
        Long carga = cargaValidada();
        CountDownLatch aplicarTieneLaCarga = new CountDownLatch(1);
        CountDownLatch soltarAplicar = new CountDownLatch(1);
        // Aplicar llega a marcar la carga con su cabecera bloqueada y se detiene ahí.
        doAnswer(llamada -> {
            aplicarTieneLaCarga.countDown();
            soltarAplicar.await(60, TimeUnit.SECONDS);
            return llamada.callRealMethod();
        }).when(importacion).marcarAplicada(anyInt(), anyLong());

        SecurityContext contexto = SecurityContextHolder.getContext();
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        Throwable alAplicar;
        Throwable alRechazar;
        try {
            Future<Throwable> aplicando = hilos.submit(haciendo(contexto, () -> servicio.aplicar(carga)));
            assertTrue(aplicarTieneLaCarga.await(60, TimeUnit.SECONDS), "aplicar no llegó a marcar la carga");
            Future<Throwable> rechazando = hilos.submit(haciendo(contexto, () -> servicio.rechazar(carga)));
            esperarAQueSeQuedeEsperandoLaCarga(rechazando);
            soltarAplicar.countDown();
            alAplicar = aplicando.get(60, TimeUnit.SECONDS);
            alRechazar = rechazando.get(60, TimeUnit.SECONDS);
        } finally {
            soltarAplicar.countDown();
            hilos.shutdownNow();
            hilos.awaitTermination(30, TimeUnit.SECONDS);
        }

        assertNull(alAplicar, "aplicar llegó primero a la carga y sale bien: " + alAplicar);
        assertEquals("APLICADA", estadoDe(carga),
                "una carga aplicada no puede quedar marcada como rechazada");
        todosLosCostosSon(COSTO_DE_LA_CARGA, "los costos de la carga están en el catálogo");
        assertInstanceOf(EstadoDeCargaInvalidoException.class, alRechazar,
                "rechazar, que llegó después, recibe el error de estado (409): " + alRechazar);
    }

    @Test
    void aplicarDespuesDeQueRechazanLaCargaNoAplicaNingunCosto() throws Exception {
        Long carga = cargaValidada();
        CountDownLatch rechazarTieneLaCarga = new CountDownLatch(1);
        CountDownLatch soltarRechazar = new CountDownLatch(1);
        // Rechazar ya escribió «rechazada» y no ha confirmado: la fila sigue bloqueada.
        doAnswer(llamada -> {
            Object resultado = llamada.callRealMethod();
            rechazarTieneLaCarga.countDown();
            soltarRechazar.await(60, TimeUnit.SECONDS);
            return resultado;
        }).when(importacion).marcarRechazada(anyInt(), anyLong());

        SecurityContext contexto = SecurityContextHolder.getContext();
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        Throwable alRechazar;
        Throwable alAplicar;
        try {
            Future<Throwable> rechazando = hilos.submit(haciendo(contexto, () -> servicio.rechazar(carga)));
            assertTrue(rechazarTieneLaCarga.await(60, TimeUnit.SECONDS), "rechazar no llegó a marcar la carga");
            Future<Throwable> aplicando = hilos.submit(haciendo(contexto, () -> servicio.aplicar(carga)));
            esperarAQueSeQuedeEsperandoLaCarga(aplicando);
            soltarRechazar.countDown();
            alRechazar = rechazando.get(60, TimeUnit.SECONDS);
            alAplicar = aplicando.get(60, TimeUnit.SECONDS);
        } finally {
            soltarRechazar.countDown();
            hilos.shutdownNow();
            hilos.awaitTermination(30, TimeUnit.SECONDS);
        }

        assertNull(alRechazar, "rechazar llegó primero y sale bien: " + alRechazar);
        assertInstanceOf(EstadoDeCargaInvalidoException.class, alAplicar,
                "aplicar, que llegó después, ve la carga rechazada y recibe el error de estado (409): " + alAplicar);
        assertEquals("RECHAZADA", estadoDe(carga));
        todosLosCostosSon(COSTO_ANTERIOR, "ningún costo cambió");
    }
}

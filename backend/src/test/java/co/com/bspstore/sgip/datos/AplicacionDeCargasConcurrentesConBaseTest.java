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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

/**
 * Aplicar listas de precios a la vez (I-A, M-d).
 *
 * <b>Dos cargas con los mismos productos en orden de fila cruzado.</b> El orden en que
 * se bloquean los productos tiene que ser el mismo para todas, sea cual sea el orden de
 * las filas del archivo; si sigue el del archivo, dos aplicaciones simultáneas se
 * esperan entre sí y el motor mata a una (1213). Un solo UPDATE o SELECT bloquea
 * fila por fila y no hay forma de sincronizar a dos hilos dentro de la sentencia, de
 * modo que la prueba usa muchos productos (cada hilo recorre la lista desde un
 * extremo) y arranca a los dos a la vez; con pocos el cruce sería cuestión de suerte.
 *
 * <b>La misma carga aplicada dos veces a la vez.</b> Las dos leen «validada» antes de
 * que ninguna escriba; solo la que bloquea primero la cabecera puede aplicarla, y la
 * otra, al volver a comprobar el estado ya bloqueada, recibe el mismo error que al
 * aplicar una carga que no está validada.
 *
 * Crea su propia empresa y lo borra todo al terminar.
 */
@Tag("conBase")
@SpringBootTest
class AplicacionDeCargasConcurrentesConBaseTest {

    private static final int PRODUCTOS = 300;

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
        empresa = EmpresaDePrueba.crear("Cargas simultaneas", plataforma, jdbc, categorias, proveedores);
        empresa.entrar();
        productos = new ArrayList<>();
        for (int i = 0; i < PRODUCTOS; i++) {
            String codigo = "L" + i + "-" + empresa.marca;
            Producto p = new Producto(empresa.empresaId, empresa.categoriaId, empresa.proveedorId,
                    codigo, codigo, "Producto " + i, new BigDecimal("100.00"));
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

    private Long carga(List<Integer> enEsteOrden, String costo) {
        Long carga = importacion.insertarCarga(empresa.empresaId, empresa.proveedorId, empresa.usuarioId,
                "prueba.xlsx", enEsteOrden.size(), enEsteOrden.size(), 0);
        List<FilaDeCarga> filas = new ArrayList<>();
        int fila = 2;
        for (Integer producto : enEsteOrden) {
            filas.add(new FilaDeCarga(fila++, producto, "REF" + producto, "Producto", new BigDecimal(costo),
                    new BigDecimal("100.00"), true, null, null));
        }
        importacion.insertarDetalles(empresa.empresaId, carga, filas);
        return carga;
    }

    private Callable<Throwable> aplicando(Long carga, SecurityContext contexto, CountDownLatch salida) {
        return () -> {
            SecurityContextHolder.setContext(contexto);
            try {
                salida.await();
                servicio.aplicar(carga);
                return null;
            } catch (Throwable t) {
                return t;
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    private List<Throwable> aplicarALaVez(Long una, Long otra) throws Exception {
        SecurityContext contexto = SecurityContextHolder.getContext();
        CountDownLatch salida = new CountDownLatch(1);
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        List<Throwable> resultados = new ArrayList<>();
        try {
            Future<Throwable> primero = hilos.submit(aplicando(una, contexto, salida));
            Future<Throwable> segundo = hilos.submit(aplicando(otra, contexto, salida));
            salida.countDown();
            resultados.add(primero.get(120, TimeUnit.SECONDS));
            resultados.add(segundo.get(120, TimeUnit.SECONDS));
        } finally {
            hilos.shutdownNow();
            hilos.awaitTermination(30, TimeUnit.SECONDS);
        }
        return resultados;
    }

    @Test
    void dosCargasConLosMismosProductosEnOrdenCruzadoSeAplicanSinInterbloqueo() throws Exception {
        List<Integer> alReves = new ArrayList<>(productos);
        Collections.reverse(alReves);
        Long ascendente = carga(productos, "150.00");
        Long descendente = carga(alReves, "175.00");

        List<Throwable> resultados = aplicarALaVez(ascendente, descendente);

        assertEquals(List.of(), resultados.stream().filter(r -> r != null).toList(),
                "las dos cargas se aplican, una detrás de la otra, sin interbloqueo");
        assertEquals(2, jdbc.sql("""
                SELECT COUNT(*) FROM cargas_precios
                 WHERE empresa_id = :e AND estado = 'APLICADA'
                """).param("e", empresa.empresaId).query(Integer.class).single());
        assertEquals(PRODUCTOS, jdbc.sql("""
                SELECT COUNT(*) FROM historico_costos
                 WHERE empresa_id = :e AND vigente_hasta IS NULL
                """).param("e", empresa.empresaId).query(Integer.class).single(),
                "un solo periodo abierto por producto");
    }

    @Test
    void laMismaCargaAplicadaDosVecesALaVezSoloSeAplicaUnaVez() throws Exception {
        Long unica = carga(productos, "150.00");
        // Las dos leen la carga antes de que ninguna escriba (con tope de espera).
        CyclicBarrier ambasLeyeron = new CyclicBarrier(2);
        doAnswer(llamada -> {
            Object cabecera = llamada.callRealMethod();
            try {
                ambasLeyeron.await(3, TimeUnit.SECONDS);
            } catch (Exception sinLaOtra) {
                // La otra espera un bloqueo: es lo que pasa con la cabecera bloqueada primero.
            }
            return cabecera;
        }).when(importacion).buscarCarga(anyInt(), anyLong());

        List<Throwable> resultados = aplicarALaVez(unica, unica);

        assertEquals(1, resultados.stream().filter(r -> r == null).count(),
                "solo una aplicación sale bien: " + resultados);
        resultados.stream().filter(r -> r != null).forEach(r -> assertInstanceOf(
                EstadoDeCargaInvalidoException.class, r, "la otra recibe el error de carga ya aplicada: " + r));
        assertEquals(PRODUCTOS, jdbc.sql("""
                SELECT COUNT(*) FROM historico_costos
                 WHERE empresa_id = :e AND vigente_hasta IS NULL AND carga_id = :c
                """).param("e", empresa.empresaId).param("c", unica).query(Integer.class).single());
    }
}

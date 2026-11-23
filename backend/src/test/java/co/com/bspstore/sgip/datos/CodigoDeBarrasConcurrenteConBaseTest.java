package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.SolicitudEdicionProducto;
import co.com.bspstore.sgip.api.dto.SolicitudPrecio;
import co.com.bspstore.sgip.api.dto.SolicitudProducto;
import co.com.bspstore.sgip.logica.NombreRepetidoException;
import co.com.bspstore.sgip.logica.ServicioProducto;
import co.com.bspstore.sgip.modelo.Producto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * El código de barras de un producto es único por empresa (V33), y la caja lo lee con la
 * pistola: lo que pasa cuando dos altas lo reclaman a la vez (M13) y lo que le dice la
 * pantalla a quien choca con un producto que ya no se vende (M12).
 *
 * La carrera no se deja al azar. El primer hilo da de alta su producto dentro de una
 * transacción que mantiene sin confirmar; el segundo pasa su comprobación previa (no ve
 * nada, la fila del primero aún no existe para nadie) y su INSERT queda esperando; al
 * confirmar el primero, el segundo choca con el índice único. Sin la unicidad de la base
 * hay dos productos con la misma etiqueta, y sin la traducción del choque el segundo
 * recibe una excepción de acceso a datos y no el 409 del servicio.
 *
 * Crea sus empresas y las borra al terminar. Las esperas tienen tiempo máximo y los hilos
 * se cierran siempre.
 */
@Tag("conBase")
@SpringBootTest
class CodigoDeBarrasConcurrenteConBaseTest {

    private static final long ESPERA_MAXIMA_EN_SEGUNDOS = 20;
    private static final String BARRAS = "7701234567890";
    private static final String TEXTO_DEL_INACTIVO =
            "Ese código de barras ya lo usa un producto inactivo de tu empresa.";

    @Autowired ServicioProducto servicio;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired PlatformTransactionManager transacciones;
    @Autowired JdbcClient jdbc;

    private final List<EmpresaDePrueba> empresasCreadas = new ArrayList<>();
    private EmpresaDePrueba empresa;

    @BeforeEach
    void prepararDatosPropios() {
        empresa = nuevaEmpresa("Barras");
        empresa.entrar();
    }

    @AfterEach
    void borrarLoQueCreo() {
        for (EmpresaDePrueba creada : empresasCreadas) {
            creada.borrar(jdbc);
        }
    }

    private EmpresaDePrueba nuevaEmpresa(String nombre) {
        EmpresaDePrueba creada = EmpresaDePrueba.crear(nombre, plataforma, jdbc, categorias, proveedores);
        empresasCreadas.add(creada);
        return creada;
    }

    private SolicitudProducto alta(EmpresaDePrueba de, String codigo, String barras) {
        return new SolicitudProducto(de.categoriaId, de.proveedorId, codigo, null, barras,
                "Producto " + codigo, null, null, new BigDecimal("1000.00"), null, null,
                new SolicitudPrecio(new BigDecimal("20"), null), null);
    }

    private String codigo(String letra) {
        return letra + "-" + empresa.marca;
    }

    private int productosConBarras(EmpresaDePrueba de, String barras) {
        return jdbc.sql("SELECT COUNT(*) FROM productos WHERE empresa_id = :e AND codigo_barras = :b")
                .param("e", de.empresaId).param("b", barras).query(Integer.class).single();
    }

    private void desactivar(Integer productoId) {
        jdbc.sql("UPDATE productos SET activo = 0 WHERE empresa_id = :e AND producto_id = :id")
                .param("e", empresa.empresaId).param("id", productoId).update();
    }

    // ------------------------------------------------------- M13: la carrera

    /**
     * Dos altas con el mismo código de barras a la vez dejan UN producto, y la otra recibe el
     * 409 del servicio (un {@link NombreRepetidoException}), no un error de la base.
     */
    @Test
    void dosAltasSimultaneasConElMismoCodigoDejanUnaYDan409() throws Exception {
        Throwable fallo = carrera(alta(empresa, codigo("A"), BARRAS), alta(empresa, codigo("B"), BARRAS));

        assertInstanceOf(NombreRepetidoException.class, fallo,
                "el choque con la unicidad del código de barras debe ser un 409 y no un error de la base: " + fallo);
        assertEquals(1, productosConBarras(empresa, BARRAS));
        assertEquals("Ya existe un producto con ese código de barras.", fallo.getMessage(),
                "texto fijo, sin repetir lo enviado");
    }

    /**
     * Solo se traduce el choque de ESE índice. Una carrera por el código interno sigue siendo
     * un error de integridad, que la red de seguridad de {@code ManejadorErrores} contesta con
     * su propio texto: decir «código de barras repetido» cuando lo repetido es el código
     * mandaría a quien corrige a mirar el campo equivocado.
     */
    @Test
    void unaCarreraPorElCodigoInternoNoSeDisfrazaDeCodigoDeBarras() throws Exception {
        String comun = codigo("C");

        Throwable fallo = carrera(alta(empresa, comun, "7700000000001"), alta(empresa, comun, "7700000000002"));

        assertInstanceOf(DataIntegrityViolationException.class, fallo,
                "otra violación de integridad no debe disfrazarse de código de barras: " + fallo);
        assertFalse(fallo instanceof NombreRepetidoException);
        Integer productos = jdbc.sql("SELECT COUNT(*) FROM productos WHERE empresa_id = :e AND codigo = :c")
                .param("e", empresa.empresaId).param("c", comun).query(Integer.class).single();
        assertEquals(1, productos);
    }

    // ------------------------------------------------------- M12: el dueño inactivo

    @Test
    void codigoRepetidoDeUnInactivoLoDiceAlCrear() {
        Producto viejo = servicio.crear(alta(empresa, codigo("A"), BARRAS));
        desactivar(viejo.getId());

        NombreRepetidoException e = assertThrows(NombreRepetidoException.class,
                () -> servicio.crear(alta(empresa, codigo("B"), BARRAS)));

        assertEquals(TEXTO_DEL_INACTIVO, e.getMessage());
        assertEquals(1, productosConBarras(empresa, BARRAS));
    }

    @Test
    void codigoRepetidoDeUnInactivoLoDiceAlEditar() {
        Producto viejo = servicio.crear(alta(empresa, codigo("A"), BARRAS));
        desactivar(viejo.getId());
        Producto otro = servicio.crear(alta(empresa, codigo("B"), null));

        NombreRepetidoException e = assertThrows(NombreRepetidoException.class,
                () -> servicio.actualizar(otro.getId(), new SolicitudEdicionProducto(
                        empresa.categoriaId, empresa.proveedorId, codigo("B"), null, BARRAS,
                        "Producto B", null, null, new BigDecimal("1000.00"), null,
                        new SolicitudPrecio(new BigDecimal("20"), null), null, null, otro.getVersion())));

        assertEquals(TEXTO_DEL_INACTIVO, e.getMessage());
    }

    @Test
    void conUnDuenoActivoSeConservaElMensajeDeSiempre() {
        servicio.crear(alta(empresa, codigo("A"), BARRAS));

        NombreRepetidoException e = assertThrows(NombreRepetidoException.class,
                () -> servicio.crear(alta(empresa, codigo("B"), BARRAS)));

        assertEquals("Ya existe un producto con el código de barras " + BARRAS + ".", e.getMessage());
    }

    @Test
    void elCodigoDeUnInactivoDeOtraEmpresaNoEsAsuntoDeEstaNiSeRevela() {
        EmpresaDePrueba otra = nuevaEmpresa("Barras ajena");
        otra.entrar();
        Producto ajeno = servicio.crear(alta(otra, "AJENO-" + otra.marca, BARRAS));
        jdbc.sql("UPDATE productos SET activo = 0 WHERE empresa_id = :e AND producto_id = :id")
                .param("e", otra.empresaId).param("id", ajeno.getId()).update();
        empresa.entrar();

        Producto propio = servicio.crear(alta(empresa, codigo("A"), BARRAS));

        assertNotNull(propio.getId(), "cada empresa etiqueta lo suyo: el código de otra no cuenta");
        assertEquals(1, productosConBarras(empresa, BARRAS));
        assertEquals(1, productosConBarras(otra, BARRAS));
    }

    // ------------------------------------------------------- ayudas

    /**
     * Da de alta el primer producto en una transacción sin confirmar, deja que el segundo
     * alta se quede esperando en el INSERT, confirma el primero y devuelve el fallo del
     * segundo. El primero siempre termina bien.
     */
    private Throwable carrera(SolicitudProducto delPrimero, SolicitudProducto delSegundo) throws Exception {
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        CountDownLatch primeroInserto = new CountDownLatch(1);
        CountDownLatch soltarAlPrimero = new CountDownLatch(1);
        try {
            Future<Integer> primero = hilos.submit(() -> {
                empresa.entrar();
                try {
                    return new TransactionTemplate(transacciones).execute(estado -> {
                        Integer id = servicio.crear(delPrimero).getId();
                        primeroInserto.countDown();
                        esperar(soltarAlPrimero);
                        return id;
                    });
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            assertTrue(primeroInserto.await(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS),
                    "el primer hilo no llegó a dar de alta su producto");

            Future<Producto> segundo = hilos.submit(() -> {
                empresa.entrar();
                try {
                    return servicio.crear(delSegundo);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            esperarAQueSeQuedeBloqueado(segundo);

            soltarAlPrimero.countDown();
            assertNotNull(primero.get(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS));
            ExecutionException fallo = assertThrows(ExecutionException.class,
                    () -> segundo.get(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS),
                    "el segundo alta debía fallar");
            return fallo.getCause();
        } finally {
            soltarAlPrimero.countDown();
            cerrar(hilos);
        }
    }

    /** Cierra los hilos y espera, con tiempo máximo, a que terminen: ninguno queda vivo tras la prueba. */
    private static void cerrar(ExecutorService hilos) throws InterruptedException {
        hilos.shutdownNow();
        assertTrue(hilos.awaitTermination(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS),
                "un hilo de la prueba no terminó");
    }

    private static void esperar(CountDownLatch cerrojo) {
        try {
            if (!cerrojo.await(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("nadie soltó al hilo a tiempo");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void esperarAQueSeQuedeBloqueado(Future<?> enEspera) throws InterruptedException {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(ESPERA_MAXIMA_EN_SEGUNDOS);
        while (System.nanoTime() < limite) {
            if (enEspera.isDone()) {
                fail("Terminó sin esperar a la fila del primero: " + comoTermino(enEspera));
            }
            int esperando = jdbc.sql("SELECT COUNT(*) FROM performance_schema.data_lock_waits")
                    .query(Integer.class).single();
            if (esperando > 0) {
                return;
            }
            Thread.sleep(20);
        }
        fail("Nadie se quedó esperando: la unicidad no está poniendo en fila al segundo alta.");
    }

    private static String comoTermino(Future<?> f) {
        try {
            f.get(1, TimeUnit.SECONDS);
            return "sin error";
        } catch (ExecutionException | TimeoutException | InterruptedException e) {
            return String.valueOf(e);
        }
    }
}

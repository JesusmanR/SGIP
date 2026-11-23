package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.CobroRegistrado;
import co.com.bspstore.sgip.api.dto.Domicilio;
import co.com.bspstore.sgip.api.dto.EntregaRegistrada;
import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.api.dto.VentaRegistrada;
import co.com.bspstore.sgip.logica.EstadoNoAdmiteLaOperacionException;
import co.com.bspstore.sgip.logica.ServicioDomicilios;
import co.com.bspstore.sgip.logica.ServicioEncargos;
import co.com.bspstore.sgip.logica.ServicioVentas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Dos hilos sobre el mismo domicilio o el mismo encargo: dos cobros, dos entregas. Cada prueba
 * fuerza el cruce: sostiene desde la prueba el bloqueo de la fila que el servicio va a pedir, deja
 * que las dos peticiones lleguen a esperarlo ({@code performance_schema} dice que de verdad esperan)
 * y las suelta juntas. Una carrera que no se fuerza solo falla algunas veces.
 *
 * Crea su empresa y la borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class DomiciliosConcurrentesConBaseTest {

    private static final long ESPERA_MAXIMA_EN_SEGUNDOS = 30;
    private static final Domicilio CONTRA_ENTREGA = new Domicilio("Calle 1 # 2-3", "Cali", "3001112233", "Ana Pérez", true);

    @Autowired ServicioVentas ventas;
    @Autowired ServicioDomicilios domicilios;
    @Autowired ServicioEncargos encargos;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioClientes clientes;
    @Autowired PlatformTransactionManager transacciones;
    @Autowired JdbcClient jdbc;

    private CajaDePrueba caja;
    private Long turnoDelAdministrador;
    private Long turnoDelVendedor;
    private ExecutorService hilos;

    @BeforeEach
    void prepararDatosPropios() {
        caja = CajaDePrueba.crear("Domicilios concurrentes", jdbc, plataforma, categorias, proveedores, clientes);
        turnoDelAdministrador = caja.abrirTurno(caja.administradorId);
        turnoDelVendedor = caja.abrirTurno(caja.vendedorId);
        caja.entrarComoAdministrador();
        hilos = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void borrarLoQueCreo() throws InterruptedException {
        if (hilos != null) {
            hilos.shutdownNow();
            assertTrue(hilos.awaitTermination(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS), "un hilo de la prueba no terminó");
        }
        SecurityContextHolder.clearContext();
        if (caja != null) {
            caja.borrar();
        }
    }

    // ------------------------------------------------------- ayudas

    private VentaRegistrada contraEntrega(Integer producto) {
        return ventas.registrar(new SolicitudVenta(UUID.randomUUID(), caja.clienteId,
                List.of(new LineaSolicitada(producto, null, 1, null, null)), null, List.of(), null, null, CONTRA_ENTREGA));
    }

    private List<PagoSolicitado> pagoTotal() {
        return List.of(new PagoSolicitado(caja.efectivoId, new BigDecimal("10000"), null, null));
    }

    private <T> Future<T> comoAdministrador(Callable<T> trabajo) {
        return como(caja.contextoDe(caja.administradorId, "ADMINISTRADOR"), trabajo);
    }

    private <T> Future<T> comoVendedor(Callable<T> trabajo) {
        return como(caja.contextoDe(caja.vendedorId, "VENDEDOR"), trabajo);
    }

    private <T> Future<T> como(SecurityContext contexto, Callable<T> trabajo) {
        return hilos.submit(() -> {
            SecurityContextHolder.setContext(contexto);
            try {
                return trabajo.call();
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
    }

    private int esperasEn(String tabla) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                  JOIN performance_schema.data_locks l ON l.engine_lock_id = w.requesting_engine_lock_id
                 WHERE l.object_schema = DATABASE() AND l.object_name = :tabla
                """).param("tabla", tabla).query(Integer.class).single();
    }

    private void esperarAQueEsperen(String tabla, int cuantas, Future<?>... enMarcha) throws InterruptedException {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(ESPERA_MAXIMA_EN_SEGUNDOS);
        while (System.nanoTime() < limite) {
            if (esperasEn(tabla) >= cuantas) {
                return;
            }
            for (Future<?> f : enMarcha) {
                if (f.isDone()) {
                    fail("Un hilo terminó sin quedarse esperando en " + tabla + ": " + comoTermino(f));
                }
            }
            Thread.sleep(20);
        }
        fail("Nadie esperaba en " + tabla + ": " + esperasEn(tabla) + " de " + cuantas);
    }

    private static String comoTermino(Future<?> f) {
        try {
            f.get(1, TimeUnit.SECONDS);
            return "sin error";
        } catch (ExecutionException | TimeoutException | InterruptedException e) {
            return String.valueOf(e);
        }
    }

    /** Una transacción abierta desde la prueba que mantiene un bloqueo hasta que se la suelte. */
    private final class Bloqueo implements AutoCloseable {
        private final CountDownLatch soltar = new CountDownLatch(1);
        private final CountDownLatch tomado = new CountDownLatch(1);
        private final Future<?> transaccion;

        Bloqueo(Runnable tomar) {
            transaccion = hilos.submit(() -> new TransactionTemplate(transacciones).execute(estado -> {
                tomar.run();
                tomado.countDown();
                esperar(soltar);
                return null;
            }));
            try {
                long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(ESPERA_MAXIMA_EN_SEGUNDOS);
                while (!tomado.await(20, TimeUnit.MILLISECONDS)) {
                    if (transaccion.isDone() || System.nanoTime() > limite) {
                        fail("no se tomó el bloqueo: " + comoTermino(transaccion));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void close() throws Exception {
            soltar.countDown();
            transaccion.get(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS);
        }
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

    private static <T> T resultado(Future<T> f) throws Exception {
        return f.get(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS);
    }

    /** Lo que quedó de dos peticiones iguales: cuántas salieron bien y con qué falló la otra. */
    private static <T> Throwable laQueFallo(Future<T> uno, Future<T> otro, int salieronBien) throws Exception {
        int bien = 0;
        Throwable fallo = null;
        for (Future<T> f : List.of(uno, otro)) {
            try {
                resultado(f);
                bien++;
            } catch (ExecutionException e) {
                fallo = e.getCause();
            }
        }
        assertEquals(salieronBien, bien, "peticiones que salieron bien");
        return fallo;
    }

    // ------------------------------------------------------- dos cobros a la vez

    @Test
    void dosCobrosSimultaneosUnoCobraYElOtroRecibe409() throws Exception {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada r = contraEntrega(producto);
        Future<CobroRegistrado> delAdministrador;
        Future<CobroRegistrado> delVendedor;
        // El bloqueo de la fila de la venta: lo que el cobro tiene que tomar para ser el único.
        try (Bloqueo bloqueo = new Bloqueo(() -> jdbc.sql("""
                SELECT venta_id FROM ventas FORCE INDEX (PRIMARY) WHERE venta_id = :v AND empresa_id = :e FOR UPDATE
                """).param("v", r.id()).param("e", caja.empresa.empresaId).query(Long.class).single())) {
            delAdministrador = comoAdministrador(() -> domicilios.cobrar(r.id(), pagoTotal()));
            delVendedor = comoVendedor(() -> domicilios.cobrar(r.id(), pagoTotal()));
            // Los dos tienen su turno y esperan la venta: el que gane cobra, el otro encuentra la venta pagada.
            esperarAQueEsperen("ventas", 2, delAdministrador, delVendedor);
        }

        Throwable perdedor = laQueFallo(delAdministrador, delVendedor, 1);

        assertInstanceOf(EstadoNoAdmiteLaOperacionException.class, perdedor, String.valueOf(perdedor));
        assertEquals(1, caja.cuantas("pagos_venta"), "el dinero entró una sola vez");
        assertEquals("PAGADA", jdbc.sql("SELECT estado_pago FROM ventas WHERE venta_id = :v")
                .param("v", r.id()).query(String.class).single());
        Long turnoDelPago = jdbc.sql("SELECT cierre_id FROM pagos_venta WHERE venta_id = :v")
                .param("v", r.id()).query(Long.class).single();
        assertTrue(turnoDelPago.equals(turnoDelAdministrador) || turnoDelPago.equals(turnoDelVendedor));
    }

    // ------------------------------------------------------- dos entregas a la vez

    @Test
    void dosEntregasSimultaneasDeUnDomicilioUnaMarcaYLaOtraRecibe409() throws Exception {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada r = contraEntrega(producto);
        Future<EntregaRegistrada> uno;
        Future<EntregaRegistrada> otro;
        // La fila del domicilio tomada desde fuera: las dos entregas llegan a la misma actualización.
        try (Bloqueo bloqueo = new Bloqueo(() -> jdbc.sql("""
                SELECT venta_id FROM domicilios_venta WHERE venta_id = :v AND empresa_id = :e FOR UPDATE
                """).param("v", r.id()).param("e", caja.empresa.empresaId).query(Long.class).single())) {
            uno = comoAdministrador(() -> domicilios.entregar(r.id()));
            otro = comoVendedor(() -> domicilios.entregar(r.id()));
            // Las dos toman primero la venta (el orden global: la venta antes que su domicilio): la primera
            // llega a la fila del domicilio y espera ahí; la otra espera la venta que la primera tiene.
            esperarAQueEsperen("domicilios_venta", 1, uno, otro);
            esperarAQueEsperen("ventas", 1, uno, otro);
        }

        Throwable perdedora = laQueFallo(uno, otro, 1);

        assertInstanceOf(EstadoNoAdmiteLaOperacionException.class, perdedora, String.valueOf(perdedora));
        Map<String, Object> d = jdbc.sql("SELECT entregado_por FROM domicilios_venta WHERE venta_id = :v")
                .param("v", r.id()).query().singleRow();
        assertTrue(d.get("entregado_por").equals(caja.administradorId) || d.get("entregado_por").equals(caja.vendedorId));
    }

    @Test
    void dosEntregasSimultaneasDeUnEncargoUnaMarcaYLaOtraRecibe409() throws Exception {
        Integer producto = caja.producto("A", 0, "10000");
        VentaRegistrada r = ventas.registrar(new SolicitudVenta(UUID.randomUUID(), null,
                List.of(new LineaSolicitada(producto, null, 1, null, LocalDate.now().plusDays(7))), null,
                pagoTotal(), "No había existencia", null, null));
        Long item = jdbc.sql("SELECT item_id FROM venta_items WHERE venta_id = :v")
                .param("v", r.id()).query(Long.class).single();
        Future<EntregaRegistrada> uno;
        Future<EntregaRegistrada> otro;
        try (Bloqueo bloqueo = new Bloqueo(() -> jdbc.sql("""
                SELECT item_id FROM venta_items WHERE item_id = :i AND empresa_id = :e FOR UPDATE
                """).param("i", item).param("e", caja.empresa.empresaId).query(Long.class).single())) {
            uno = comoAdministrador(() -> encargos.entregar(item));
            otro = comoAdministrador(() -> encargos.entregar(item));
            // Primero la venta, después la línea: una espera la línea y la otra espera la venta de la primera.
            esperarAQueEsperen("venta_items", 1, uno, otro);
            esperarAQueEsperen("ventas", 1, uno, otro);
        }

        Throwable perdedora = laQueFallo(uno, otro, 1);

        assertInstanceOf(EstadoNoAdmiteLaOperacionException.class, perdedora, String.valueOf(perdedora));
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM venta_items WHERE item_id = :i AND encargo_entregado_en IS NOT NULL")
                .param("i", item).query(Integer.class).single());
    }

    // ------------------------------------------------------- una entrega y un cobro de la misma venta

    @Test
    void unaEntregaYUnCobroDeLaMismaVentaSeHacenLosDosSinInterbloquearse() throws Exception {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada r = contraEntrega(producto);
        Future<EntregaRegistrada> entrega;
        Future<CobroRegistrado> cobro;
        try (Bloqueo bloqueo = new Bloqueo(() -> jdbc.sql("""
                SELECT venta_id FROM ventas FORCE INDEX (PRIMARY) WHERE venta_id = :v AND empresa_id = :e FOR UPDATE
                """).param("v", r.id()).param("e", caja.empresa.empresaId).query(Long.class).single())) {
            cobro = comoVendedor(() -> domicilios.cobrar(r.id(), pagoTotal()));
            entrega = comoAdministrador(() -> domicilios.entregar(r.id()));
            esperarAQueEsperen("ventas", 2, cobro, entrega);
        }

        resultado(cobro);
        resultado(entrega);

        assertEquals("PAGADA", jdbc.sql("SELECT estado_pago FROM ventas WHERE venta_id = :v")
                .param("v", r.id()).query(String.class).single());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM domicilios_venta WHERE venta_id = :v AND entregado_en IS NOT NULL")
                .param("v", r.id()).query(Integer.class).single());
    }

    // ------------------------------------------------------- una entrega y una anulación de la misma venta

    /** Lo que hace la anulación mientras no confirma: tiene la fila de la venta y ya la marcó anulada. */
    private Bloqueo anulando(Long ventaId) {
        return new Bloqueo(() -> jdbc.sql("""
                UPDATE ventas SET estado = 'ANULADA', estado_pago = 'ANULADA' WHERE venta_id = :v AND empresa_id = :e
                """).param("v", ventaId).param("e", caja.empresa.empresaId).update());
    }

    @Test
    void unaEntregaDeUnDomicilioQueLlegaMientrasSeAnulaLaVentaEsperaLaVentaYAlFinalRecibe409SinMarca() throws Exception {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada r = contraEntrega(producto);
        Future<EntregaRegistrada> entrega;
        try (Bloqueo anulacion = anulando(r.id())) {
            entrega = comoAdministrador(() -> domicilios.entregar(r.id()));
            // La entrega tiene que pedir la venta antes de marcar: si marcara primero y leyera después, vería
            // la venta vigente (la anulación no ha confirmado) y la marca sobreviviría a la anulación.
            esperarAQueEsperen("ventas", 1, entrega);
        }

        Future<EntregaRegistrada> enMarcha = entrega;
        Throwable fallo = assertThrows(ExecutionException.class, () -> resultado(enMarcha)).getCause();

        assertInstanceOf(EstadoNoAdmiteLaOperacionException.class, fallo, String.valueOf(fallo));
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM domicilios_venta WHERE venta_id = :v AND entregado_en IS NOT NULL")
                .param("v", r.id()).query(Integer.class).single(), "no quedó marca de entrega");
    }

    @Test
    void unaEntregaDeUnEncargoQueLlegaMientrasSeAnulaLaVentaEsperaLaVentaYAlFinalRecibe409SinMarca() throws Exception {
        Integer producto = caja.producto("A", 0, "10000");
        VentaRegistrada r = ventas.registrar(new SolicitudVenta(UUID.randomUUID(), null,
                List.of(new LineaSolicitada(producto, null, 1, null, LocalDate.now().plusDays(7))), null,
                pagoTotal(), "No había existencia", null, null));
        Long item = jdbc.sql("SELECT item_id FROM venta_items WHERE venta_id = :v")
                .param("v", r.id()).query(Long.class).single();
        Future<EntregaRegistrada> entrega;
        try (Bloqueo anulacion = anulando(r.id())) {
            entrega = comoAdministrador(() -> encargos.entregar(item));
            esperarAQueEsperen("ventas", 1, entrega);
        }

        Future<EntregaRegistrada> enMarcha = entrega;
        Throwable fallo = assertThrows(ExecutionException.class, () -> resultado(enMarcha)).getCause();

        assertInstanceOf(EstadoNoAdmiteLaOperacionException.class, fallo, String.valueOf(fallo));
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM venta_items WHERE item_id = :i AND encargo_entregado_en IS NOT NULL")
                .param("i", item).query(Integer.class).single(), "no quedó marca de entrega");
    }
}

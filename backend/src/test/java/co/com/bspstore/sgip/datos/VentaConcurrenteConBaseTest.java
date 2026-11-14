package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.logica.ClaveDeIdempotenciaEnUsoException;
import co.com.bspstore.sgip.logica.ExistenciaInsuficienteException;
import co.com.bspstore.sgip.logica.ResultadoDeVenta;
import co.com.bspstore.sgip.logica.ServicioTurnos;
import co.com.bspstore.sgip.logica.ServicioVentas;
import co.com.bspstore.sgip.logica.SinTurnoAbiertoException;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * La venta contra dos hilos: la última unidad, el reintento en paralelo, los números seguidos y
 * el cierre del turno. Cada prueba fuerza el cruce en lugar de esperar a que ocurra solo:
 * sostiene un bloqueo desde la prueba, o hace que los dos hilos lleguen juntos a un punto, y
 * comprueba con {@code performance_schema} que de verdad se están esperando.
 *
 * Esperas con tiempo máximo y hilos que se cierran siempre. Crea su empresa y la borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class VentaConcurrenteConBaseTest {

    private static final long ESPERA_MAXIMA_EN_SEGUNDOS = 30;

    @Autowired ServicioVentas ventas;
    @Autowired ServicioTurnos turnos;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioClientes clientes;
    @Autowired PlatformTransactionManager transacciones;
    @Autowired JdbcClient jdbc;
    @MockitoSpyBean RepositorioConsecutivos consecutivos;
    @MockitoSpyBean RepositorioVentas repositorioVentas;

    private CajaDePrueba caja;
    private Long turnoDelAdministrador;
    private Long turnoDelVendedor;
    private ExecutorService hilos;

    @BeforeEach
    void prepararDatosPropios() {
        caja = CajaDePrueba.crear("Concurrente", jdbc, plataforma, categorias, proveedores, clientes);
        turnoDelAdministrador = caja.abrirTurno(caja.administradorId);
        turnoDelVendedor = caja.abrirTurno(caja.vendedorId);
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

    private PagoSolicitado efectivo(String monto) {
        return new PagoSolicitado(caja.efectivoId, new BigDecimal(monto), null, null);
    }

    private SolicitudVenta venta(UUID clave, Integer producto, int cantidad, String pago) {
        return new SolicitudVenta(clave, null, List.of(new LineaSolicitada(producto, null, cantidad, null, null)), null,
                List.of(efectivo(pago)), null, null, null);
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

    /** Cuántas peticiones esperan hoy un bloqueo sobre filas de esa tabla (filtra por tabla). */
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
                assertTrue(tomado.await(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS), "no se tomó el bloqueo");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        void soltar() {
            soltar.countDown();
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

    private Bloqueo bloquearElProducto(Integer producto) {
        return new Bloqueo(() -> jdbc.sql("""
                SELECT existencia FROM productos WHERE empresa_id = :e AND bloqueoid = :p FOR UPDATE
                """).param("e", caja.empresa.empresaId).param("p", producto).query(Integer.class).single());
    }

    private Long tomarElTurnoComoElCierre(Long turno) {
        return jdbc.sql("""
                SELECT cierre_id FROM cierres_caja FORCE INDEX (PRIMARY)
                 WHERE empresa_id = :e AND cierre_id = :id FOR UPDATE
                """).param("e", caja.empresa.empresaId).param("id", turno).query(Long.class).single();
    }

    private int cerrarComoElCierre(Long turno) {
        return jdbc.sql("""
                UPDATE cierres_caja SET cierre = apertura WHERE empresa_id = :e AND cierre_id = :id
                """).param("e", caja.empresa.empresaId).param("id", turno).update();
    }

    private static <T> T resultado(Future<T> f) throws Exception {
        return f.get(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS);
    }

    private static Throwable fallo(Future<?> f) throws Exception {
        ExecutionException e = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                () -> f.get(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS));
        return e.getCause();
    }

    // ------------------------------------------------------- la última unidad

    @Test
    void dosCajerosLaUltimaUnidad() throws Exception {
        Integer producto = caja.producto("A", 1, "10000");
        Future<ResultadoDeVenta> delAdministrador;
        Future<ResultadoDeVenta> delVendedor;
        try (Bloqueo bloqueo = bloquearElProducto(producto)) {
            delAdministrador = comoAdministrador(() -> ventas.procesar(venta(UUID.randomUUID(), producto, 1, "10000")));
            delVendedor = comoVendedor(() -> ventas.procesar(venta(UUID.randomUUID(), producto, 1, "10000")));
            // Los dos llegaron a la unidad y ninguno la tiene: se esperan al mismo bloqueo.
            esperarAQueEsperen("productos", 2, delAdministrador, delVendedor);
        }

        int vendidas = 0;
        int rechazadas = 0;
        for (Future<ResultadoDeVenta> f : List.of(delAdministrador, delVendedor)) {
            try {
                resultado(f);
                vendidas++;
            } catch (ExecutionException e) {
                assertInstanceOf(ExistenciaInsuficienteException.class, e.getCause(), String.valueOf(e.getCause()));
                rechazadas++;
            }
        }
        assertEquals(1, vendidas, "una venta");
        assertEquals(1, rechazadas, "y un 409");
        assertEquals(0, caja.existencia(producto), "la existencia nunca queda negativa sin autorización");
        assertEquals(1, caja.cuantas("ventas"));
        assertEquals(1, caja.movimientosDe(producto, "EGRESO"));
    }

    // ------------------------------------------------------- el mismo envío por dos hilos

    @Test
    void mismaClaveEnParaleloUnaSolaVenta() throws Exception {
        Integer producto = caja.producto("A", 5, "10000");
        SolicitudVenta solicitud = venta(UUID.randomUUID(), producto, 2, "20000");
        Future<ResultadoDeVenta> uno;
        Future<ResultadoDeVenta> otro;
        try (Bloqueo bloqueo = bloquearElProducto(producto)) {
            uno = comoAdministrador(() -> ventas.procesar(solicitud));
            otro = comoAdministrador(() -> ventas.procesar(solicitud));
            esperarAQueEsperen("productos", 2, uno, otro);
        }

        ResultadoDeVenta a = resultado(uno);
        ResultadoDeVenta b = resultado(otro);

        assertNotEquals(a.yaExistia(), b.yaExistia(), "uno recibe el 201 y el otro el 200");
        assertEquals(a.venta().id(), b.venta().id(), "los dos reciben la misma venta");
        assertEquals(a.venta(), b.venta());
        assertEquals(1, caja.cuantas("ventas"));
        assertEquals(1, caja.movimientosDe(producto, "EGRESO"));
        assertEquals(3, caja.existencia(producto), "el descuento se hizo una sola vez");
    }

    @Test
    void mismaClaveConOtraCargaEnParaleloEs409ConSuTextoYNoUnErrorDeLaBase() throws Exception {
        // Dos peticiones con la misma clave pero productos distintos no se esperan por los productos:
        // las dos pasan la comprobación de la clave sin ver nada. Se juntan en el consecutivo; la
        // que llega segunda inserta, choca con la clave única de la primera y se descarta entera.
        Integer a = caja.producto("A", 5, "10000");
        Integer b = caja.producto("B", 5, "10000");
        UUID clave = UUID.randomUUID();
        CyclicBarrier juntas = new CyclicBarrier(2);
        doAnswer(llamada -> {
            juntas.await(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS);
            return llamada.callRealMethod();
        }).when(consecutivos).tomarSiguiente(any(), any());

        Future<ResultadoDeVenta> primera = comoAdministrador(() -> ventas.procesar(venta(clave, a, 1, "10000")));
        Future<ResultadoDeVenta> segunda = comoVendedor(() -> ventas.procesar(venta(clave, b, 1, "10000")));

        List<Future<ResultadoDeVenta>> las2 = List.of(primera, segunda);
        int vendidas = 0;
        Throwable rechazo = null;
        for (Future<ResultadoDeVenta> f : las2) {
            try {
                resultado(f);
                vendidas++;
            } catch (ExecutionException e) {
                rechazo = e.getCause();
            }
        }
        assertEquals(1, vendidas);
        assertNotNull(rechazo);
        assertInstanceOf(ClaveDeIdempotenciaEnUsoException.class, rechazo,
                "la clave repetida con otra carga es un 409 propio: " + rechazo);
        assertEquals(1, caja.cuantas("ventas"));
        assertEquals(1, caja.movimientosDe(a, "EGRESO") + caja.movimientosDe(b, "EGRESO"));
        assertEquals(9, caja.existencia(a) + caja.existencia(b), "una sola unidad salió");
        // La que se descartó no consumió su número.
        caja.entrarComoAdministrador();
        assertEquals("V-00000002", ventas.registrar(venta(UUID.randomUUID(), a, 1, "10000")).consecutivo());
    }

    // ------------------------------------------------------- los números seguidos

    @Test
    void ventasSimultaneasTienenConsecutivosDistintosYSeguidos() throws Exception {
        Integer a = caja.producto("A", 5, "10000");
        Integer b = caja.producto("B", 5, "10000");
        // Los dos llegan juntos al consecutivo, y la empresa todavía no tiene su fila: la primera
        // vez los dos intentan crearla.
        CyclicBarrier juntas = new CyclicBarrier(2);
        doAnswer(llamada -> {
            juntas.await(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS);
            return llamada.callRealMethod();
        }).when(consecutivos).tomarSiguiente(any(), any());

        Future<ResultadoDeVenta> uno = comoAdministrador(() -> ventas.procesar(venta(UUID.randomUUID(), a, 1, "10000")));
        Future<ResultadoDeVenta> otro = comoVendedor(() -> ventas.procesar(venta(UUID.randomUUID(), b, 1, "10000")));

        List<String> numeros = new ArrayList<>(List.of(resultado(uno).venta().consecutivo(),
                resultado(otro).venta().consecutivo()));
        numeros.sort(String::compareTo);

        assertEquals(List.of("V-00000001", "V-00000002"), numeros, "sin huecos ni repetidos");
        assertEquals(2, caja.cuantas("ventas"));
        assertEquals(3L, jdbc.sql("SELECT siguiente FROM consecutivos WHERE empresa_id = :e AND tipo = 'VENTA'")
                .param("e", caja.empresa.empresaId).query(Long.class).single());
        assertEquals(1, caja.cuantas("consecutivos"), "una sola fila, aunque los dos intentaron crearla");
    }

    // ------------------------------------------------------- la transacción y su aislamiento

    @Test
    void laTransaccionDeLaVentaLeeLoUltimoConfirmado() throws Exception {
        // Con REPEATABLE READ, la segunda petición de un reintento en paralelo tomaría su instantánea
        // al comprobar el turno, antes de que la primera confirmara, y no vería su venta al mirar la
        // clave: insertaría y chocaría. Con READ COMMITTED la ve y devuelve la ya registrada.
        Integer producto = caja.producto("A", 5, "10000");
        AtomicReference<String> aislamiento = new AtomicReference<>();
        doAnswer(llamada -> {
            aislamiento.set(jdbc.sql("SELECT @@transaction_isolation").query(String.class).single());
            return llamada.callRealMethod();
        }).when(repositorioVentas).buscarPorClave(any(), any());
        caja.entrarComoAdministrador();

        ventas.registrar(venta(UUID.randomUUID(), producto, 1, "10000"));

        assertEquals("READ-COMMITTED", aislamiento.get());
    }

    // ------------------------------------------------------- el cierre del mismo turno

    @Test
    void unaVentaQueLlegaCuandoElCierreTieneElTurnoEsperaYRecibeSinTurnoYNoUnInterbloqueo() throws Exception {
        Integer producto = caja.producto("A", 5, "10000");
        Future<ResultadoDeVenta> venta;
        Future<Integer> cierre;
        CountDownLatch cierreTieneElTurno = new CountDownLatch(1);
        CountDownLatch seguirConElCierre = new CountDownLatch(1);
        cierre = hilos.submit(() -> new TransactionTemplate(transacciones).execute(estado -> {
            tomarElTurnoComoElCierre(turnoDelAdministrador);
            cierreTieneElTurno.countDown();
            esperar(seguirConElCierre);
            return cerrarComoElCierre(turnoDelAdministrador);
        }));
        assertTrue(cierreTieneElTurno.await(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS));

        venta = comoAdministrador(() -> ventas.procesar(venta(UUID.randomUUID(), producto, 1, "10000")));
        esperarAQueEsperen("cierres_caja", 1, venta, cierre);
        seguirConElCierre.countDown();

        assertEquals(1, resultado(cierre), "el cierre debe terminar: no espera a la venta");
        assertInstanceOf(SinTurnoAbiertoException.class, fallo(venta),
                "la venta que llega durante el cierre recibe el 409 de sin turno, nunca un 500");
        assertEquals(0, caja.cuantas("ventas"));
        assertEquals(5, caja.existencia(producto));
    }

    @Test
    void unaVentaConElTurnoYLaClaveForaneaEnCursoNoSeInterbloqueaConElCierre() throws Exception {
        // La venta inserta (la clave foránea compuesta toma un bloqueo compartido sobre la entrada de
        // uq_cierres_empresa) y se queda esperando al pago, porque la prueba sostiene el medio de pago.
        // Entonces llega el cierre: toma el turno por su clave primaria y espera. Al soltar el medio,
        // la venta termina, confirma, y solo entonces sigue el cierre. Sin interbloqueo.
        Integer producto = caja.producto("A", 5, "10000");
        Future<ResultadoDeVenta> venta;
        Future<Integer> cierre;
        try (Bloqueo medio = new Bloqueo(() -> jdbc.sql("""
                SELECT nombre FROM medios_pago WHERE empresa_id = :e AND medio_id = :m FOR UPDATE
                """).param("e", caja.empresa.empresaId).param("m", caja.efectivoId).query(String.class).single())) {
            venta = comoAdministrador(() -> ventas.procesar(venta(UUID.randomUUID(), producto, 1, "10000")));
            esperarAQueEsperen("medios_pago", 1, venta);
            assertEquals(0, caja.cuantas("ventas"), "la venta todavía no confirmó");

            cierre = hilos.submit(() -> new TransactionTemplate(transacciones).execute(estado -> {
                tomarElTurnoComoElCierre(turnoDelAdministrador);
                return cerrarComoElCierre(turnoDelAdministrador);
            }));
            esperarAQueEsperen("cierres_caja", 1, venta, cierre);
        }

        ResultadoDeVenta registrada = resultado(venta);
        assertEquals(1, resultado(cierre), "el cierre sigue cuando la venta confirma");
        assertEquals("V-00000001", registrada.venta().consecutivo(), "la venta se registró");
        assertEquals(1, caja.cuantas("pagos_venta"));
    }

    @Test
    void elPagoQueSoloTomaLaClaveForaneaDelTurnoNoSeInterbloqueaConElCierre() throws Exception {
        // La misma consideración vale para pagos_venta.cierre_id (cobros de otro momento): la
        // inserción toma el turno compartido y la entrada de uq_cierres_empresa; el cierre espera.
        Integer producto = caja.producto("A", 5, "10000");
        caja.entrarComoAdministrador();
        Long venta = ventas.registrar(venta(UUID.randomUUID(), producto, 1, "10000")).id();
        CountDownLatch pagoInsertado = new CountDownLatch(1);
        CountDownLatch soltarAlPago = new CountDownLatch(1);
        Future<Long> pago = comoAdministrador(() -> new TransactionTemplate(transacciones).execute(estado -> {
            Long turno = turnos.exigirTurnoAbierto();
            jdbc.sql("""
                    INSERT INTO pagos_venta (empresa_id, venta_id, medio_id, monto, cierre_id, registrado_por, registrado_en)
                    VALUES (:e, :venta, :medio, 1000, :turno, :usuario, '2026-11-12 09:00:00')
                    """).param("e", caja.empresa.empresaId).param("venta", venta).param("medio", caja.efectivoId)
                    .param("turno", turno).param("usuario", caja.administradorId).update();
            pagoInsertado.countDown();
            esperar(soltarAlPago);
            return turno;
        }));
        assertTrue(pagoInsertado.await(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS));

        Future<Integer> cierre = hilos.submit(() -> new TransactionTemplate(transacciones).execute(estado -> {
            tomarElTurnoComoElCierre(turnoDelAdministrador);
            return cerrarComoElCierre(turnoDelAdministrador);
        }));
        esperarAQueEsperen("cierres_caja", 1, pago, cierre);
        soltarAlPago.countDown();

        assertEquals(turnoDelAdministrador, resultado(pago));
        assertEquals(1, resultado(cierre));
        assertEquals(2, caja.cuantas("pagos_venta"));
    }

    // ------------------------------------------------------- cada pago ve su turno

    @Test
    void lasVentasDeDosCajerosALaVezVanCadaUnaASuTurno() throws Exception {
        Integer a = caja.producto("A", 5, "10000");
        Integer b = caja.producto("B", 5, "10000");

        Future<ResultadoDeVenta> delAdministrador = comoAdministrador(() -> ventas.procesar(venta(UUID.randomUUID(), a, 1, "10000")));
        Future<ResultadoDeVenta> delVendedor = comoVendedor(() -> ventas.procesar(venta(UUID.randomUUID(), b, 1, "10000")));

        Long ventaDelAdministrador = resultado(delAdministrador).venta().id();
        Long ventaDelVendedor = resultado(delVendedor).venta().id();
        Map<Long, Long> turnoDe = new java.util.HashMap<>();
        jdbc.sql("SELECT venta_id, cierre_id FROM ventas WHERE empresa_id = :e").param("e", caja.empresa.empresaId)
                .query((rs, n) -> {
                    turnoDe.put(rs.getLong(1), rs.getLong(2));
                    return null;
                }).list();
        assertEquals(turnoDelAdministrador, turnoDe.get(ventaDelAdministrador));
        assertEquals(turnoDelVendedor, turnoDe.get(ventaDelVendedor));

    }
}

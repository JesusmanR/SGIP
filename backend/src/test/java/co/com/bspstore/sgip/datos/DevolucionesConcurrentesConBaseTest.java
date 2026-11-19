package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.CobroRegistrado;
import co.com.bspstore.sgip.api.dto.DevolucionRegistrada;
import co.com.bspstore.sgip.api.dto.Domicilio;
import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.api.dto.Reembolso;
import co.com.bspstore.sgip.api.dto.SolicitudAutorizacion;
import co.com.bspstore.sgip.api.dto.SolicitudDevolucion;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.api.dto.UnidadDevuelta;
import co.com.bspstore.sgip.api.dto.VentaRegistrada;
import co.com.bspstore.sgip.logica.EstadoNoAdmiteLaOperacionException;
import co.com.bspstore.sgip.logica.ServicioDevoluciones;
import co.com.bspstore.sgip.logica.ServicioDomicilios;
import co.com.bspstore.sgip.logica.ServicioVentas;
import co.com.bspstore.sgip.logica.VentaInvalidaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Dos hilos sobre la misma venta, el mismo saldo o el mismo producto (RF-68). Cada prueba fuerza el
 * cruce: sostiene desde la prueba el bloqueo que las operaciones van a pedir, deja que las dos lleguen a
 * esperarlo ({@code performance_schema} dice que de verdad esperan) y las suelta juntas. Una carrera que
 * no se fuerza solo falla algunas veces.
 *
 * El orden de bloqueos es el global de la caja (ADR-0030): turno, venta, productos, saldo, consecutivo,
 * kárdex. Que ninguna pareja de operaciones se interbloquee es lo que estas pruebas comprueban.
 *
 * Crea su empresa y la borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class DevolucionesConcurrentesConBaseTest {

    private static final long ESPERA_MAXIMA_EN_SEGUNDOS = 30;
    private static final Domicilio CONTRA_ENTREGA = new Domicilio("Calle 1 # 2-3", "Cali", "3001112233", "Ana Pérez", true);

    @Autowired ServicioVentas ventas;
    @Autowired ServicioDevoluciones devoluciones;
    @Autowired ServicioDomicilios domicilios;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioClientes clientes;
    @Autowired PlatformTransactionManager transacciones;
    @Autowired JdbcClient jdbc;

    private CajaDePrueba caja;
    private ExecutorService hilos;

    @BeforeEach
    void prepararDatosPropios() {
        caja = CajaDePrueba.crear("Devoluciones concurrentes", jdbc, plataforma, categorias, proveedores, clientes);
        caja.abrirTurno(caja.administradorId);
        caja.abrirTurno(caja.vendedorId);
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

    private PagoSolicitado efectivo(String monto) {
        return new PagoSolicitado(caja.efectivoId, new BigDecimal(monto), null, null);
    }

    private VentaRegistrada vender(Integer producto, int cantidad, String monto) {
        return ventas.registrar(new SolicitudVenta(UUID.randomUUID(), caja.clienteId,
                List.of(new LineaSolicitada(producto, null, cantidad, null, null)), null, List.of(efectivo(monto)),
                null, null, null));
    }

    private VentaRegistrada contraEntrega(Integer producto, int cantidad) {
        return ventas.registrar(new SolicitudVenta(UUID.randomUUID(), caja.clienteId,
                List.of(new LineaSolicitada(producto, null, cantidad, null, null)), null, List.of(), null, null,
                CONTRA_ENTREGA));
    }

    private SolicitudDevolucion delAdministrador(Long venta, List<UnidadDevuelta> unidades, Reembolso... reembolsos) {
        return new SolicitudDevolucion(venta, unidades, "Motivo", List.of(reembolsos), null);
    }

    /** Del vendedor: necesita el PIN del administrador. */
    private SolicitudDevolucion delVendedor(Long venta, List<UnidadDevuelta> unidades, Reembolso... reembolsos) {
        return new SolicitudDevolucion(venta, unidades, "Motivo", List.of(reembolsos),
                new SolicitudAutorizacion(caja.administradorId, CajaDePrueba.PIN));
    }

    private Reembolso enEfectivo(String monto) {
        return new Reembolso(caja.efectivoId, new BigDecimal(monto), null);
    }

    private Reembolso enSaldo(String monto) {
        return new Reembolso(caja.saldoAFavorId, new BigDecimal(monto), null);
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

    private Bloqueo bloqueoDeLaVenta(Long venta) {
        return new Bloqueo(() -> jdbc.sql("""
                SELECT venta_id FROM ventas FORCE INDEX (PRIMARY) WHERE venta_id = :v AND empresa_id = :e FOR UPDATE
                """).param("v", venta).param("e", caja.empresa.empresaId).query(Long.class).single());
    }

    private static <T> T resultado(Future<T> f) throws Exception {
        return f.get(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS);
    }

    /** Lo que quedó de dos peticiones: cuántas salieron bien y con qué falló la otra. */
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

    private BigDecimal valorDevuelto() {
        return jdbc.sql("SELECT COALESCE(SUM(valor), 0) FROM devolucion_items WHERE empresa_id = :e")
                .param("e", caja.empresa.empresaId).query(BigDecimal.class).single();
    }

    private int unidadesDevueltas() {
        return jdbc.sql("SELECT COALESCE(SUM(cantidad), 0) FROM devolucion_items WHERE empresa_id = :e")
                .param("e", caja.empresa.empresaId).query(Integer.class).single();
    }

    // ------------------------------------------------------- dos devoluciones de la misma venta

    @Test
    void dosDevolucionesSimultaneasNoSuperanLoVendido() throws Exception {
        Integer producto = caja.producto("A", 10, "10000");
        VentaRegistrada v = vender(producto, 3, "30000");
        Future<DevolucionRegistrada> delAdmin;
        Future<DevolucionRegistrada> delVend;
        // Las dos piden devolver 2 de las 3 unidades: las dos juntas serían 4. La fila de la venta se
        // sostiene desde fuera; el que gane devuelve, y el segundo tiene que ver lo que el primero ya devolvió.
        try (Bloqueo bloqueo = bloqueoDeLaVenta(v.id())) {
            delAdmin = comoAdministrador(() -> devoluciones.registrar(
                    delAdministrador(v.id(), List.of(new UnidadDevuelta(producto, 2)), enEfectivo("20000"))));
            delVend = comoVendedor(() -> devoluciones.registrar(
                    delVendedor(v.id(), List.of(new UnidadDevuelta(producto, 2)), enEfectivo("20000"))));
            esperarAQueEsperen("ventas", 2, delAdmin, delVend);
        }

        Throwable perdedora = laQueFallo(delAdmin, delVend, 1);

        assertInstanceOf(VentaInvalidaException.class, perdedora, String.valueOf(perdedora));
        assertEquals(2, unidadesDevueltas(), "se devolvieron 2, no 4");
        assertEquals(1, caja.cuantas("devoluciones"));
        assertEquals(9, caja.existencia(producto), "10 - 3 + 2");
        assertEquals(1, caja.movimientosDe(producto, "DEVOLUCION"));
    }

    @Test
    void dosAnulacionesSimultaneasUnaAnulaYLaOtraRecibe409() throws Exception {
        Integer producto = caja.producto("A", 10, "10000");
        VentaRegistrada v = vender(producto, 2, "20000");
        Future<DevolucionRegistrada> uno;
        Future<DevolucionRegistrada> otro;
        try (Bloqueo bloqueo = bloqueoDeLaVenta(v.id())) {
            uno = comoAdministrador(() -> devoluciones.registrar(delAdministrador(v.id(), List.of(), enEfectivo("20000"))));
            otro = comoVendedor(() -> devoluciones.registrar(delVendedor(v.id(), List.of(), enEfectivo("20000"))));
            esperarAQueEsperen("ventas", 2, uno, otro);
        }

        Throwable perdedora = laQueFallo(uno, otro, 1);

        assertInstanceOf(EstadoNoAdmiteLaOperacionException.class, perdedora, String.valueOf(perdedora));
        assertEquals(1, caja.cuantas("devoluciones"));
        assertEquals(10, caja.existencia(producto), "la existencia volvió una sola vez");
        assertEquals("ANULADA", jdbc.sql("SELECT estado FROM ventas WHERE venta_id = :v")
                .param("v", v.id()).query(String.class).single());
    }

    // ------------------------------------------------------- una devolución y otra operación

    @Test
    void unaDevolucionYUnCobroDeLaMismaVentaSeSerializanSinInterbloquearse() throws Exception {
        Integer producto = caja.producto("A", 10, "3000");
        VentaRegistrada v = contraEntrega(producto, 3);
        Future<DevolucionRegistrada> devolucion;
        Future<CobroRegistrado> cobro;
        try (Bloqueo bloqueo = bloqueoDeLaVenta(v.id())) {
            devolucion = comoAdministrador(() -> devoluciones.registrar(
                    delAdministrador(v.id(), List.of(new UnidadDevuelta(producto, 1)))));
            cobro = comoVendedor(() -> domicilios.cobrar(v.id(), List.of(efectivo("9000"))));
            esperarAQueEsperen("ventas", 2, devolucion, cobro);
        }

        // Según quién gane, el otro encuentra la venta cambiada: lo que no puede pasar es un interbloqueo ni
        // un tiempo de espera agotado.
        for (Future<?> f : List.of(devolucion, cobro)) {
            try {
                resultado(f);
            } catch (ExecutionException e) {
                assertTrue(!(e.getCause() instanceof DataAccessException), String.valueOf(e.getCause()));
            }
        }
        String estadoPago = jdbc.sql("SELECT estado_pago FROM ventas WHERE venta_id = :v")
                .param("v", v.id()).query(String.class).single();
        int devoluciones = caja.cuantas("devoluciones");
        int pagos = caja.cuantas("pagos_venta");
        assertTrue((devoluciones == 1 && "PENDIENTE".equals(estadoPago) && pagos == 0)
                        || (devoluciones == 0 && "PAGADA".equals(estadoPago) && pagos == 1),
                "una gana y la otra no cambia nada: devoluciones=" + devoluciones + ", pagos=" + pagos + ", " + estadoPago);
    }

    @Test
    void unaDevolucionYUnaVentaDelMismoProductoNoSeInterbloquean() throws Exception {
        Integer producto = caja.producto("A", 10, "10000");
        VentaRegistrada anterior = vender(producto, 2, "20000");
        Future<DevolucionRegistrada> devolucion;
        Future<VentaRegistrada> venta;
        // El producto tomado desde fuera: la devolución llega a él con la venta ya bloqueada, y la venta
        // nueva llega sin haber bloqueado ninguna. Las dos esperan el mismo producto y no se esperan entre sí.
        try (Bloqueo bloqueo = new Bloqueo(() -> jdbc.sql("""
                SELECT producto_id FROM productos FORCE INDEX (PRIMARY)
                 WHERE producto_id = :p AND empresa_id = :e FOR UPDATE
                """).param("p", producto).param("e", caja.empresa.empresaId).query(Integer.class).single())) {
            devolucion = comoAdministrador(() -> devoluciones.registrar(
                    delAdministrador(anterior.id(), List.of(), enEfectivo("20000"))));
            venta = comoVendedor(() -> vender(producto, 1, "10000"));
            esperarAQueEsperen("productos", 2, devolucion, venta);
        }

        resultado(devolucion);
        resultado(venta);

        assertEquals(10 - 2 + 2 - 1, caja.existencia(producto));
    }

    @Test
    void dosAbonosAlSaldoDelMismoClienteSeSumanSinPerderse() throws Exception {
        // Productos distintos: si compartieran uno, la segunda esperaría al primero en el producto y no en el saldo.
        Integer producto = caja.producto("A", 20, "10000");
        Integer otroProducto = caja.producto("B", 20, "10000");
        VentaRegistrada semilla = vender(producto, 1, "10000");
        devoluciones.registrar(delAdministrador(semilla.id(), List.of(), enSaldo("10000")));
        VentaRegistrada una = vender(producto, 1, "10000");
        VentaRegistrada otra = vender(otroProducto, 1, "10000");
        Future<DevolucionRegistrada> primera;
        Future<DevolucionRegistrada> segunda;
        // La fila del saldo del cliente, tomada desde fuera.
        try (Bloqueo bloqueo = new Bloqueo(() -> jdbc.sql("""
                SELECT saldo FROM saldos_cliente WHERE empresa_id = :e AND cliente_id = :c FOR UPDATE
                """).param("e", caja.empresa.empresaId).param("c", caja.clienteId).query(BigDecimal.class).single())) {
            primera = comoAdministrador(() -> devoluciones.registrar(
                    delAdministrador(una.id(), List.of(), enSaldo("10000"))));
            segunda = comoVendedor(() -> devoluciones.registrar(
                    delVendedor(otra.id(), List.of(), enSaldo("10000"))));
            esperarAQueEsperen("saldos_cliente", 2, primera, segunda);
        }

        resultado(primera);
        resultado(segunda);

        assertEquals(0, new BigDecimal("30000").compareTo(jdbc.sql("""
                SELECT saldo FROM saldos_cliente WHERE empresa_id = :e AND cliente_id = :c
                """).param("e", caja.empresa.empresaId).param("c", caja.clienteId).query(BigDecimal.class).single()));
        assertEquals(3, caja.movimientosDeSaldo("ABONO"));
    }
}

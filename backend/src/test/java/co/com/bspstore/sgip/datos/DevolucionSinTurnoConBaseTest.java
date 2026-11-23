package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.DevolucionRegistrada;
import co.com.bspstore.sgip.api.dto.Domicilio;
import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.api.dto.Reembolso;
import co.com.bspstore.sgip.api.dto.ResumenDeTurno;
import co.com.bspstore.sgip.api.dto.SolicitudAutorizacion;
import co.com.bspstore.sgip.api.dto.SolicitudCierre;
import co.com.bspstore.sgip.api.dto.SolicitudDevolucion;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.api.dto.VentaRegistrada;
import co.com.bspstore.sgip.logica.ResultadoDeDevolucion;
import co.com.bspstore.sgip.logica.ServicioDevoluciones;
import co.com.bspstore.sgip.logica.ServicioTurnos;
import co.com.bspstore.sgip.logica.ServicioVentas;
import co.com.bspstore.sgip.logica.SinTurnoAbiertoException;
import co.com.bspstore.sgip.logica.TurnoRequeridoParaEfectivoException;
import co.com.bspstore.sgip.logica.VentaInvalidaException;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * La devolución del administrador sin turno abierto (mejoras posteriores a la muestra, M4) contra el esquema
 * real: queda con {@code cierre_id} nulo si ningún reembolso es en efectivo, el efectivo exige turno y no deja
 * nada a medias, el vendedor sigue necesitando turno, el reintento devuelve lo mismo y un turno que se abre
 * mientras tanto no se queda con la devolución.
 *
 * Crea su empresa y la borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class DevolucionSinTurnoConBaseTest {

    private static final long ESPERA_MAXIMA_EN_SEGUNDOS = 30;
    private static final Domicilio CONTRA_ENTREGA = new Domicilio("Calle 1 # 2-3", "Cali", "3001112233", "Ana Pérez", true);

    @Autowired ServicioVentas ventas;
    @Autowired ServicioDevoluciones devoluciones;
    @Autowired ServicioTurnos turnos;
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
        caja = CajaDePrueba.crear("Devolucion sin turno", jdbc, plataforma, categorias, proveedores, clientes);
        turnoDelAdministrador = caja.abrirTurno(caja.administradorId);
        turnoDelVendedor = caja.abrirTurno(caja.vendedorId);
        caja.entrarComoAdministrador();
        hilos = Executors.newFixedThreadPool(3);
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

    private PagoSolicitado pagoEnEfectivo(String monto) {
        return new PagoSolicitado(caja.efectivoId, new BigDecimal(monto), null, null);
    }

    private Reembolso enEfectivo(String monto) {
        return new Reembolso(caja.efectivoId, new BigDecimal(monto), null);
    }

    private Reembolso enTarjeta(String monto) {
        return new Reembolso(caja.tarjetaId, new BigDecimal(monto), "DATAFONO-1");
    }

    private Reembolso enSaldo(String monto) {
        return new Reembolso(caja.saldoAFavorId, new BigDecimal(monto), null);
    }

    private VentaRegistrada vender(Integer producto, int cantidad, String efectivo) {
        return ventas.registrar(new SolicitudVenta(UUID.randomUUID(), caja.clienteId,
                List.of(new LineaSolicitada(producto, null, cantidad, null, null)), null, List.of(pagoEnEfectivo(efectivo)),
                null, null, null));
    }

    private VentaRegistrada contraEntrega(Integer producto, int cantidad) {
        return ventas.registrar(new SolicitudVenta(UUID.randomUUID(), caja.clienteId,
                List.of(new LineaSolicitada(producto, null, cantidad, null, null)), null, List.of(), null, null,
                CONTRA_ENTREGA));
    }

    private static SolicitudDevolucion anulacion(Long venta, Reembolso... reembolsos) {
        return new SolicitudDevolucion(UUID.randomUUID(), venta, List.of(), "El cliente se arrepintió",
                List.of(reembolsos), null);
    }

    private Map<String, Object> devolucionGuardada(Long id) {
        return jdbc.sql("SELECT * FROM devoluciones WHERE empresa_id = :e AND devolucion_id = :id")
                .param("e", caja.empresa.empresaId).param("id", id).query().singleRow();
    }

    private BigDecimal saldoGuardado(Integer cliente) {
        return jdbc.sql("SELECT COALESCE(SUM(saldo), 0) FROM saldos_cliente WHERE empresa_id = :e AND cliente_id = :c")
                .param("e", caja.empresa.empresaId).param("c", cliente).query(BigDecimal.class).single();
    }

    private static void igual(String esperado, BigDecimal obtenido) {
        assertEquals(0, new BigDecimal(esperado).compareTo(obtenido), "esperaba " + esperado + " y fue " + obtenido);
    }

    private void nadaDevuelto(Integer producto, int existencia) {
        assertEquals(0, caja.cuantas("devoluciones"), "ninguna devolución");
        assertEquals(0, caja.cuantas("devolucion_items"), "ningún ítem");
        assertEquals(0, caja.cuantas("reembolsos"), "ningún reembolso");
        assertEquals(0, caja.movimientosDeSaldo("ABONO"), "ningún abono al saldo");
        assertEquals(0, caja.movimientosDeTipo("DEVOLUCION"), "ningún movimiento de kárdex");
        assertEquals(existencia, caja.existencia(producto), "la existencia no se movió");
    }

    // ------------------------------------------------------- lo que se registra

    @Test
    void devolucionSinTurnoQuedaConCierreNulo() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada v = vender(producto, 1, "10000");
        caja.cerrarTurno(turnoDelAdministrador);

        DevolucionRegistrada r = devoluciones.registrar(anulacion(v.id(), enTarjeta("6000"), enSaldo("4000")));

        assertNull(devolucionGuardada(r.id()).get("cierre_id"), "ningún cajón de ningún turno");
        assertEquals("ANULADA", r.estadoVenta());
        assertEquals(2, caja.cuantas("reembolsos"));
        igual("4000", saldoGuardado(caja.clienteId));
        assertEquals(5, caja.existencia(producto), "la existencia vuelve");
        assertEquals(1, caja.movimientosDe(producto, "DEVOLUCION"));
    }

    @Test
    void tarjetaMasEfectivoSinTurnoNoDejaNadaAMedias() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada v = vender(producto, 1, "10000");
        caja.cerrarTurno(turnoDelAdministrador);

        assertThrows(TurnoRequeridoParaEfectivoException.class,
                () -> devoluciones.registrar(anulacion(v.id(), enTarjeta("3000"), enSaldo("3000"), enEfectivo("4000"))));

        nadaDevuelto(producto, 4);
        igual("0", saldoGuardado(caja.clienteId));
    }

    @Test
    void elVendedorSinTurnoNoDevuelveNiConElPinDelAdministradorNiConTarjeta() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada v = vender(producto, 1, "10000");
        caja.cerrarTurno(turnoDelVendedor);
        caja.entrarComoVendedor();

        assertThrows(SinTurnoAbiertoException.class, () -> devoluciones.registrar(new SolicitudDevolucion(UUID.randomUUID(),
                v.id(), List.of(), "Falló", List.of(enTarjeta("10000")),
                new SolicitudAutorizacion(caja.administradorId, CajaDePrueba.PIN))));

        nadaDevuelto(producto, 4);
    }

    @Test
    void ventaContraEntregaPendienteRespetaLasReglasActuales() {
        Integer producto = caja.producto("A", 5, "9000");
        VentaRegistrada v = contraEntrega(producto, 1);
        caja.cerrarTurno(turnoDelAdministrador);

        // Una venta por cobrar no pagó nada: reembolsar sigue siendo un 400 y no un 409 de turno.
        assertThrows(VentaInvalidaException.class, () -> devoluciones.registrar(anulacion(v.id(), enTarjeta("9000"))));
        assertEquals(0, caja.cuantas("devoluciones"));

        // Y anularla no mueve dinero: se registra sin turno, como siempre.
        DevolucionRegistrada r = devoluciones.registrar(anulacion(v.id()));

        assertEquals("ANULADA", r.estadoVenta());
        assertNull(devolucionGuardada(r.id()).get("cierre_id"));
        assertEquals(5, caja.existencia(producto));
    }

    @Test
    void reintentoConLaMismaClaveSinTurnoDevuelve200() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada v = vender(producto, 1, "10000");
        caja.cerrarTurno(turnoDelAdministrador);
        SolicitudDevolucion s = anulacion(v.id(), enTarjeta("10000"));

        ResultadoDeDevolucion primera = devoluciones.procesar(s);
        ResultadoDeDevolucion repetida = devoluciones.procesar(s);

        assertFalse(primera.yaExistia());
        assertTrue(repetida.yaExistia(), "el reintento devuelve la misma devolución, no otra");
        assertEquals(primera.devolucion().id(), repetida.devolucion().id());
        assertEquals(1, caja.cuantas("devoluciones"));
        assertEquals(1, caja.cuantas("reembolsos"));
        assertEquals(5, caja.existencia(producto), "se devolvió una sola vez");
    }

    // ------------------------------------------------------- el cuadre de la caja

    @Test
    void cierreDeUnTurnoNoCuentaLaDevolucionSinTurno() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada v = vender(producto, 1, "10000");
        ResumenDeTurno primero = turnos.cerrar(new SolicitudCierre(new BigDecimal("10000"), null));
        igual("10000", primero.efectivoEsperado());

        DevolucionRegistrada r = devoluciones.registrar(anulacion(v.id(), enTarjeta("10000")));

        assertNull(devolucionGuardada(r.id()).get("cierre_id"));
        Long segundoTurno = caja.abrirTurno(caja.administradorId, "5000");
        ResumenDeTurno abierto = turnos.resumenActual();
        assertEquals(segundoTurno, abierto.id());
        igual("5000", abierto.efectivoEsperado());
        ResumenDeTurno cerrado = turnos.cerrar(new SolicitudCierre(new BigDecimal("5000"), null));
        igual("5000", cerrado.efectivoEsperado());
        igual("0", cerrado.diferencia());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM devoluciones WHERE empresa_id = :e AND cierre_id IS NOT NULL")
                .param("e", caja.empresa.empresaId).query(Integer.class).single(), "ningún turno la tiene");
    }

    // ------------------------------------------------------- la carrera con la apertura de un turno

    @Test
    void aperturaDeTurnoConcurrenteNoAsociaLaDevolucion() throws Exception {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada v = vender(producto, 1, "10000");
        caja.cerrarTurno(turnoDelAdministrador);
        Future<DevolucionRegistrada> devolucion;
        Long turnoNuevo;
        // La fila de la venta se sostiene desde fuera: la devolución ya miró el turno (no tiene) y espera la
        // venta. Mientras espera, el administrador abre un turno. Al soltar, la devolución sigue sin turno.
        try (Bloqueo bloqueo = bloqueoDeLaVenta(v.id())) {
            devolucion = comoAdministrador(() -> devoluciones.registrar(anulacion(v.id(), enTarjeta("10000"))));
            esperarAQueEsperen("ventas", 1, devolucion);
            turnoNuevo = caja.abrirTurno(caja.administradorId);
        }

        DevolucionRegistrada r = devolucion.get(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS);

        assertNull(devolucionGuardada(r.id()).get("cierre_id"), "la devolución no se quedó con el turno recién abierto");
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM devoluciones WHERE empresa_id = :e AND cierre_id = :t")
                .param("e", caja.empresa.empresaId).param("t", turnoNuevo).query(Integer.class).single());
    }

    // ------------------------------------------------------- dos hilos, a la manera de DevolucionesConcurrentesConBaseTest

    private <T> Future<T> comoAdministrador(Callable<T> trabajo) {
        SecurityContext contexto = caja.contextoDe(caja.administradorId, "ADMINISTRADOR");
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
}

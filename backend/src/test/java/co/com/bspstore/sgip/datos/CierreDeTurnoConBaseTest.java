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
import co.com.bspstore.sgip.api.dto.UnidadDevuelta;
import co.com.bspstore.sgip.api.dto.VentaRegistrada;
import co.com.bspstore.sgip.logica.DatoDeTurnoInvalidoException;
import co.com.bspstore.sgip.logica.RecursoNoEncontradoException;
import co.com.bspstore.sgip.logica.ServicioDevoluciones;
import co.com.bspstore.sgip.logica.ServicioDomicilios;
import co.com.bspstore.sgip.logica.ServicioTurnos;
import co.com.bspstore.sgip.logica.ServicioVentas;
import co.com.bspstore.sgip.logica.SinTurnoAbiertoException;
import co.com.bspstore.sgip.logica.TurnoRequeridoParaEfectivoException;
import co.com.bspstore.sgip.modelo.TipoMedio;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Cerrar el turno de caja con el efectivo contado (RF-45) contra el esquema real: cuánto efectivo se
 * espera, qué pagos y qué reembolsos cuenta cada turno, la diferencia que exige explicación, lo que el
 * turno cerrado ya no admite y la carrera de una venta, un cobro o una devolución contra el cierre.
 *
 * El cierre toma el turno {@code FOR UPDATE} y las demás operaciones lo leen {@code FOR SHARE}. Las
 * pruebas de concurrencia fuerzan el cruce como las de las devoluciones: sostienen desde la prueba un
 * bloqueo (un producto, una venta, el turno), dejan que las dos operaciones lleguen a esperar
 * ({@code performance_schema} dice que de verdad esperan) y lo sueltan. Una carrera que no se fuerza
 * solo falla algunas veces.
 *
 * Crea su empresa y la borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class CierreDeTurnoConBaseTest {

    private static final long ESPERA_MAXIMA_EN_SEGUNDOS = 30;
    private static final Domicilio CONTRA_ENTREGA = new Domicilio("Calle 1 # 2-3", "Cali", "3001112233", "Ana Pérez", true);

    @Autowired ServicioTurnos turnos;
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
        caja = CajaDePrueba.crear("Cierre de turno", jdbc, plataforma, categorias, proveedores, clientes);
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

    private PagoSolicitado tarjeta(String monto) {
        return new PagoSolicitado(caja.tarjetaId, new BigDecimal(monto), null, "AUT-1");
    }

    private PagoSolicitado transferencia(String monto) {
        return new PagoSolicitado(caja.transferenciaId, new BigDecimal(monto), null, "TRF-1");
    }

    private PagoSolicitado conSaldo(String monto) {
        return new PagoSolicitado(caja.saldoAFavorId, new BigDecimal(monto), null, null);
    }

    private static LineaSolicitada linea(Integer producto, int cantidad) {
        return new LineaSolicitada(producto, null, cantidad, null, null);
    }

    private VentaRegistrada vender(List<LineaSolicitada> lineas, PagoSolicitado... pagos) {
        return ventas.registrar(new SolicitudVenta(UUID.randomUUID(), caja.clienteId, lineas, null, List.of(pagos),
                null, null, null));
    }

    private VentaRegistrada vender(Integer producto, int cantidad, String efectivo) {
        return vender(List.of(linea(producto, cantidad)), efectivo(efectivo));
    }

    private VentaRegistrada contraEntrega(Integer producto, int cantidad) {
        return ventas.registrar(new SolicitudVenta(UUID.randomUUID(), caja.clienteId, List.of(linea(producto, cantidad)),
                null, List.of(), null, null, CONTRA_ENTREGA));
    }

    private DevolucionRegistrada devolver(Long venta, List<UnidadDevuelta> unidades, Reembolso... reembolsos) {
        return devoluciones.registrar(new SolicitudDevolucion(UUID.randomUUID(), venta, unidades, "Motivo",
                List.of(reembolsos), null));
    }

    private DevolucionRegistrada devolverComoVendedor(Long venta, List<UnidadDevuelta> unidades, Reembolso... reembolsos) {
        return devoluciones.registrar(new SolicitudDevolucion(UUID.randomUUID(), venta, unidades, "Motivo",
                List.of(reembolsos), new SolicitudAutorizacion(caja.administradorId, CajaDePrueba.PIN)));
    }

    private Reembolso reembolsoEnEfectivo(String monto) {
        return new Reembolso(caja.efectivoId, new BigDecimal(monto), null);
    }

    private Reembolso reembolsoEnSaldo(String monto) {
        return new Reembolso(caja.saldoAFavorId, new BigDecimal(monto), null);
    }

    private static SolicitudCierre contado(String monto) {
        return new SolicitudCierre(new BigDecimal(monto), null);
    }

    private static void igual(String esperado, BigDecimal real) {
        assertNotNull(real, "se esperaba " + esperado + " y fue nulo");
        assertEquals(0, new BigDecimal(esperado).compareTo(real), "se esperaba " + esperado + " y fue " + real);
    }

    /** Lo que quedó escrito en la fila del turno. */
    private Map<String, Object> fila(Long turno) {
        return jdbc.sql("""
                SELECT cierre, esperado, contado, diferencia, observacion FROM cierres_caja
                 WHERE empresa_id = :e AND cierre_id = :t
                """).param("e", caja.empresa.empresaId).param("t", turno).query().singleRow();
    }

    private void entrarComoCajero(Integer usuario, String rol) {
        SecurityContextHolder.setContext(caja.contextoDe(usuario, rol));
    }

    // ------------------------------------------------------- el efectivo esperado

    @Test
    void esperadoEsBaseMasEfectivoMenosReembolsos() {
        Long turno = caja.abrirTurno(caja.administradorId, "100000");
        Integer a = caja.producto("A", 20, "10000");
        Integer c = caja.producto("C", 20, "5000");
        // Ventas en efectivo de 50 000 (cuatro A y dos C) y de 20 000, y un reembolso en efectivo de 5 000.
        VentaRegistrada primera = vender(List.of(linea(a, 4), linea(c, 2)), efectivo("50000"));
        vender(a, 2, "20000");
        devolver(primera.id(), List.of(new UnidadDevuelta(c, 1)), reembolsoEnEfectivo("5000"));

        ResumenDeTurno antes = turnos.resumenActual();
        ResumenDeTurno cerrado = turnos.cerrar(contado("165000"));

        igual("165000", antes.efectivoEsperado());
        assertNull(antes.efectivoContado());
        assertEquals(turno, cerrado.id());
        igual("165000", cerrado.efectivoEsperado());
        igual("165000", cerrado.efectivoContado());
        igual("0", cerrado.diferencia());
        igual("100000", cerrado.baseEfectivo());
        igual("70000", cerrado.porMedio().get(TipoMedio.EFECTIVO));
        assertNotNull(cerrado.cierre());
        Map<String, Object> guardado = fila(turno);
        assertNotNull(guardado.get("cierre"));
        igual("165000", (BigDecimal) guardado.get("esperado"));
        igual("165000", (BigDecimal) guardado.get("contado"));
        igual("0", (BigDecimal) guardado.get("diferencia"));
    }

    @Test
    void cobrosYDevolucionesDeOtroTurnoNoCuentan() {
        Long turnoAdmin = caja.abrirTurno(caja.administradorId, "10000");
        Long turnoVendedor = caja.abrirTurno(caja.vendedorId, "5000");
        Integer producto = caja.producto("A", 50, "10000");

        // El administrador vende 3 en su turno (30 000 en efectivo).
        VentaRegistrada delAdministrador = vender(producto, 3, "30000");
        // El vendedor deja una venta contra entrega sin cobrar y vende 1 en su turno (10 000 en efectivo).
        entrarComoCajero(caja.vendedorId, "VENDEDOR");
        VentaRegistrada porCobrar = contraEntrega(producto, 1);
        vender(producto, 1, "10000");
        // El vendedor devuelve 1 de las 3 del administrador: los 10 000 salen de SU cajón, no del del administrador.
        devolverComoVendedor(delAdministrador.id(), List.of(new UnidadDevuelta(producto, 1)), reembolsoEnEfectivo("10000"));
        // El administrador cobra en SU turno la entrega que dejó el vendedor: entra a SU cajón.
        entrarComoCajero(caja.administradorId, "ADMINISTRADOR");
        domicilios.cobrar(porCobrar.id(), List.of(efectivo("10000")));

        ResumenDeTurno admin = turnos.resumenActual();
        entrarComoCajero(caja.vendedorId, "VENDEDOR");
        ResumenDeTurno vendedor = turnos.resumenActual();

        assertEquals(turnoAdmin, admin.id());
        // 10 000 de base + 30 000 de la venta + 10 000 del cobro; la devolución del vendedor no lo toca.
        igual("50000", admin.efectivoEsperado());
        assertEquals(turnoVendedor, vendedor.id());
        // 5 000 de base + 10 000 de su venta - 10 000 que devolvió; el cobro del administrador no lo toca.
        igual("5000", vendedor.efectivoEsperado());
        igual("40000", admin.porMedio().get(TipoMedio.EFECTIVO));
        igual("10000", vendedor.porMedio().get(TipoMedio.EFECTIVO));
    }

    @Test
    void elPagoConSaldoNoCuentaEnElEsperado() {
        caja.abrirTurno(caja.administradorId, "0");
        Integer a = caja.producto("A", 20, "10000");
        Integer c = caja.producto("C", 20, "5000");
        // Un saldo a favor de 10 000: se vende en efectivo y se devuelve entero al saldo.
        VentaRegistrada semilla = vender(a, 1, "10000");
        devolver(semilla.id(), List.of(), reembolsoEnSaldo("10000"));
        // Una venta de 15 000: 10 000 con el saldo y 5 000 en efectivo.
        vender(List.of(linea(a, 1), linea(c, 1)), conSaldo("10000"), efectivo("5000"));

        ResumenDeTurno r = turnos.resumenActual();

        // 10 000 de la semilla + 5 000 en efectivo; el saldo no pasó por el cajón ni lleva turno.
        igual("15000", r.efectivoEsperado());
        assertEquals(java.util.Set.of(TipoMedio.EFECTIVO), r.porMedio().keySet());
        igual("15000", r.porMedio().get(TipoMedio.EFECTIVO));
        assertEquals(1, jdbc.sql("""
                SELECT COUNT(*) FROM pagos_venta WHERE empresa_id = :e AND cierre_id IS NULL
                """).param("e", caja.empresa.empresaId).query(Integer.class).single(),
                "el pago con saldo se guarda sin turno");
    }

    @Test
    void elCambioEntregadoNoCuentaEnElEsperado() {
        Long turno = caja.abrirTurno(caja.administradorId, "10000");
        Integer producto = caja.producto("A", 20, "10000");
        // Paga 30 000 con un billete de 50 000: el monto guardado es el neto, los 30 000. El cambio de 20 000
        // que se entrega no cuenta, ni sumando ni restando.
        vender(List.of(linea(producto, 3)), new PagoSolicitado(caja.efectivoId, new BigDecimal("30000"),
                new BigDecimal("50000"), null));

        ResumenDeTurno r = turnos.resumenActual();
        ResumenDeTurno cerrado = turnos.cerrar(contado("40000"));

        igual("40000", r.efectivoEsperado());
        igual("30000", r.porMedio().get(TipoMedio.EFECTIVO));
        igual("40000", cerrado.efectivoEsperado());
        igual("40000", (BigDecimal) fila(turno).get("esperado"));
    }

    @Test
    void porMedioTotalizaTarjetaYTransferencia() {
        caja.abrirTurno(caja.administradorId, "20000");
        Integer producto = caja.producto("A", 50, "10000");
        vender(List.of(linea(producto, 6)), tarjeta("30000"), transferencia("20000"), efectivo("10000"));
        vender(List.of(linea(producto, 1)), tarjeta("5000"), transferencia("5000"));

        ResumenDeTurno r = turnos.cerrar(contado("30000"));

        igual("10000", r.porMedio().get(TipoMedio.EFECTIVO));
        igual("35000", r.porMedio().get(TipoMedio.TARJETA));
        igual("25000", r.porMedio().get(TipoMedio.TRANSFERENCIA));
        assertEquals(3, r.porMedio().size());
        // Solo el efectivo pasa por el cajón: la base y los 10 000 en efectivo.
        igual("30000", r.efectivoEsperado());
    }

    // ------------------------------------------------------- la diferencia

    @Test
    void diferenciaDistintaDeCeroExigeObservacion() {
        Long turno = caja.abrirTurno(caja.administradorId, "10000");
        Integer producto = caja.producto("A", 20, "10000");
        vender(producto, 1, "10000");

        // Faltan 2 000, sin explicar.
        assertThrows(DatoDeTurnoInvalidoException.class, () -> turnos.cerrar(contado("18000")));
        assertThrows(DatoDeTurnoInvalidoException.class,
                () -> turnos.cerrar(new SolicitudCierre(new BigDecimal("18000"), "   ")));
        // Sobran 500, sin explicar.
        assertThrows(DatoDeTurnoInvalidoException.class, () -> turnos.cerrar(contado("20500")));

        assertNull(fila(turno).get("cierre"), "el turno sigue abierto");
        assertTrue(turnos.actual().isPresent());

        ResumenDeTurno cerrado = turnos.cerrar(new SolicitudCierre(new BigDecimal("18000"), "Un billete roto"));

        igual("-2000", cerrado.diferencia());
        assertEquals("Un billete roto", cerrado.observacion());
        Map<String, Object> guardado = fila(turno);
        igual("20000", (BigDecimal) guardado.get("esperado"));
        igual("18000", (BigDecimal) guardado.get("contado"));
        igual("-2000", (BigDecimal) guardado.get("diferencia"));
        assertEquals("Un billete roto", guardado.get("observacion"));
    }

    @Test
    void conLaDiferenciaEnCeroLaObservacionNoSeExige() {
        Long turno = caja.abrirTurno(caja.administradorId, "10000");
        Integer producto = caja.producto("A", 20, "10000");
        vender(producto, 1, "10000");

        ResumenDeTurno cerrado = turnos.cerrar(contado("20000"));

        igual("0", cerrado.diferencia());
        assertNull(cerrado.observacion());
        assertNull(fila(turno).get("observacion"));
    }

    // ------------------------------------------------------- lo que el turno cerrado ya no admite

    @Test
    void turnoCerradoRechazaVentas() {
        caja.abrirTurno(caja.administradorId, "0");
        Integer producto = caja.producto("A", 20, "10000");
        turnos.cerrar(contado("0"));

        assertThrows(SinTurnoAbiertoException.class, () -> vender(producto, 1, "10000"));

        assertEquals(0, caja.cuantas("ventas"));
        assertEquals(0, caja.cuantas("pagos_venta"));
        assertEquals(20, caja.existencia(producto));
    }

    @Test
    void turnoCerradoRechazaCobrosYDevoluciones() {
        Long turno = caja.abrirTurno(caja.administradorId, "0");
        Integer producto = caja.producto("A", 20, "10000");
        VentaRegistrada pagada = vender(producto, 2, "20000");
        VentaRegistrada porCobrar = contraEntrega(producto, 1);
        turnos.cerrar(contado("20000"));

        assertThrows(SinTurnoAbiertoException.class, () -> domicilios.cobrar(porCobrar.id(), List.of(efectivo("10000"))));
        // El administrador sin turno no devuelve efectivo; el vendedor sin turno no devuelve nada, ni con el PIN.
        assertThrows(TurnoRequeridoParaEfectivoException.class,
                () -> devolver(pagada.id(), List.of(new UnidadDevuelta(producto, 1)), reembolsoEnEfectivo("10000")));
        caja.entrarComoVendedor();
        assertThrows(SinTurnoAbiertoException.class, () -> devolverComoVendedor(pagada.id(),
                List.of(new UnidadDevuelta(producto, 1)), reembolsoEnEfectivo("10000")));

        assertEquals(1, caja.cuantas("pagos_venta"), "ningún pago nuevo después del cierre");
        assertEquals(0, caja.cuantas("devoluciones"));
        igual("20000", (BigDecimal) fila(turno).get("esperado"));
    }

    @Test
    void cerrarDosVecesEs409() {
        Long turno = caja.abrirTurno(caja.administradorId, "10000");
        turnos.cerrar(contado("10000"));
        Object cierre = fila(turno).get("cierre");

        SinTurnoAbiertoException e = assertThrows(SinTurnoAbiertoException.class, () -> turnos.cerrar(contado("99999")));

        assertEquals("No tienes un turno abierto para cerrar.", e.getMessage());
        assertEquals(cierre, fila(turno).get("cierre"), "el segundo cierre no cambió nada");
        igual("10000", (BigDecimal) fila(turno).get("contado"));
    }

    @Test
    void sinTurnoAbiertoNoHayResumenNiCierre() {
        assertThrows(RecursoNoEncontradoException.class, () -> turnos.resumenActual());
        assertEquals("No tienes un turno abierto para cerrar.",
                assertThrows(SinTurnoAbiertoException.class, () -> turnos.cerrar(contado("0"))).getMessage());
    }

    @Test
    void despuesDeCerrarElCajeroPuedeAbrirOtroTurnoConSuPropioEsperado() {
        Long primero = caja.abrirTurno(caja.administradorId, "10000");
        Integer producto = caja.producto("A", 20, "10000");
        vender(producto, 1, "10000");
        turnos.cerrar(contado("20000"));
        Long segundo = caja.abrirTurno(caja.administradorId, "3000");

        ResumenDeTurno nuevo = turnos.resumenActual();

        assertEquals(segundo, nuevo.id());
        igual("3000", nuevo.efectivoEsperado());
        assertTrue(nuevo.porMedio().isEmpty());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM pagos_venta WHERE empresa_id = :e AND cierre_id = :t")
                .param("e", caja.empresa.empresaId).param("t", primero).query(Integer.class).single());
    }

    // ------------------------------------------------------- la lista

    @Test
    void elVendedorSoloVeSusTurnos() {
        Long delAdministrador = caja.abrirTurno(caja.administradorId, "10000");
        turnos.cerrar(contado("10000"));
        Long delVendedor = caja.abrirTurno(caja.vendedorId, "7000");
        LocalDate dia = LocalDate.of(2026, 11, 12);

        entrarComoCajero(caja.vendedorId, "VENDEDOR");
        List<ResumenDeTurno> comoVendedor = turnos.listar(dia, dia);
        entrarComoCajero(caja.administradorId, "ADMINISTRADOR");
        List<ResumenDeTurno> comoAdministrador = turnos.listar(dia, dia);

        assertEquals(List.of(delVendedor), comoVendedor.stream().map(ResumenDeTurno::id).toList());
        assertEquals(2, comoAdministrador.size());
        assertTrue(comoAdministrador.stream().map(ResumenDeTurno::id).toList().containsAll(List.of(delAdministrador, delVendedor)));
        // El turno abierto no tiene contado ni cierre, con su esperado vivo; el cerrado, lo que quedó guardado.
        ResumenDeTurno abierto = comoVendedor.get(0);
        assertNull(abierto.efectivoContado());
        assertNull(abierto.diferencia());
        assertNull(abierto.cierre());
        igual("7000", abierto.efectivoEsperado());
        ResumenDeTurno cerrado = comoAdministrador.stream().filter(t -> t.id().equals(delAdministrador)).findFirst().orElseThrow();
        igual("10000", cerrado.efectivoContado());
        igual("0", cerrado.diferencia());
    }

    @Test
    void laListaRespetaLasFechasDeApertura() {
        caja.abrirTurno(caja.administradorId, "10000");

        assertEquals(1, turnos.listar(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 12)).size());
        assertEquals(1, turnos.listar(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)).size());
        assertTrue(turnos.listar(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 11)).isEmpty());
        assertTrue(turnos.listar(LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 20)).isEmpty());
    }

    @Test
    void laListaTraeLosPagosDeCadaTurnoSinMezclarlos() {
        caja.abrirTurno(caja.administradorId, "0");
        Integer producto = caja.producto("A", 50, "10000");
        vender(List.of(linea(producto, 3)), efectivo("10000"), tarjeta("20000"));
        turnos.cerrar(contado("10000"));
        caja.abrirTurno(caja.administradorId, "0");
        vender(List.of(linea(producto, 1)), transferencia("10000"));
        LocalDate dia = LocalDate.of(2026, 11, 12);

        List<ResumenDeTurno> lista = turnos.listar(dia, dia);

        assertEquals(2, lista.size());
        ResumenDeTurno cerrado = lista.stream().filter(t -> t.cierre() != null).findFirst().orElseThrow();
        ResumenDeTurno abierto = lista.stream().filter(t -> t.cierre() == null).findFirst().orElseThrow();
        assertEquals(java.util.Set.of(TipoMedio.EFECTIVO, TipoMedio.TARJETA), cerrado.porMedio().keySet());
        assertEquals(java.util.Set.of(TipoMedio.TRANSFERENCIA), abierto.porMedio().keySet());
        igual("10000", cerrado.efectivoEsperado());
        igual("0", abierto.efectivoEsperado());
    }

    @Test
    void laListaNoMuestraNiSumaTurnosDeOtraEmpresa() {
        caja.abrirTurno(caja.administradorId, "10000");
        CajaDePrueba otra = CajaDePrueba.crear("Cierre de otra empresa", jdbc, plataforma, categorias, proveedores, clientes);
        try {
            otra.abrirTurno(otra.administradorId, "999");

            LocalDate dia = LocalDate.of(2026, 11, 12);
            List<ResumenDeTurno> lista = turnos.listar(dia, dia);

            assertEquals(1, lista.size());
            igual("10000", lista.get(0).efectivoEsperado());
        } finally {
            otra.borrar();
        }
    }

    // ------------------------------------------------------- la carrera contra el cierre

    private <T> Future<T> comoAdministrador(Callable<T> trabajo) {
        return como(caja.contextoDe(caja.administradorId, "ADMINISTRADOR"), trabajo);
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

    private Bloqueo bloqueoDelProducto(Integer producto) {
        return new Bloqueo(() -> jdbc.sql("""
                SELECT producto_id FROM productos FORCE INDEX (PRIMARY)
                 WHERE producto_id = :p AND empresa_id = :e FOR UPDATE
                """).param("p", producto).param("e", caja.empresa.empresaId).query(Integer.class).single());
    }

    private Bloqueo bloqueoDeLaVenta(Long venta) {
        return new Bloqueo(() -> jdbc.sql("""
                SELECT venta_id FROM ventas FORCE INDEX (PRIMARY) WHERE venta_id = :v AND empresa_id = :e FOR UPDATE
                """).param("v", venta).param("e", caja.empresa.empresaId).query(Long.class).single());
    }

    private Bloqueo bloqueoDelTurno(Long turno) {
        return new Bloqueo(() -> jdbc.sql("""
                SELECT cierre_id FROM cierres_caja FORCE INDEX (PRIMARY)
                 WHERE cierre_id = :t AND empresa_id = :e FOR UPDATE
                """).param("t", turno).param("e", caja.empresa.empresaId).query(Long.class).single());
    }

    private static <T> T resultado(Future<T> f) throws Exception {
        return f.get(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS);
    }

    /** La regla de fondo: lo guardado como esperado es la base más todo el efectivo con ese turno. */
    private void ningunPagoQuedaFuera(Long turno, String base) {
        BigDecimal efectivo = jdbc.sql("""
                SELECT COALESCE(SUM(p.monto), 0) FROM pagos_venta p
                  JOIN medios_pago m ON m.empresa_id = p.empresa_id AND m.medio_id = p.medio_id
                 WHERE p.empresa_id = :e AND p.cierre_id = :t AND m.tipo = 'EFECTIVO'
                """).param("e", caja.empresa.empresaId).param("t", turno).query(BigDecimal.class).single();
        igual(new BigDecimal(base).add(efectivo).toPlainString(), (BigDecimal) fila(turno).get("esperado"));
    }

    @Test
    void elCierreEsperaALaVentaEnCursoYLaCuenta() throws Exception {
        Long turno = caja.abrirTurno(caja.administradorId, "5000");
        Integer producto = caja.producto("A", 10, "10000");
        Future<VentaRegistrada> venta;
        Future<ResumenDeTurno> cierre;
        // La venta toma el turno FOR SHARE y se queda esperando el producto; el cierre llega después y
        // tiene que esperar a la venta, no pasar por encima de ella.
        try (Bloqueo bloqueo = bloqueoDelProducto(producto)) {
            venta = comoAdministrador(() -> vender(producto, 1, "10000"));
            esperarAQueEsperen("productos", 1, venta);
            cierre = comoAdministrador(() -> turnos.cerrar(contado("15000")));
            esperarAQueEsperen("cierres_caja", 1, venta, cierre);
        }

        resultado(venta);
        ResumenDeTurno cerrado = resultado(cierre);

        igual("15000", cerrado.efectivoEsperado());
        igual("0", cerrado.diferencia());
        ningunPagoQuedaFuera(turno, "5000");
        assertEquals(1, caja.cuantas("ventas"));
    }

    @Test
    void elCierreEsperaAlCobroContraEntregaEnCurso() throws Exception {
        Long turno = caja.abrirTurno(caja.administradorId, "0");
        Integer producto = caja.producto("A", 10, "3000");
        VentaRegistrada porCobrar = contraEntrega(producto, 3);
        Future<?> cobro;
        Future<ResumenDeTurno> cierre;
        try (Bloqueo bloqueo = bloqueoDeLaVenta(porCobrar.id())) {
            cobro = comoAdministrador(() -> domicilios.cobrar(porCobrar.id(), List.of(efectivo("9000"))));
            esperarAQueEsperen("ventas", 1, cobro);
            cierre = comoAdministrador(() -> turnos.cerrar(contado("9000")));
            esperarAQueEsperen("cierres_caja", 1, cobro, cierre);
        }

        resultado(cobro);
        ResumenDeTurno cerrado = resultado(cierre);

        igual("9000", cerrado.efectivoEsperado());
        ningunPagoQuedaFuera(turno, "0");
    }

    @Test
    void elCierreEsperaALaDevolucionEnCursoYLaDescuenta() throws Exception {
        Long turno = caja.abrirTurno(caja.administradorId, "0");
        Integer producto = caja.producto("A", 10, "10000");
        VentaRegistrada venta = vender(producto, 2, "20000");
        Future<DevolucionRegistrada> devolucion;
        Future<ResumenDeTurno> cierre;
        try (Bloqueo bloqueo = bloqueoDeLaVenta(venta.id())) {
            devolucion = comoAdministrador(() -> devolver(venta.id(), List.of(new UnidadDevuelta(producto, 1)),
                    reembolsoEnEfectivo("10000")));
            esperarAQueEsperen("ventas", 1, devolucion);
            cierre = comoAdministrador(() -> turnos.cerrar(contado("10000")));
            esperarAQueEsperen("cierres_caja", 1, devolucion, cierre);
        }

        resultado(devolucion);
        ResumenDeTurno cerrado = resultado(cierre);

        // 20 000 de la venta menos los 10 000 que salieron del cajón.
        igual("10000", cerrado.efectivoEsperado());
        igual("0", cerrado.diferencia());
        assertEquals(1, caja.cuantas("devoluciones"));
        assertEquals(turno, jdbc.sql("SELECT cierre_id FROM devoluciones WHERE empresa_id = :e")
                .param("e", caja.empresa.empresaId).query(Long.class).single());
    }

    @Test
    void ventaYCierreSimultaneosNingunPagoQuedaFuera() throws Exception {
        // Las dos llegan a la vez, con el turno sostenido desde fuera, y la venta entra primero a la cola;
        // quien gane, el resultado es uno de los dos: la venta entra y el esperado la incluye, o la venta
        // recibe 409. Nunca un pago del turno fuera del esperado.
        ventaYCierreALaVez(true);
    }

    @Test
    void cierreYVentaSimultaneosNingunPagoQuedaFueraTampoco() throws Exception {
        // El mismo cruce con el cierre primero en la cola.
        ventaYCierreALaVez(false);
    }

    private void ventaYCierreALaVez(boolean laVentaPrimero) throws Exception {
        Long turno = caja.abrirTurno(caja.administradorId, "1000");
        Integer producto = caja.producto("A", 10, "10000");
        Future<VentaRegistrada> venta;
        Future<ResumenDeTurno> cierre;
        try (Bloqueo bloqueo = bloqueoDelTurno(turno)) {
            if (laVentaPrimero) {
                venta = comoAdministrador(() -> vender(producto, 1, "10000"));
                esperarAQueEsperen("cierres_caja", 1, venta);
                cierre = comoAdministrador(() -> turnos.cerrar(new SolicitudCierre(new BigDecimal("11000"), "Por si no entra")));
            } else {
                cierre = comoAdministrador(() -> turnos.cerrar(new SolicitudCierre(new BigDecimal("1000"), "Por si entra")));
                esperarAQueEsperen("cierres_caja", 1, cierre);
                venta = comoAdministrador(() -> vender(producto, 1, "10000"));
            }
            esperarAQueEsperen("cierres_caja", 2, venta, cierre);
        }

        resultado(cierre);
        boolean entro;
        try {
            resultado(venta);
            entro = true;
        } catch (ExecutionException e) {
            assertInstanceOf(SinTurnoAbiertoException.class, e.getCause(), String.valueOf(e.getCause()));
            entro = false;
        }

        ningunPagoQuedaFuera(turno, "1000");
        assertEquals(entro ? 1 : 0, caja.cuantas("pagos_venta"));
        assertEquals(entro ? 1 : 0, caja.cuantas("ventas"));
        assertEquals(entro ? 9 : 10, caja.existencia(producto), "una venta rechazada no descuenta existencia");
        assertFalse(fila(turno).get("cierre") == null, "el turno quedó cerrado");
    }

    @Test
    void dosCierresSimultaneosUnoCierraYElOtroRecibe409() throws Exception {
        Long turno = caja.abrirTurno(caja.administradorId, "1000");
        Future<ResumenDeTurno> uno;
        Future<ResumenDeTurno> otro;
        try (Bloqueo bloqueo = bloqueoDelTurno(turno)) {
            uno = comoAdministrador(() -> turnos.cerrar(contado("1000")));
            otro = comoAdministrador(() -> turnos.cerrar(contado("1000")));
            esperarAQueEsperen("cierres_caja", 2, uno, otro);
        }

        int bien = 0;
        Throwable fallo = null;
        for (Future<ResumenDeTurno> f : List.of(uno, otro)) {
            try {
                resultado(f);
                bien++;
            } catch (ExecutionException e) {
                fallo = e.getCause();
            }
        }

        assertEquals(1, bien, "uno solo cierra");
        assertInstanceOf(SinTurnoAbiertoException.class, fallo, String.valueOf(fallo));
        ningunPagoQuedaFuera(turno, "1000");
    }
}

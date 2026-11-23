package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.Cliente;
import co.com.bspstore.sgip.api.dto.DevolucionRegistrada;
import co.com.bspstore.sgip.api.dto.Domicilio;
import co.com.bspstore.sgip.api.dto.DomicilioPendiente;
import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.api.dto.Reembolso;
import co.com.bspstore.sgip.api.dto.SaldoDeCliente;
import co.com.bspstore.sgip.api.dto.SolicitudAutorizacion;
import co.com.bspstore.sgip.api.dto.SolicitudDevolucion;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.api.dto.UnidadDevuelta;
import co.com.bspstore.sgip.api.dto.VentaConsultada;
import co.com.bspstore.sgip.api.dto.VentaRegistrada;
import co.com.bspstore.sgip.logica.AutorizacionRequeridaException;
import co.com.bspstore.sgip.logica.EstadoNoAdmiteLaOperacionException;
import co.com.bspstore.sgip.logica.RecursoNoEncontradoException;
import co.com.bspstore.sgip.logica.ClaveDeIdempotenciaEnUsoException;
import co.com.bspstore.sgip.logica.ResultadoDeDevolucion;
import co.com.bspstore.sgip.logica.ServicioConsultaDeVentas;
import co.com.bspstore.sgip.logica.ServicioDevoluciones;
import co.com.bspstore.sgip.logica.ServicioEncargos;
import co.com.bspstore.sgip.logica.ServicioDomicilios;
import co.com.bspstore.sgip.logica.ServicioSaldoAFavor;
import co.com.bspstore.sgip.logica.ServicioVentas;
import co.com.bspstore.sgip.logica.SinTurnoAbiertoException;
import co.com.bspstore.sgip.logica.TurnoRequeridoParaEfectivoException;
import co.com.bspstore.sgip.logica.VentaInvalidaException;
import co.com.bspstore.sgip.logica.venta.Descuento;
import co.com.bspstore.sgip.logica.venta.TipoDescuento;
import co.com.bspstore.sgip.modelo.TipoDocumento;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Devoluciones, anulaciones y saldo a favor (RF-68) contra el esquema real: lo que vale lo devuelto, a
 * dónde va el dinero (el turno de quien devuelve, o el saldo del cliente), el kárdex, la existencia, lo
 * que no se admite, y el cobro posterior de una venta contra entrega cuando ya se devolvió una parte.
 *
 * Crea su empresa y la borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class DevolucionesConBaseTest {

    private static final Domicilio CONTRA_ENTREGA = new Domicilio("Calle 1 # 2-3", "Cali", "3001112233", "Ana Pérez", true);

    @Autowired ServicioVentas ventas;
    @Autowired ServicioDevoluciones devoluciones;
    @Autowired ServicioDomicilios domicilios;
    @Autowired ServicioSaldoAFavor saldos;
    @Autowired ServicioEncargos encargos;
    @Autowired ServicioConsultaDeVentas consulta;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioClientes clientes;
    @Autowired JdbcClient jdbc;

    private CajaDePrueba caja;
    private Long turnoDelAdministrador;
    private Long turnoDelVendedor;

    @BeforeEach
    void prepararDatosPropios() {
        caja = CajaDePrueba.crear("Devoluciones", jdbc, plataforma, categorias, proveedores, clientes);
        turnoDelAdministrador = caja.abrirTurno(caja.administradorId);
        turnoDelVendedor = caja.abrirTurno(caja.vendedorId);
        caja.entrarComoAdministrador();
    }

    @AfterEach
    void borrarLoQueCreo() {
        if (caja != null) {
            caja.borrar();
        }
    }

    // ------------------------------------------------------- ayudas

    private PagoSolicitado efectivo(String monto) {
        return new PagoSolicitado(caja.efectivoId, new BigDecimal(monto), null, null);
    }

    private PagoSolicitado saldo(String monto) {
        return new PagoSolicitado(caja.saldoAFavorId, new BigDecimal(monto), null, null);
    }

    private static Reembolso reembolso(Integer medio, String monto, String referencia) {
        return new Reembolso(medio, new BigDecimal(monto), referencia);
    }

    private Reembolso enEfectivo(String monto) {
        return reembolso(caja.efectivoId, monto, null);
    }

    private Reembolso enSaldo(String monto) {
        return reembolso(caja.saldoAFavorId, monto, null);
    }

    private VentaRegistrada vender(Integer cliente, Integer producto, int cantidad, PagoSolicitado... pagos) {
        return ventas.registrar(new SolicitudVenta(UUID.randomUUID(), cliente,
                List.of(new LineaSolicitada(producto, null, cantidad, null, null)), null, List.of(pagos), null, null, null));
    }

    private VentaRegistrada vender(Integer producto, int cantidad, String efectivo) {
        return vender(caja.clienteId, producto, cantidad, efectivo(efectivo));
    }

    private VentaRegistrada contraEntrega(Integer producto, int cantidad) {
        return ventas.registrar(new SolicitudVenta(UUID.randomUUID(), caja.clienteId,
                List.of(new LineaSolicitada(producto, null, cantidad, null, null)), null, List.of(), null, null,
                CONTRA_ENTREGA));
    }

    private SolicitudAutorizacion conPinDelAdministrador() {
        return new SolicitudAutorizacion(caja.administradorId, CajaDePrueba.PIN);
    }

    /** Lo que envía el administrador: sin PIN. */
    private static SolicitudDevolucion devolucion(Long venta, List<UnidadDevuelta> unidades, List<Reembolso> reembolsos) {
        return new SolicitudDevolucion(UUID.randomUUID(), venta, unidades, "El cliente se arrepintió", reembolsos, null);
    }

    private DevolucionRegistrada devolver(Long venta, List<UnidadDevuelta> unidades, Reembolso... reembolsos) {
        return devoluciones.registrar(devolucion(venta, unidades, List.of(reembolsos)));
    }

    private DevolucionRegistrada anular(Long venta, Reembolso... reembolsos) {
        return devolver(venta, List.of(), reembolsos);
    }

    private static void igual(String esperado, BigDecimal obtenido) {
        assertEquals(0, new BigDecimal(esperado).compareTo(obtenido), "esperaba " + esperado + " y fue " + obtenido);
    }

    private Map<String, Object> ventaGuardada(Long id) {
        return jdbc.sql("SELECT * FROM ventas WHERE empresa_id = :e AND venta_id = :id")
                .param("e", caja.empresa.empresaId).param("id", id).query().singleRow();
    }

    private Map<String, Object> devolucionGuardada(Long id) {
        return jdbc.sql("SELECT * FROM devoluciones WHERE empresa_id = :e AND devolucion_id = :id")
                .param("e", caja.empresa.empresaId).param("id", id).query().singleRow();
    }

    private BigDecimal saldoGuardado(Integer cliente) {
        return jdbc.sql("SELECT COALESCE(SUM(saldo), 0) FROM saldos_cliente WHERE empresa_id = :e AND cliente_id = :c")
                .param("e", caja.empresa.empresaId).param("c", cliente).query(BigDecimal.class).single();
    }

    /**
     * Le deja saldo al cliente como lo hace el sistema: le vende una unidad del producto (de ese precio) y la
     * devuelve, el {@code monto} al saldo y el resto en efectivo.
     */
    private void darSaldo(Integer cliente, Integer producto, String precio, String monto) {
        VentaRegistrada v = vender(cliente, producto, 1, efectivo(precio));
        BigDecimal resto = new BigDecimal(precio).subtract(new BigDecimal(monto));
        if (resto.signum() == 0) {
            anular(v.id(), enSaldo(monto));
        } else {
            anular(v.id(), enSaldo(monto), enEfectivo(resto.toPlainString()));
        }
    }

    private void nadaDevuelto(Integer producto, int existencia) {
        assertEquals(0, caja.cuantas("devoluciones"), "ninguna devolución");
        assertEquals(0, caja.cuantas("devolucion_items"), "ningún ítem");
        assertEquals(0, caja.cuantas("reembolsos"), "ningún reembolso");
        assertEquals(0, caja.movimientosDeTipo("DEVOLUCION"), "ningún movimiento de kárdex");
        assertEquals(existencia, caja.existencia(producto), "la existencia no se movió");
    }

    // ------------------------------------------------------- la autorización

    @Test
    void devolverSinPinEs403() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada v = vender(producto, 1, "10000");
        caja.entrarComoVendedor();

        assertThrows(AutorizacionRequeridaException.class,
                () -> devoluciones.registrar(devolucion(v.id(), List.of(), List.of(enEfectivo("10000")))));

        nadaDevuelto(producto, 4);
        assertEquals("REGISTRADA", ventaGuardada(v.id()).get("estado"));
    }

    @Test
    void elVendedorConElPinDelAdministradorDevuelveYQuedaSuNombreComoAutorizador() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada v = vender(producto, 1, "10000");
        caja.entrarComoVendedor();

        DevolucionRegistrada r = devoluciones.registrar(new SolicitudDevolucion(UUID.randomUUID(), v.id(), List.of(), "Falló",
                List.of(enEfectivo("10000")), conPinDelAdministrador()));

        Map<String, Object> d = devolucionGuardada(r.id());
        assertEquals(caja.administradorId, d.get("autorizado_por"));
        assertEquals(caja.vendedorId, d.get("registrado_por"));
        assertEquals(turnoDelVendedor, d.get("cierre_id"), "el dinero sale del turno de quien devuelve");
    }

    @Test
    void elAdministradorNoNecesitaPinYSeAutorizaASiMismo() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada v = vender(producto, 1, "10000");

        DevolucionRegistrada r = anular(v.id(), enEfectivo("10000"));

        assertEquals(caja.administradorId, devolucionGuardada(r.id()).get("autorizado_por"));
        assertEquals(caja.administradorId, devolucionGuardada(r.id()).get("registrado_por"));
    }

    // ------------------------------------------------------- la forma

    @Test
    void motivoVacioEs400() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada v = vender(producto, 1, "10000");

        for (String motivo : new String[]{null, "", "   "}) {
            assertThrows(VentaInvalidaException.class, () -> devoluciones.registrar(
                    new SolicitudDevolucion(UUID.randomUUID(), v.id(), List.of(), motivo, List.of(enEfectivo("10000")), null)));
        }

        nadaDevuelto(producto, 4);
    }

    @Test
    void unaVentaQueNoExisteOEsDeOtraEmpresaEs404() {
        CajaDePrueba ajena = CajaDePrueba.crear("Ajena", jdbc, plataforma, categorias, proveedores, clientes);
        try {
            Integer productoAjeno = ajena.producto("A", 5, "10000");
            ajena.abrirTurno(ajena.administradorId);
            ajena.entrarComoAdministrador();
            VentaRegistrada deLaAjena = ventas.registrar(new SolicitudVenta(UUID.randomUUID(), null,
                    List.of(new LineaSolicitada(productoAjeno, null, 1, null, null)), null,
                    List.of(new PagoSolicitado(ajena.efectivoId, new BigDecimal("10000"), null, null)), null, null, null));
            caja.entrarComoAdministrador();

            assertThrows(RecursoNoEncontradoException.class, () -> anular(deLaAjena.id(), enEfectivo("10000")));
            assertThrows(RecursoNoEncontradoException.class, () -> anular(2_000_000_000L, enEfectivo("10000")));

            assertEquals(4, ajena.existencia(productoAjeno), "la venta ajena no se tocó");
            assertEquals("REGISTRADA", jdbc.sql("SELECT estado FROM ventas WHERE venta_id = :v")
                    .param("v", deLaAjena.id()).query(String.class).single());
        } finally {
            ajena.borrar();
        }
    }

    @Test
    void unProductoQueNoEstaEnLaVentaEs400() {
        Integer producto = caja.producto("A", 5, "10000");
        Integer otro = caja.producto("B", 5, "10000");
        VentaRegistrada v = vender(producto, 1, "10000");

        assertThrows(VentaInvalidaException.class,
                () -> devolver(v.id(), List.of(new UnidadDevuelta(otro, 1)), enEfectivo("10000")));

        nadaDevuelto(producto, 4);
        assertEquals(5, caja.existencia(otro));
    }

    // ------------------------------------------------------- cuánto se puede devolver

    @Test
    void devolverMasDeLoVendidoEs400ContandoTambienLasDevolucionesAnteriores() {
        Integer producto = caja.producto("A", 10, "10000");
        VentaRegistrada v = vender(producto, 3, "30000");
        devolver(v.id(), List.of(new UnidadDevuelta(producto, 2)), enEfectivo("20000"));

        assertThrows(VentaInvalidaException.class,
                () -> devolver(v.id(), List.of(new UnidadDevuelta(producto, 2)), enEfectivo("20000")),
                "se vendieron 3 y ya se devolvieron 2");
        assertThrows(VentaInvalidaException.class,
                () -> devolver(v.id(), List.of(new UnidadDevuelta(producto, 4)), enEfectivo("40000")),
                "más de lo vendido de una vez");

        assertEquals(1, caja.cuantas("devoluciones"));
        assertEquals(9, caja.existencia(producto), "10 - 3 + 2");
    }

    @Test
    void anularDosVecesEs409() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada v = vender(producto, 2, "20000");
        anular(v.id(), enEfectivo("20000"));

        assertThrows(EstadoNoAdmiteLaOperacionException.class, () -> anular(v.id(), enEfectivo("20000")));
        assertThrows(EstadoNoAdmiteLaOperacionException.class,
                () -> devolver(v.id(), List.of(new UnidadDevuelta(producto, 1)), enEfectivo("10000")),
                "devolver de una venta anulada también es 409, no 400");

        assertEquals(1, caja.cuantas("devoluciones"));
        assertEquals(5, caja.existencia(producto));
        assertEquals("ANULADA", ventaGuardada(v.id()).get("estado"));
    }

    @Test
    void unaDevolucionParcialDejaLaVentaDevueltaParcialYLaQueCompletaLaAnula() {
        Integer producto = caja.producto("A", 10, "10000");
        VentaRegistrada v = vender(producto, 3, "30000");

        DevolucionRegistrada primera = devolver(v.id(), List.of(new UnidadDevuelta(producto, 1)), enEfectivo("10000"));

        assertEquals("DEVUELTA_PARCIAL", primera.estadoVenta());
        assertEquals("DEVUELTA_PARCIAL", ventaGuardada(v.id()).get("estado"));
        assertEquals("PAGADA", ventaGuardada(v.id()).get("estado_pago"));

        DevolucionRegistrada resto = anular(v.id(), enEfectivo("20000"));

        igual("20000", resto.valorDevuelto());
        assertEquals("ANULADA", resto.estadoVenta());
        assertEquals("ANULADA", ventaGuardada(v.id()).get("estado"));
        assertEquals(10, caja.existencia(producto));
    }

    // ------------------------------------------------------- existencia y kárdex

    @Test
    void devolucionParcialReintegraExistenciaYKardex() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada v = vender(producto, 3, "30000");
        assertEquals(2, caja.existencia(producto));

        DevolucionRegistrada r = devolver(v.id(), List.of(new UnidadDevuelta(producto, 1)), enEfectivo("10000"));

        assertEquals(3, caja.existencia(producto));
        assertEquals(1, caja.movimientosDe(producto, "DEVOLUCION"));
        Map<String, Object> m = jdbc.sql("""
                SELECT * FROM movimientos_inventario
                 WHERE empresa_id = :e AND producto_id = :p AND tipo = 'DEVOLUCION'
                """).param("e", caja.empresa.empresaId).param("p", producto).query().singleRow();
        assertEquals(1, ((Number) m.get("cantidad")).intValue());
        assertEquals(2, ((Number) m.get("existencia_anterior")).intValue());
        assertEquals(3, ((Number) m.get("existencia_posterior")).intValue());
        assertEquals(r.consecutivo(), m.get("documento"), "el kárdex apunta a la devolución");
        assertEquals(caja.administradorId, m.get("usuario_id"));
        assertEquals(1, caja.cuantas("devolucion_items"));
    }

    @Test
    void anularUnaVentaConVariasLineasReintegraTodasYDejaUnMovimientoPorProducto() {
        Integer a = caja.producto("A", 5, "10000");
        Integer b = caja.producto("B", 5, "5000");
        VentaRegistrada v = ventas.registrar(new SolicitudVenta(UUID.randomUUID(), caja.clienteId,
                List.of(new LineaSolicitada(a, null, 2, null, null), new LineaSolicitada(b, null, 1, null, null)), null,
                List.of(efectivo("25000")), null, null, null));

        DevolucionRegistrada r = anular(v.id(), enEfectivo("25000"));

        igual("25000", r.valorDevuelto());
        assertEquals(5, caja.existencia(a));
        assertEquals(5, caja.existencia(b));
        assertEquals(1, caja.movimientosDe(a, "DEVOLUCION"));
        assertEquals(1, caja.movimientosDe(b, "DEVOLUCION"));
        assertEquals(2, caja.cuantas("devolucion_items"));
    }

    // ------------------------------------------------------- el consecutivo

    @Test
    void elConsecutivoDeDevolucionTieneElFormato() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada v = vender(producto, 2, "20000");

        DevolucionRegistrada primera = devolver(v.id(), List.of(new UnidadDevuelta(producto, 1)), enEfectivo("10000"));
        DevolucionRegistrada segunda = devolver(v.id(), List.of(new UnidadDevuelta(producto, 1)), enEfectivo("10000"));

        assertEquals("D-00000001", primera.consecutivo());
        assertEquals("D-00000002", segunda.consecutivo());
        assertEquals("D-00000001", devolucionGuardada(primera.id()).get("consecutivo"));
    }

    @Test
    void unaDevolucionQueFallaNoGastaConsecutivo() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada v = vender(producto, 1, "10000");
        assertThrows(VentaInvalidaException.class, () -> devolver(v.id(), List.of(new UnidadDevuelta(producto, 2)),
                enEfectivo("20000")));

        DevolucionRegistrada r = anular(v.id(), enEfectivo("10000"));

        assertEquals("D-00000001", r.consecutivo());
    }

    // ------------------------------------------------------- el reembolso

    @Test
    void reembolsoMixtoEfectivoYSaldo() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada v = vender(producto, 1, "10000");

        DevolucionRegistrada r = anular(v.id(), enEfectivo("6000"), enSaldo("4000"));

        igual("10000", r.valorDevuelto());
        igual("10000", r.reembolsado());
        igual("0", r.saldoPorCobrar());
        assertEquals(2, r.reembolsos().size());
        assertEquals(2, caja.cuantas("reembolsos"));
        igual("4000", saldoGuardado(caja.clienteId));
        Map<String, Object> mov = jdbc.sql("SELECT * FROM movimientos_saldo WHERE empresa_id = :e")
                .param("e", caja.empresa.empresaId).query().singleRow();
        assertEquals("ABONO", mov.get("tipo"));
        igual("4000", (BigDecimal) mov.get("monto"));
        assertEquals(r.id(), mov.get("devolucion_id"));
        assertNull(mov.get("venta_id"));
        assertEquals(turnoDelAdministrador, devolucionGuardada(r.id()).get("cierre_id"));
    }

    @Test
    void reembolsoQueNoCuadraConLoPagadoEs400() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada v = vender(producto, 2, "20000");
        List<UnidadDevuelta> una = List.of(new UnidadDevuelta(producto, 1));

        assertThrows(VentaInvalidaException.class, () -> devolver(v.id(), una, enEfectivo("9000")), "de menos");
        assertThrows(VentaInvalidaException.class, () -> devolver(v.id(), una, enEfectivo("11000")), "de más");
        assertThrows(VentaInvalidaException.class, () -> devolver(v.id(), una), "sin reembolso");
        assertThrows(VentaInvalidaException.class,
                () -> devolver(v.id(), una, enEfectivo("5000"), enSaldo("4000")), "la suma no cuadra");

        nadaDevuelto(producto, 3);
        igual("0", saldoGuardado(caja.clienteId));
    }

    @Test
    void reembolsoPorTarjetaOTransferenciaSinReferenciaEs400() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada v = vender(producto, 2, "20000");
        List<UnidadDevuelta> una = List.of(new UnidadDevuelta(producto, 1));

        assertThrows(VentaInvalidaException.class,
                () -> devolver(v.id(), una, reembolso(caja.tarjetaId, "10000", null)));
        assertThrows(VentaInvalidaException.class,
                () -> devolver(v.id(), una, reembolso(caja.transferenciaId, "10000", null)));
        nadaDevuelto(producto, 3);

        DevolucionRegistrada r = devolver(v.id(), una, reembolso(caja.tarjetaId, "10000", "DATAFONO-123"));

        assertEquals("DATAFONO-123", jdbc.sql("SELECT referencia FROM reembolsos WHERE devolucion_id = :d")
                .param("d", r.id()).query(String.class).single());
        assertEquals(turnoDelAdministrador, devolucionGuardada(r.id()).get("cierre_id"));
    }

    @Test
    void unMedioInactivoOAjenoOInexistenteNoSirveParaReembolsar() {
        CajaDePrueba ajena = CajaDePrueba.crear("Ajena", jdbc, plataforma, categorias, proveedores, clientes);
        try {
            Integer producto = caja.producto("A", 5, "10000");
            VentaRegistrada v = vender(producto, 1, "10000");
            caja.entrarComoAdministrador();

            for (Integer medio : List.of(caja.inactivoId, ajena.efectivoId, 2_000_000_000)) {
                assertThrows(VentaInvalidaException.class,
                        () -> anular(v.id(), reembolso(medio, "10000", "REF")), "medio " + medio);
            }
            nadaDevuelto(producto, 4);
        } finally {
            ajena.borrar();
        }
    }

    @Test
    void sinTurnoAbiertoUnaDevolucionConDineroEs409YNoGuardaNada() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada v = vender(producto, 1, "10000");
        caja.cerrarTurno(turnoDelAdministrador);
        caja.cerrarTurno(turnoDelVendedor);

        // El administrador sin turno puede devolver sin efectivo (DevolucionSinTurnoConBaseTest); el efectivo no.
        assertThrows(TurnoRequeridoParaEfectivoException.class, () -> anular(v.id(), enEfectivo("10000")));
        // El vendedor sin turno no devuelve nada, ni con el PIN del administrador.
        caja.entrarComoVendedor();
        assertThrows(SinTurnoAbiertoException.class, () -> devoluciones.registrar(new SolicitudDevolucion(
                UUID.randomUUID(), v.id(), List.of(), "Falló", List.of(enEfectivo("10000")), conPinDelAdministrador())));

        nadaDevuelto(producto, 4);
        assertEquals("REGISTRADA", ventaGuardada(v.id()).get("estado"));
    }

    @Test
    void laDevolucionSeRegistraEnElTurnoDeQuienLaHaceNoEnElDeLaVenta() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada v = vender(producto, 1, "10000");
        assertEquals(turnoDelAdministrador, ventaGuardada(v.id()).get("cierre_id"));
        caja.entrarComoVendedor();

        DevolucionRegistrada r = devoluciones.registrar(new SolicitudDevolucion(UUID.randomUUID(), v.id(), List.of(), "Falló",
                List.of(enEfectivo("10000")), conPinDelAdministrador()));

        assertEquals(turnoDelVendedor, devolucionGuardada(r.id()).get("cierre_id"));
    }

    // ------------------------------------------------------- cuánto vale lo devuelto

    @Test
    void devolucionConDescuentoProrrateadoDevuelveLaParteJusta() {
        Integer seis = caja.producto("A", 5, "6000");
        Integer tres = caja.producto("B", 5, "3000");
        VentaRegistrada v = ventas.registrar(new SolicitudVenta(UUID.randomUUID(), caja.clienteId,
                List.of(new LineaSolicitada(seis, null, 1, null, null), new LineaSolicitada(tres, null, 1, null, null)),
                new Descuento(TipoDescuento.PESOS, new BigDecimal("1000")), List.of(efectivo("8000")), null, null, null));
        igual("8000", v.total());

        DevolucionRegistrada r = devolver(v.id(), List.of(new UnidadDevuelta(tres, 1)), enEfectivo("2667"));

        igual("2667", r.valorDevuelto());
        igual("2667", jdbc.sql("SELECT valor FROM devolucion_items WHERE devolucion_id = :d")
                .param("d", r.id()).query(BigDecimal.class).single());
    }

    @Test
    void lasDevolucionesDeUnaLineaSumanSuTotal() {
        Integer producto = caja.producto("A", 10, "1000");
        // Tres unidades de 1 000 con 2 000 de descuento: la línea vale 1 000.
        VentaRegistrada v = ventas.registrar(new SolicitudVenta(UUID.randomUUID(), caja.clienteId,
                List.of(new LineaSolicitada(producto, null, 3,
                        new Descuento(TipoDescuento.PESOS, new BigDecimal("2000")), null)), null,
                List.of(efectivo("1000")), null, null, null));
        igual("1000", v.total());

        DevolucionRegistrada una = devolver(v.id(), List.of(new UnidadDevuelta(producto, 1)), enEfectivo("333"));
        DevolucionRegistrada otra = devolver(v.id(), List.of(new UnidadDevuelta(producto, 1)), enEfectivo("333"));
        DevolucionRegistrada ultima = devolver(v.id(), List.of(new UnidadDevuelta(producto, 1)), enEfectivo("334"));

        igual("333", una.valorDevuelto());
        igual("333", otra.valorDevuelto());
        igual("334", ultima.valorDevuelto());
        igual("1000", jdbc.sql("SELECT SUM(valor) FROM devolucion_items WHERE empresa_id = :e")
                .param("e", caja.empresa.empresaId).query(BigDecimal.class).single());
        assertEquals("ANULADA", ultima.estadoVenta());
    }

    @Test
    void devolverDosDeTresDeUnaVezValeSeiscientosSesentaYSiete() {
        Integer producto = caja.producto("A", 10, "1000");
        VentaRegistrada v = ventas.registrar(new SolicitudVenta(UUID.randomUUID(), caja.clienteId,
                List.of(new LineaSolicitada(producto, null, 3,
                        new Descuento(TipoDescuento.PESOS, new BigDecimal("2000")), null)), null,
                List.of(efectivo("1000")), null, null, null));

        DevolucionRegistrada r = devolver(v.id(), List.of(new UnidadDevuelta(producto, 2)), enEfectivo("667"));

        igual("667", r.valorDevuelto());
        assertEquals("DEVUELTA_PARCIAL", r.estadoVenta());
    }

    // ------------------------------------------------------- el saldo a favor

    @Test
    void saldoAFavorSeUsaEnUnaCompraPosterior() {
        Integer producto = caja.producto("A", 10, "10000");
        darSaldo(caja.clienteId, producto, "10000", "10000");
        igual("10000", saldoGuardado(caja.clienteId));

        VentaRegistrada v = vender(caja.clienteId, producto, 1, saldo("10000"));

        igual("10000", v.total());
        igual("0", saldoGuardado(caja.clienteId));
        assertEquals("PAGADA", ventaGuardada(v.id()).get("estado_pago"));
    }

    @Test
    void unaCompraPuedePagarseMitadConSaldoYMitadEnEfectivo() {
        Integer producto = caja.producto("A", 10, "10000");
        darSaldo(caja.clienteId, producto, "10000", "6000");
        VentaRegistrada v = vender(caja.clienteId, producto, 1, saldo("6000"), efectivo("4000"));

        igual("10000", v.total());
        igual("0", saldoGuardado(caja.clienteId));
        assertEquals(2, jdbc.sql("SELECT COUNT(*) FROM pagos_venta WHERE venta_id = :v")
                .param("v", v.id()).query(Integer.class).single());
    }

    @Test
    void noSePuedeUsarMasSaldoDelQueHay() {
        Integer producto = caja.producto("A", 10, "10000");
        darSaldo(caja.clienteId, producto, "10000", "4000");
        int ventasAntes = caja.cuantas("ventas");
        int existencia = caja.existencia(producto);

        assertThrows(VentaInvalidaException.class,
                () -> vender(caja.clienteId, producto, 1, saldo("10000")), "hay 4 000 y se piden 10 000");
        assertThrows(VentaInvalidaException.class,
                () -> vender(caja.clienteId, producto, 1, saldo("5000"), efectivo("5000")), "5 000 de 4 000");

        igual("4000", saldoGuardado(caja.clienteId));
        assertEquals(ventasAntes, caja.cuantas("ventas"), "ninguna venta nueva");
        assertEquals(existencia, caja.existencia(producto), "la existencia no se movió");
        assertEquals(0, caja.movimientosDeSaldo("USO"));
    }

    @Test
    void sinNingunSaldoPagarConSaldoEs400() {
        Integer producto = caja.producto("A", 10, "10000");

        assertThrows(VentaInvalidaException.class, () -> vender(caja.clienteId, producto, 1, saldo("10000")));

        assertEquals(0, caja.cuantas("ventas"));
        assertEquals(10, caja.existencia(producto));
    }

    @Test
    void elPagoConSaldoNoLlevaCierreYDejaMovimientoDeUso() {
        Integer producto = caja.producto("A", 10, "10000");
        darSaldo(caja.clienteId, producto, "10000", "10000");

        VentaRegistrada v = vender(caja.clienteId, producto, 1, saldo("7000"), efectivo("3000"));

        Map<String, Object> deSaldo = jdbc.sql("""
                SELECT * FROM pagos_venta WHERE venta_id = :v AND medio_id = :m
                """).param("v", v.id()).param("m", caja.saldoAFavorId).query().singleRow();
        assertNull(deSaldo.get("cierre_id"), "el pago con saldo no pasa por la caja");
        Map<String, Object> enEfectivo = jdbc.sql("""
                SELECT * FROM pagos_venta WHERE venta_id = :v AND medio_id = :m
                """).param("v", v.id()).param("m", caja.efectivoId).query().singleRow();
        assertEquals(turnoDelAdministrador, enEfectivo.get("cierre_id"), "el efectivo sí");
        Map<String, Object> uso = jdbc.sql("SELECT * FROM movimientos_saldo WHERE empresa_id = :e AND tipo = 'USO'")
                .param("e", caja.empresa.empresaId).query().singleRow();
        igual("7000", (BigDecimal) uso.get("monto"));
        assertEquals(v.id(), uso.get("venta_id"));
        assertNull(uso.get("devolucion_id"));
        assertEquals(caja.clienteId, uso.get("cliente_id"));
        igual("3000", saldoGuardado(caja.clienteId));
    }

    @Test
    void consumidorFinalNoRecibeSaldo() {
        Integer producto = caja.producto("A", 10, "10000");
        VentaRegistrada v = vender(null, producto, 1, efectivo("10000"));

        assertThrows(VentaInvalidaException.class, () -> anular(v.id(), enSaldo("10000")));

        nadaDevuelto(producto, 9);
        assertEquals(0, caja.cuantas("saldos_cliente"));
        // Tampoco puede pagar con saldo: no tiene.
        assertThrows(VentaInvalidaException.class, () -> vender(null, producto, 1, saldo("10000")));
        assertEquals("REGISTRADA", ventaGuardada(v.id()).get("estado"));
    }

    @Test
    void elSaldoDelClienteSeConsultaYEsDeLaEmpresa() {
        Integer producto = caja.producto("A", 10, "10000");
        darSaldo(caja.clienteId, producto, "10000", "10000");
        CajaDePrueba ajena = CajaDePrueba.crear("Ajena", jdbc, plataforma, categorias, proveedores, clientes);
        try {
            caja.entrarComoVendedor();

            SaldoDeCliente propio = saldos.consultar(caja.clienteId);
            SaldoDeCliente consumidor = saldos.consultar(caja.consumidorFinalId);
            SaldoDeCliente sinMovimientos = saldos.consultar(clienteNuevo());

            igual("10000", propio.saldo());
            assertEquals(caja.saldoAFavorId, propio.medioId(), "el medio de sistema de ESTA empresa");
            igual("0", consumidor.saldo());
            igual("0", sinMovimientos.saldo());
            assertThrows(RecursoNoEncontradoException.class, () -> saldos.consultar(ajena.clienteId),
                    "un cliente de otra empresa no existe");
            assertThrows(RecursoNoEncontradoException.class, () -> saldos.consultar(2_000_000_000));
        } finally {
            ajena.borrar();
        }
    }

    private Integer clienteNuevo() {
        return clientes.insertar(caja.empresa.empresaId, new Cliente(null, "Otro cliente", TipoDocumento.CC,
                "7" + caja.empresa.marca, "otro-" + caja.empresa.marca.toLowerCase() + "@ejemplo.co", "3001112233",
                "Calle 2", "Cali", false), LocalDateTime.of(2026, 11, 1, 8, 0), "CAJA");
    }

    // ------------------------------------------------------- la venta por cobrar (contra entrega)

    @Test
    void anularUnaVentaPendienteNoExigeTurnoNiReembolso() {
        Integer producto = caja.producto("A", 5, "9000");
        VentaRegistrada v = contraEntrega(producto, 1);
        assertEquals(4, caja.existencia(producto));
        caja.cerrarTurno(turnoDelAdministrador);

        DevolucionRegistrada r = anular(v.id());

        igual("0", r.reembolsado());
        igual("0", r.saldoPorCobrar());
        assertEquals("ANULADA", r.estadoVenta());
        assertEquals("ANULADA", ventaGuardada(v.id()).get("estado"));
        assertEquals("ANULADA", ventaGuardada(v.id()).get("estado_pago"));
        assertEquals(5, caja.existencia(producto), "la existencia vuelve");
        assertNull(devolucionGuardada(r.id()).get("cierre_id"), "no movió dinero");
        assertEquals(0, caja.cuantas("reembolsos"));
    }

    @Test
    void unaVentaPendienteNoTieneDineroQueReembolsar() {
        Integer producto = caja.producto("A", 5, "9000");
        VentaRegistrada v = contraEntrega(producto, 1);

        assertThrows(VentaInvalidaException.class, () -> anular(v.id(), enEfectivo("9000")));

        nadaDevuelto(producto, 4);
    }

    @Test
    void devolucionParcialDeVentaPendienteReduceLoPorCobrar() {
        Integer producto = caja.producto("A", 5, "3000");
        VentaRegistrada v = contraEntrega(producto, 3);
        igual("9000", v.total());

        DevolucionRegistrada r = devolver(v.id(), List.of(new UnidadDevuelta(producto, 1)));

        igual("3000", r.valorDevuelto());
        igual("0", r.reembolsado());
        igual("6000", r.saldoPorCobrar());
        assertEquals("DEVUELTA_PARCIAL", r.estadoVenta());
        assertEquals("PENDIENTE", ventaGuardada(v.id()).get("estado_pago"), "sigue por cobrar");
        assertNull(devolucionGuardada(r.id()).get("cierre_id"));
        DomicilioPendiente fila = domicilios.listar("POR_COBRAR").stream()
                .filter(d -> d.ventaId().equals(v.id())).findFirst().orElseThrow();
        igual("6000", fila.valorPorCobrar());
        igual("9000", fila.total());
    }

    @Test
    void devolverTodoDeUnaVentaPendientePorUnidadesLaAnula() {
        Integer producto = caja.producto("A", 5, "3000");
        VentaRegistrada v = contraEntrega(producto, 3);
        devolver(v.id(), List.of(new UnidadDevuelta(producto, 1)));

        DevolucionRegistrada r = devolver(v.id(), List.of(new UnidadDevuelta(producto, 2)));

        igual("0", r.saldoPorCobrar());
        assertEquals("ANULADA", r.estadoVenta());
        assertEquals("ANULADA", ventaGuardada(v.id()).get("estado_pago"));
        assertTrue(domicilios.listar("POR_COBRAR").stream().noneMatch(d -> d.ventaId().equals(v.id())));
    }

    @Test
    void cobrarUnaVentaConDevolucionParcialCobraElSaldoActualizado() {
        Integer producto = caja.producto("A", 5, "3000");
        VentaRegistrada v = contraEntrega(producto, 3);
        devolver(v.id(), List.of(new UnidadDevuelta(producto, 1)));

        assertThrows(VentaInvalidaException.class, () -> domicilios.cobrar(v.id(), List.of(efectivo("9000"))),
                "el total original ya no es lo que se debe");
        var cobro = domicilios.cobrar(v.id(), List.of(efectivo("6000")));

        igual("6000", cobro.cobrado());
        assertEquals("PAGADA", ventaGuardada(v.id()).get("estado_pago"));
        assertEquals("DEVUELTA_PARCIAL", ventaGuardada(v.id()).get("estado"), "cobrar no cambia el estado de la venta");
        assertThrows(EstadoNoAdmiteLaOperacionException.class, () -> domicilios.cobrar(v.id(), List.of(efectivo("6000"))));
    }

    @Test
    void unaVentaAnuladaNoSeCobra() {
        Integer producto = caja.producto("A", 5, "3000");
        VentaRegistrada v = contraEntrega(producto, 1);
        anular(v.id());

        assertThrows(EstadoNoAdmiteLaOperacionException.class, () -> domicilios.cobrar(v.id(), List.of(efectivo("3000"))));
    }

    @Test
    void elContraEntregaNoSePagaConSaldoAFavor() {
        Integer producto = caja.producto("A", 5, "3000");
        darSaldo(caja.clienteId, producto, "3000", "3000");
        VentaRegistrada v = contraEntrega(producto, 1);

        assertThrows(VentaInvalidaException.class, () -> domicilios.cobrar(v.id(), List.of(saldo("3000"))));

        igual("3000", saldoGuardado(caja.clienteId));
        assertEquals("PENDIENTE", ventaGuardada(v.id()).get("estado_pago"));
    }

    // ------------------------------------------------------- la clave de idempotencia

    private SolicitudDevolucion conClave(UUID clave, Long venta, List<UnidadDevuelta> unidades, Reembolso... reembolsos) {
        return new SolicitudDevolucion(clave, venta, unidades, "El cliente se arrepintió", List.of(reembolsos), null);
    }

    private static void mismaRespuesta(DevolucionRegistrada esperada, DevolucionRegistrada obtenida) {
        assertEquals(esperada.id(), obtenida.id());
        assertEquals(esperada.consecutivo(), obtenida.consecutivo());
        igual(esperada.valorDevuelto().toPlainString(), obtenida.valorDevuelto());
        igual(esperada.reembolsado().toPlainString(), obtenida.reembolsado());
        igual(esperada.saldoPorCobrar().toPlainString(), obtenida.saldoPorCobrar());
        assertEquals(esperada.estadoVenta(), obtenida.estadoVenta());
        assertEquals(esperada.reembolsos().size(), obtenida.reembolsos().size());
        for (int i = 0; i < esperada.reembolsos().size(); i++) {
            assertEquals(esperada.reembolsos().get(i).medioId(), obtenida.reembolsos().get(i).medioId());
            igual(esperada.reembolsos().get(i).monto().toPlainString(), obtenida.reembolsos().get(i).monto());
            assertEquals(esperada.reembolsos().get(i).referencia(), obtenida.reembolsos().get(i).referencia());
        }
    }

    @Test
    void elReintentoConLaMismaClaveDevuelveLaPrimeraRespuestaYNoRegistraOtra() {
        Integer producto = caja.producto("A", 10, "10000");
        VentaRegistrada v = vender(producto, 3, "30000");
        SolicitudDevolucion s = conClave(UUID.randomUUID(), v.id(), List.of(new UnidadDevuelta(producto, 1)),
                enEfectivo("6000"), reembolso(caja.tarjetaId, "4000", "DATAFONO-1"));

        ResultadoDeDevolucion primera = devoluciones.procesar(s);
        ResultadoDeDevolucion repetida = devoluciones.procesar(s);

        assertFalse(primera.yaExistia());
        assertTrue(repetida.yaExistia());
        mismaRespuesta(primera.devolucion(), repetida.devolucion());
        assertEquals(1, caja.cuantas("devoluciones"));
        assertEquals(2, caja.cuantas("reembolsos"));
        assertEquals(8, caja.existencia(producto), "10 - 3 + 1, una sola vez");
        assertEquals(1, caja.movimientosDe(producto, "DEVOLUCION"));
        assertEquals("D-00000001", repetida.devolucion().consecutivo());
    }

    @Test
    void elReintentoDevuelveLoQueSeRespondioEntoncesAunqueLaVentaHayaCambiado() {
        Integer producto = caja.producto("A", 10, "10000");
        VentaRegistrada v = vender(producto, 3, "30000");
        SolicitudDevolucion primera = conClave(UUID.randomUUID(), v.id(), List.of(new UnidadDevuelta(producto, 1)),
                enEfectivo("10000"));
        DevolucionRegistrada original = devoluciones.procesar(primera).devolucion();
        anular(v.id(), enEfectivo("20000"));
        assertEquals("ANULADA", ventaGuardada(v.id()).get("estado"));

        ResultadoDeDevolucion repetida = devoluciones.procesar(primera);

        assertTrue(repetida.yaExistia());
        mismaRespuesta(original, repetida.devolucion());
        assertEquals("DEVUELTA_PARCIAL", repetida.devolucion().estadoVenta(), "el estado de entonces, no el de ahora");
        assertEquals(2, caja.cuantas("devoluciones"));
    }

    @Test
    void unaAnulacionReintentadaDevuelveLaMismaRespuestaEnVezDelConflicto() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada v = vender(producto, 1, "10000");
        SolicitudDevolucion s = conClave(UUID.randomUUID(), v.id(), List.of(), enEfectivo("10000"));
        DevolucionRegistrada original = devoluciones.procesar(s).devolucion();

        ResultadoDeDevolucion repetida = devoluciones.procesar(s);

        assertTrue(repetida.yaExistia());
        mismaRespuesta(original, repetida.devolucion());
        assertEquals(1, caja.cuantas("devoluciones"));
    }

    @Test
    void elReintentoNoPideNiElPinNiUnTurnoAbierto() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada v = vender(producto, 1, "10000");
        SolicitudDevolucion s = conClave(UUID.randomUUID(), v.id(), List.of(), enEfectivo("10000"));
        DevolucionRegistrada original = devoluciones.procesar(s).devolucion();
        caja.cerrarTurno(turnoDelAdministrador);
        caja.entrarComoVendedor();

        ResultadoDeDevolucion repetida = devoluciones.procesar(s);

        assertTrue(repetida.yaExistia(), "una devolución confirmada se devuelve pase lo que pase después");
        mismaRespuesta(original, repetida.devolucion());
    }

    @Test
    void laMismaClaveConOtraCargaEs409YNoRegistraNada() {
        Integer producto = caja.producto("A", 10, "10000");
        VentaRegistrada v = vender(producto, 3, "30000");
        UUID clave = UUID.randomUUID();
        devoluciones.procesar(conClave(clave, v.id(), List.of(new UnidadDevuelta(producto, 1)), enEfectivo("10000")));

        assertThrows(ClaveDeIdempotenciaEnUsoException.class, () -> devoluciones.procesar(
                conClave(clave, v.id(), List.of(new UnidadDevuelta(producto, 2)), enEfectivo("20000"))));

        assertEquals(1, caja.cuantas("devoluciones"));
        assertEquals(8, caja.existencia(producto));
    }

    @Test
    void laMismaClaveEnOtraVentaEs409() {
        Integer producto = caja.producto("A", 10, "10000");
        VentaRegistrada una = vender(producto, 1, "10000");
        VentaRegistrada otra = vender(producto, 1, "10000");
        UUID clave = UUID.randomUUID();
        devoluciones.procesar(conClave(clave, una.id(), List.of(), enEfectivo("10000")));

        assertThrows(ClaveDeIdempotenciaEnUsoException.class,
                () -> devoluciones.procesar(conClave(clave, otra.id(), List.of(), enEfectivo("10000"))));

        assertEquals(1, caja.cuantas("devoluciones"));
        assertEquals("REGISTRADA", ventaGuardada(otra.id()).get("estado"));
    }

    // ------------------------------------------------------- lo pagado con saldo vuelve como saldo

    /** Una venta de dos unidades de 10 000: 10 000 con saldo a favor y 10 000 en efectivo. */
    private VentaRegistrada ventaPagadaMitadConSaldo(Integer producto) {
        darSaldo(caja.clienteId, producto, "10000", "10000");
        return vender(caja.clienteId, producto, 2, saldo("10000"), efectivo("10000"));
    }

    @Test
    void loPagadoConSaldoNoSePuedeReembolsarEnEfectivo() {
        Integer producto = caja.producto("A", 10, "10000");
        VentaRegistrada v = ventaPagadaMitadConSaldo(producto);
        int devolucionesAntes = caja.cuantas("devoluciones");

        VentaInvalidaException e = assertThrows(VentaInvalidaException.class, () -> anular(v.id(), enEfectivo("20000")));
        assertThrows(VentaInvalidaException.class, () -> anular(v.id(), enEfectivo("15000"), enSaldo("5000")));
        assertThrows(VentaInvalidaException.class, () -> anular(v.id(), reembolso(caja.tarjetaId, "20000", "REF")),
                "tampoco por tarjeta o transferencia: no es saldo");

        assertTrue(e.getMessage().contains("saldo a favor"), e.getMessage());
        assertEquals(devolucionesAntes, caja.cuantas("devoluciones"));
        igual("0", saldoGuardado(caja.clienteId));
    }

    @Test
    void loPagadoConSaldoSeReembolsaComoSaldoYLoPagadoEnEfectivoVuelveComoSeQuiera() {
        Integer producto = caja.producto("A", 10, "10000");
        VentaRegistrada v = ventaPagadaMitadConSaldo(producto);

        DevolucionRegistrada r = anular(v.id(), enEfectivo("10000"), enSaldo("10000"));

        igual("20000", r.reembolsado());
        igual("10000", saldoGuardado(caja.clienteId));
    }

    @Test
    void todoSePuedeReembolsarComoSaldoAunqueSeHayaPagadoEnEfectivo() {
        Integer producto = caja.producto("A", 10, "10000");
        VentaRegistrada v = vender(producto, 2, "20000");

        anular(v.id(), enSaldo("20000"));

        igual("20000", saldoGuardado(caja.clienteId));
    }

    @Test
    void loQueSePagoConSaldoSeCuentaAcumuladoEntreDevolucionesParciales() {
        Integer producto = caja.producto("A", 10, "10000");
        VentaRegistrada v = ventaPagadaMitadConSaldo(producto);
        List<UnidadDevuelta> una = List.of(new UnidadDevuelta(producto, 1));

        devolver(v.id(), una, enEfectivo("10000"));
        assertThrows(VentaInvalidaException.class, () -> devolver(v.id(), una, enEfectivo("10000")),
                "ya se reembolsaron en efectivo los 10 000 que se pagaron en efectivo");
        DevolucionRegistrada segunda = devolver(v.id(), una, enSaldo("10000"));

        assertEquals("ANULADA", segunda.estadoVenta());
        igual("10000", saldoGuardado(caja.clienteId));
        assertEquals(2, caja.cuantas("devoluciones") - 1, "las dos de esta venta y la que dio el saldo");
    }

    @Test
    void loPagadoTodoConSaldoSoloVuelveComoSaldo() {
        Integer producto = caja.producto("A", 10, "10000");
        darSaldo(caja.clienteId, producto, "10000", "10000");
        VentaRegistrada v = vender(caja.clienteId, producto, 1, saldo("10000"));

        assertThrows(VentaInvalidaException.class, () -> anular(v.id(), enEfectivo("10000")));
        anular(v.id(), enSaldo("10000"));

        igual("10000", saldoGuardado(caja.clienteId));
    }

    // ------------------------------------------------------- lo que DEVUELTA_PARCIAL sigue siendo

    private static final Domicilio ENTREGA = new Domicilio("Calle 1 # 2-3", "Cali", "3001112233", "Ana Pérez", false);

    private VentaRegistrada domicilioPagado(Integer producto, int cantidad, String monto) {
        return ventas.registrar(new SolicitudVenta(UUID.randomUUID(), caja.clienteId,
                List.of(new LineaSolicitada(producto, null, cantidad, null, null)), null, List.of(efectivo(monto)), null,
                null, ENTREGA));
    }

    @Test
    void unDomicilioDevueltoEnParteSigueEnLaListaPorDespacharYSePuedeEntregar() {
        Integer producto = caja.producto("A", 10, "10000");
        VentaRegistrada v = domicilioPagado(producto, 2, "20000");
        devolver(v.id(), List.of(new UnidadDevuelta(producto, 1)), enEfectivo("10000"));
        assertEquals("DEVUELTA_PARCIAL", ventaGuardada(v.id()).get("estado"));

        DomicilioPendiente fila = domicilios.listar("POR_DESPACHAR").stream()
                .filter(d -> d.ventaId().equals(v.id())).findFirst().orElseThrow();
        assertEquals(caja.clienteId.longValue(), fila.clienteId());
        assertFalse(fila.entregado());
        domicilios.entregar(v.id());

        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM domicilios_venta WHERE venta_id = :v AND entregado_en IS NOT NULL")
                .param("v", v.id()).query(Integer.class).single(), "quedó marcado como entregado");
    }

    @Test
    void unDomicilioAnuladoSaleDeLaListaPorDespacharYNoSeEntrega() {
        Integer producto = caja.producto("A", 10, "10000");
        VentaRegistrada v = domicilioPagado(producto, 1, "10000");
        anular(v.id(), enEfectivo("10000"));

        assertTrue(domicilios.listar("POR_DESPACHAR").stream().noneMatch(d -> d.ventaId().equals(v.id())));
        assertThrows(EstadoNoAdmiteLaOperacionException.class, () -> domicilios.entregar(v.id()));
    }

    @Test
    void laListaPorCobrarDiceSiYaSeEntregoYDeQuienEs() {
        Integer producto = caja.producto("A", 10, "3000");
        VentaRegistrada sinEntregar = contraEntrega(producto, 1);
        VentaRegistrada entregada = contraEntrega(producto, 1);
        domicilios.entregar(entregada.id());

        List<DomicilioPendiente> porCobrar = domicilios.listar("POR_COBRAR");

        DomicilioPendiente a = porCobrar.stream().filter(d -> d.ventaId().equals(sinEntregar.id())).findFirst().orElseThrow();
        DomicilioPendiente b = porCobrar.stream().filter(d -> d.ventaId().equals(entregada.id())).findFirst().orElseThrow();
        assertFalse(a.entregado());
        assertTrue(b.entregado(), "la que ya se entregó sigue por cobrar y lo dice");
        assertEquals(caja.clienteId.longValue(), a.clienteId());
        assertEquals(caja.clienteId.longValue(), b.clienteId());
    }

    // ------------------------------------------------------- los encargos de una venta devuelta

    /** Dos líneas: A con existencia y B como encargo (existencia cero). Devuelve la venta y el ítem de B. */
    private Long[] ventaConUnEncargo(Integer enExistencia, Integer encargo) {
        VentaRegistrada v = ventas.registrar(new SolicitudVenta(UUID.randomUUID(), caja.clienteId,
                List.of(new LineaSolicitada(enExistencia, null, 1, null, null),
                        new LineaSolicitada(encargo, null, 1, null, LocalDate.now().plusDays(7))), null,
                List.of(efectivo("20000")), "No había existencia", null, null));
        Long item = jdbc.sql("SELECT item_id FROM venta_items WHERE venta_id = :v AND producto_id = :p")
                .param("v", v.id()).param("p", encargo).query(Long.class).single();
        return new Long[]{v.id(), item};
    }

    private boolean estaEnLasListasDeEncargos(Long item) {
        return java.util.stream.Stream.of("PENDIENTE", "LLEGO").flatMap(e -> encargos.listar(e).stream())
                .anyMatch(e -> e.itemId().equals(item));
    }

    @Test
    void elEncargoDeUnaVentaDevueltaEnParteSigueEnLaListaYSePuedeEntregar() {
        Integer a = caja.producto("A", 5, "10000");
        Integer b = caja.producto("B", 0, "10000");
        Long[] venta = ventaConUnEncargo(a, b);
        devolver(venta[0], List.of(new UnidadDevuelta(a, 1)), enEfectivo("10000"));
        assertEquals("DEVUELTA_PARCIAL", ventaGuardada(venta[0]).get("estado"));

        assertTrue(estaEnLasListasDeEncargos(venta[1]), "el encargo no se devolvió y sigue pendiente");
        encargos.entregar(venta[1]);

        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM venta_items WHERE item_id = :i AND encargo_entregado_en IS NOT NULL")
                .param("i", venta[1]).query(Integer.class).single());
    }

    @Test
    void elEncargoDevueltoPorCompletoSaleDeLaListaYNoSeEntrega() {
        Integer a = caja.producto("A", 5, "10000");
        Integer b = caja.producto("B", 0, "10000");
        Long[] venta = ventaConUnEncargo(a, b);
        assertTrue(estaEnLasListasDeEncargos(venta[1]));

        devolver(venta[0], List.of(new UnidadDevuelta(b, 1)), enEfectivo("10000"));

        assertEquals("DEVUELTA_PARCIAL", ventaGuardada(venta[0]).get("estado"), "A sigue vendida");
        assertFalse(estaEnLasListasDeEncargos(venta[1]), "la línea del encargo se devolvió entera");
        assertThrows(EstadoNoAdmiteLaOperacionException.class, () -> encargos.entregar(venta[1]));
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM venta_items WHERE item_id = :i AND encargo_entregado_en IS NOT NULL")
                .param("i", venta[1]).query(Integer.class).single(), "no quedó marcada");
    }

    @Test
    void elEncargoDeUnaVentaAnuladaSaleDeLaLista() {
        Integer a = caja.producto("A", 5, "10000");
        Integer b = caja.producto("B", 0, "10000");
        Long[] venta = ventaConUnEncargo(a, b);

        anular(venta[0], enEfectivo("20000"));

        assertFalse(estaEnLasListasDeEncargos(venta[1]));
        assertThrows(EstadoNoAdmiteLaOperacionException.class, () -> encargos.entregar(venta[1]));
    }

    // ------------------------------------------------------- consultar una venta por su consecutivo

    @Test
    void unaVentaSeConsultaPorSuConsecutivoConSusLineasYSusPagos() {
        Integer a = caja.producto("A", 10, "10000");
        Integer b = caja.producto("B", 10, "5000");
        VentaRegistrada v = ventas.registrar(new SolicitudVenta(UUID.randomUUID(), caja.clienteId,
                List.of(new LineaSolicitada(a, null, 2, null, null), new LineaSolicitada(b, null, 1, null, null)), null,
                List.of(efectivo("15000"), new PagoSolicitado(caja.tarjetaId, new BigDecimal("10000"), null, "REF-1")),
                null, null, null));
        caja.entrarComoVendedor();

        VentaConsultada c = consulta.porConsecutivo(v.consecutivo());

        assertEquals(v.id(), c.id());
        assertEquals(v.consecutivo(), c.consecutivo());
        assertEquals("REGISTRADA", c.estado());
        assertEquals("PAGADA", c.estadoPago());
        igual("25000", c.total());
        assertEquals(caja.clienteId, c.cliente().id());
        assertEquals("cliente " + caja.empresa.marca.toLowerCase(), c.cliente().nombre().toLowerCase());
        assertFalse(c.cliente().consumidorFinal());
        assertEquals(2, c.lineas().size());
        VentaConsultada.Linea la = c.lineas().get(0).productoId().equals(a) ? c.lineas().get(0) : c.lineas().get(1);
        assertEquals("Producto A", la.nombre());
        assertEquals(2, la.cantidad());
        assertEquals(0, la.cantidadDevuelta());
        igual("20000", la.total());
        igual("0", la.valorDevuelto());
        igual("0", c.reembolsadoSinSaldo());
        assertEquals(2, c.pagos().size());
        VentaConsultada.Pago tarjeta = c.pagos().stream().filter(p -> p.medioId().equals(caja.tarjetaId)).findFirst().orElseThrow();
        assertEquals("Tarjeta", tarjeta.medio());
        assertEquals(co.com.bspstore.sgip.modelo.TipoMedio.TARJETA, tarjeta.tipo());
        igual("10000", tarjeta.monto());
    }

    @Test
    void laVentaDelConsumidorFinalSeConsultaConSuMarca() {
        Integer a = caja.producto("A", 10, "10000");
        VentaRegistrada v = vender(null, a, 1, efectivo("10000"));

        VentaConsultada c = consulta.porConsecutivo(v.consecutivo());

        assertTrue(c.cliente().consumidorFinal());
        assertEquals(caja.consumidorFinalId, c.cliente().id());
    }

    @Test
    void unaVentaConDevolucionPreviaDiceCuantoSeDevolvioDeCadaLinea() {
        Integer a = caja.producto("A", 10, "10000");
        VentaRegistrada v = vender(a, 3, "30000");
        devolver(v.id(), List.of(new UnidadDevuelta(a, 2)), enEfectivo("20000"));

        VentaConsultada c = consulta.porConsecutivo(v.consecutivo());

        assertEquals("DEVUELTA_PARCIAL", c.estado());
        assertEquals(1, c.lineas().size());
        assertEquals(3, c.lineas().get(0).cantidad());
        assertEquals(2, c.lineas().get(0).cantidadDevuelta());
        igual("30000", c.lineas().get(0).total());
        igual("20000", c.lineas().get(0).valorDevuelto());
        igual("20000", c.reembolsadoSinSaldo());
    }

    @Test
    void reembolsadoSinSaldoSumaLoReembolsadoPorMediosQueNoSonElSaldoAcumulado() {
        Integer a = caja.producto("A", 10, "10000");
        VentaRegistrada v = vender(a, 3, "30000");
        devolver(v.id(), List.of(new UnidadDevuelta(a, 1)), enEfectivo("4000"), enSaldo("6000"));
        devolver(v.id(), List.of(new UnidadDevuelta(a, 1)), reembolso(caja.tarjetaId, "5000", "REF"), enSaldo("5000"));

        VentaConsultada c = consulta.porConsecutivo(v.consecutivo());

        igual("9000", c.reembolsadoSinSaldo());
    }

    @Test
    void reembolsadoSinSaldoNoCuentaLoReembolsadoDeOtrasVentas() {
        Integer a = caja.producto("A", 10, "10000");
        VentaRegistrada una = vender(a, 1, "10000");
        VentaRegistrada otra = vender(a, 1, "10000");
        anular(una.id(), enEfectivo("10000"));

        igual("0", consulta.porConsecutivo(otra.consecutivo()).reembolsadoSinSaldo());
        igual("10000", consulta.porConsecutivo(una.consecutivo()).reembolsadoSinSaldo());
    }

    @Test
    void unConsecutivoQueNoExisteEs404() {
        assertThrows(RecursoNoEncontradoException.class, () -> consulta.porConsecutivo("V-99999999"));
        assertThrows(RecursoNoEncontradoException.class, () -> consulta.porConsecutivo("   "));
    }

    @Test
    void laVentaDeOtraEmpresaConElMismoConsecutivoNoSeVe() {
        CajaDePrueba ajena = CajaDePrueba.crear("Ajena", jdbc, plataforma, categorias, proveedores, clientes);
        try {
            Integer productoAjeno = ajena.producto("A", 5, "10000");
            ajena.abrirTurno(ajena.administradorId);
            ajena.entrarComoAdministrador();
            VentaRegistrada deLaAjena = ventas.registrar(new SolicitudVenta(UUID.randomUUID(), null,
                    List.of(new LineaSolicitada(productoAjeno, null, 1, null, null)), null,
                    List.of(new PagoSolicitado(ajena.efectivoId, new BigDecimal("10000"), null, null)), null, null, null));
            assertEquals("V-00000001", deLaAjena.consecutivo());
            caja.entrarComoAdministrador();

            assertThrows(RecursoNoEncontradoException.class, () -> consulta.porConsecutivo("V-00000001"),
                    "la empresa de la sesión no tiene ninguna venta: la V-00000001 es de la otra");
            Integer propio = caja.producto("A", 5, "10000");
            VentaRegistrada mia = vender(propio, 1, "10000");
            assertEquals("V-00000001", mia.consecutivo());
            assertEquals(mia.id(), consulta.porConsecutivo("V-00000001").id(), "cada empresa ve la suya");
        } finally {
            ajena.borrar();
        }
    }
}

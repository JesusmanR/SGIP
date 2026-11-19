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
import co.com.bspstore.sgip.api.dto.VentaRegistrada;
import co.com.bspstore.sgip.logica.AutorizacionRequeridaException;
import co.com.bspstore.sgip.logica.EstadoNoAdmiteLaOperacionException;
import co.com.bspstore.sgip.logica.RecursoNoEncontradoException;
import co.com.bspstore.sgip.logica.ServicioDevoluciones;
import co.com.bspstore.sgip.logica.ServicioDomicilios;
import co.com.bspstore.sgip.logica.ServicioSaldoAFavor;
import co.com.bspstore.sgip.logica.ServicioVentas;
import co.com.bspstore.sgip.logica.SinTurnoAbiertoException;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        return new SolicitudDevolucion(venta, unidades, "El cliente se arrepintió", reembolsos, null);
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

    /** Le deja saldo al cliente como lo hace el sistema: vendiéndole y devolviendo todo al saldo. */
    private void darSaldo(Integer cliente, Integer producto, String monto) {
        VentaRegistrada v = vender(cliente, producto, 1, efectivo(monto));
        anular(v.id(), enSaldo(monto));
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

        DevolucionRegistrada r = devoluciones.registrar(new SolicitudDevolucion(v.id(), List.of(), "Falló",
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
                    new SolicitudDevolucion(v.id(), List.of(), motivo, List.of(enEfectivo("10000")), null)));
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

        assertThrows(SinTurnoAbiertoException.class, () -> anular(v.id(), enEfectivo("10000")));

        nadaDevuelto(producto, 4);
        assertEquals("REGISTRADA", ventaGuardada(v.id()).get("estado"));
    }

    @Test
    void laDevolucionSeRegistraEnElTurnoDeQuienLaHaceNoEnElDeLaVenta() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada v = vender(producto, 1, "10000");
        assertEquals(turnoDelAdministrador, ventaGuardada(v.id()).get("cierre_id"));
        caja.entrarComoVendedor();

        DevolucionRegistrada r = devoluciones.registrar(new SolicitudDevolucion(v.id(), List.of(), "Falló",
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
        darSaldo(caja.clienteId, producto, "10000");
        igual("10000", saldoGuardado(caja.clienteId));

        VentaRegistrada v = vender(caja.clienteId, producto, 1, saldo("10000"));

        igual("10000", v.total());
        igual("0", saldoGuardado(caja.clienteId));
        assertEquals("PAGADA", ventaGuardada(v.id()).get("estado_pago"));
    }

    @Test
    void unaCompraPuedePagarseMitadConSaldoYMitadEnEfectivo() {
        Integer producto = caja.producto("A", 10, "10000");
        darSaldo(caja.clienteId, producto, "6000");
        VentaRegistrada v = vender(caja.clienteId, producto, 1, saldo("6000"), efectivo("4000"));

        igual("10000", v.total());
        igual("0", saldoGuardado(caja.clienteId));
        assertEquals(2, jdbc.sql("SELECT COUNT(*) FROM pagos_venta WHERE venta_id = :v")
                .param("v", v.id()).query(Integer.class).single());
    }

    @Test
    void noSePuedeUsarMasSaldoDelQueHay() {
        Integer producto = caja.producto("A", 10, "10000");
        darSaldo(caja.clienteId, producto, "4000");
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
        darSaldo(caja.clienteId, producto, "10000");

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
        darSaldo(caja.clienteId, producto, "10000");
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
        darSaldo(caja.clienteId, producto, "3000");
        VentaRegistrada v = contraEntrega(producto, 1);

        assertThrows(VentaInvalidaException.class, () -> domicilios.cobrar(v.id(), List.of(saldo("3000"))));

        igual("3000", saldoGuardado(caja.clienteId));
        assertEquals("PENDIENTE", ventaGuardada(v.id()).get("estado_pago"));
    }
}

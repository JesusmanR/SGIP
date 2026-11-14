package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.api.dto.SolicitudAutorizacion;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.api.dto.VentaRegistrada;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.logica.AutorizacionRequeridaException;
import co.com.bspstore.sgip.logica.ClaveDeIdempotenciaEnUsoException;
import co.com.bspstore.sgip.logica.ExistenciaInsuficienteException;
import co.com.bspstore.sgip.logica.RecursoNoEncontradoException;
import co.com.bspstore.sgip.logica.RegistradorDeVenta;
import co.com.bspstore.sgip.logica.ResultadoDeVenta;
import co.com.bspstore.sgip.logica.ServicioInventario;
import co.com.bspstore.sgip.logica.ServicioTurnos;
import co.com.bspstore.sgip.logica.ServicioVentas;
import co.com.bspstore.sgip.logica.SinTurnoAbiertoException;
import co.com.bspstore.sgip.logica.VentaInvalidaException;
import co.com.bspstore.sgip.logica.venta.Descuento;
import co.com.bspstore.sgip.logica.venta.TipoDescuento;
import co.com.bspstore.sgip.util.Hoy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La venta en el mostrador (RF-41, RF-42, RF-44, RF-36) contra el esquema real: lo que se guarda
 * y lo que NO queda cuando algo falla.
 *
 * Crea su empresa (con administrador, vendedor, medios de pago, productos y turno) y la borra al
 * terminar. La concurrencia está en {@code VentaConcurrenteConBaseTest}.
 */
@Tag("conBase")
@SpringBootTest
@AutoConfigureMockMvc
class VentaConBaseTest {

    @Autowired ServicioVentas ventas;
    @Autowired ServicioInventario inventario;
    @Autowired ServicioTurnos turnos;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioClientes clientes;
    @Autowired RepositorioVentas repositorioVentas;
    @Autowired RepositorioConsecutivos consecutivos;
    @Autowired PlatformTransactionManager transacciones;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @MockitoSpyBean RepositorioInventario kardex;

    private CajaDePrueba caja;
    private Long turnoDelAdministrador;
    private Long turnoDelVendedor;

    @BeforeEach
    void prepararDatosPropios() {
        caja = CajaDePrueba.crear("Venta", jdbc, plataforma, categorias, proveedores, clientes);
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

    private LineaSolicitada linea(Integer producto, int cantidad) {
        return new LineaSolicitada(producto, null, cantidad, null, null);
    }

    private PagoSolicitado efectivo(String monto) {
        return new PagoSolicitado(caja.efectivoId, new BigDecimal(monto), null, null);
    }

    private SolicitudVenta venta(List<LineaSolicitada> lineas, PagoSolicitado... pagos) {
        return new SolicitudVenta(UUID.randomUUID(), null, lineas, null, List.of(pagos), null, null, null);
    }

    private SolicitudVenta venta(LineaSolicitada linea, PagoSolicitado... pagos) {
        return venta(List.of(linea), pagos);
    }

    private SolicitudVenta con(SolicitudVenta base, UUID clave) {
        return new SolicitudVenta(clave, base.clienteId(), base.lineas(), base.descuentoTotal(), base.pagos(),
                base.motivoEncargo(), base.autorizacion(), base.domicilio());
    }

    private SolicitudVenta conEncargo(UUID clave, Integer producto, int cantidad, LocalDate entrega, String motivo,
                                      String pago, SolicitudAutorizacion autorizacion) {
        return new SolicitudVenta(clave, null, List.of(new LineaSolicitada(producto, null, cantidad, null, entrega)),
                null, List.of(efectivo(pago)), motivo, autorizacion, null);
    }

    private Map<String, Object> fila(String sql, Object... parametros) {
        var consulta = jdbc.sql(sql).param("e", caja.empresa.empresaId);
        for (int i = 0; i < parametros.length; i += 2) {
            consulta = consulta.param((String) parametros[i], parametros[i + 1]);
        }
        return consulta.query().singleRow();
    }

    private Map<String, Object> ventaGuardada(Long id) {
        return fila("SELECT * FROM ventas WHERE empresa_id = :e AND venta_id = :id", "id", id);
    }

    private List<Map<String, Object>> lineasGuardadas(Long id) {
        return jdbc.sql("SELECT * FROM venta_items WHERE empresa_id = :e AND venta_id = :id ORDER BY producto_id")
                .param("e", caja.empresa.empresaId).param("id", id).query().listOfRows();
    }

    private List<Map<String, Object>> pagosGuardados(Long id) {
        return jdbc.sql("SELECT * FROM pagos_venta WHERE empresa_id = :e AND venta_id = :id ORDER BY pago_id")
                .param("e", caja.empresa.empresaId).param("id", id).query().listOfRows();
    }

    private static BigDecimal dinero(Object valor) {
        return (BigDecimal) valor;
    }

    private static void igual(String esperado, Object obtenido) {
        assertEquals(0, new BigDecimal(esperado).compareTo((BigDecimal) obtenido),
                "esperaba " + esperado + " y fue " + obtenido);
    }

    private void nadaGuardado(Integer... productos) {
        assertEquals(0, caja.cuantas("ventas"), "ninguna venta");
        assertEquals(0, caja.cuantas("venta_items"), "ninguna línea");
        assertEquals(0, caja.cuantas("pagos_venta"), "ningún pago");
        assertEquals(0, caja.cuantas("consecutivos"), "ningún consecutivo consumido");
        for (Integer p : productos) {
            assertEquals(0, caja.movimientosDe(p, "EGRESO"), "ningún egreso en el kárdex");
        }
    }

    // ------------------------------------------------------- la venta simple

    @Test
    void ventaSimpleDescuentaExistenciaYDejaKardex() {
        Integer producto = caja.producto("A", 5, "10000");

        ResultadoDeVenta r = ventas.procesar(venta(linea(producto, 2), efectivo("20000")));

        assertFalse(r.yaExistia());
        assertEquals("V-00000001", r.venta().consecutivo());
        igual("20000", r.venta().total());
        igual("0", r.venta().cambio());
        assertEquals(3, caja.existencia(producto));
        Map<String, Object> egreso = fila("""
                SELECT * FROM movimientos_inventario WHERE empresa_id = :e AND producto_id = :p AND tipo = 'EGRESO'
                """, "p", producto);
        assertEquals(5, egreso.get("existencia_anterior"));
        assertEquals(3, egreso.get("existencia_posterior"));
        assertEquals(2, egreso.get("cantidad"));
        assertEquals("V-00000001", egreso.get("documento"), "el kárdex apunta al documento de la venta");
        assertEquals(caja.administradorId, egreso.get("usuario_id"));
        assertNull(egreso.get("autorizado_por"), "una venta normal no lleva autorizador");
        var cuadre = inventario.cuadre(producto).orElseThrow();
        assertTrue(cuadre.cuadra(), "RNF-10: la suma de los movimientos es la existencia");
        assertEquals(3, cuadre.sumaDeMovimientos());
    }

    @Test
    void laVentaGuardaSuTurnoSuClienteSuClaveYSuHuella() {
        Integer producto = caja.producto("A", 5, "10000");
        UUID clave = UUID.randomUUID();

        VentaRegistrada registrada = ventas.registrar(con(venta(linea(producto, 1), efectivo("10000")), clave));

        Map<String, Object> v = ventaGuardada(registrada.id());
        assertEquals(turnoDelAdministrador, v.get("cierre_id"));
        assertEquals(caja.consumidorFinalId, v.get("cliente_id"), "sin cliente es el Consumidor final de la empresa");
        assertEquals(caja.administradorId, v.get("usuario_id"));
        assertEquals(clave.toString(), v.get("clave_idempotencia"));
        assertEquals(64, ((String) v.get("huella_carga")).length());
        assertEquals("CONTADO", v.get("forma_pago"));
        assertEquals(0, v.get("plazo_dias"));
        assertEquals("PAGADA", v.get("estado_pago"));
        assertEquals("MOSTRADOR", v.get("tipo_entrega"));
        assertEquals(false, v.get("sin_existencia"));
        assertNull(v.get("autorizada_por"));
        igual("0", dinero(v.get("envio")));
        List<Map<String, Object>> pagos = pagosGuardados(registrada.id());
        assertEquals(1, pagos.size());
        assertEquals(turnoDelAdministrador, pagos.get(0).get("cierre_id"), "cada pago lleva el turno actual");
        assertEquals(caja.efectivoId, pagos.get(0).get("medio_id"));
        assertEquals(caja.administradorId, pagos.get(0).get("registrado_por"));
    }

    @Test
    void elTurnoEsElDelCajeroQueVende() {
        Integer producto = caja.producto("A", 5, "10000");
        caja.entrarComoVendedor();

        VentaRegistrada registrada = ventas.registrar(venta(linea(producto, 1), efectivo("10000")));

        assertEquals(turnoDelVendedor, ventaGuardada(registrada.id()).get("cierre_id"));
        assertEquals(caja.vendedorId, ventaGuardada(registrada.id()).get("usuario_id"));
    }

    @Test
    void laRespuestaLlevaLasLineasCalculadasSinCostoNiUtilidad() {
        Integer producto = caja.producto("A", 5, "10000");
        caja.responsableDeIva19();

        VentaRegistrada r = ventas.registrar(venta(linea(producto, 2), efectivo("23800")));

        assertEquals(1, r.lineas().size());
        var l = r.lineas().get(0);
        assertEquals(producto, l.productoId());
        assertEquals(2, l.cantidad());
        igual("10000", l.precioUnitario());
        igual("20000", l.subtotal());
        igual("0", l.descuento());
        igual("20000", l.base());
        igual("0.19", l.tarifaIva());
        assertEquals("GRAVADO", l.tratamientoIva());
        igual("3800", l.iva());
        igual("23800", l.total());
        igual("23800", r.total());
    }

    // ------------------------------------------------------- los importes guardados

    @Test
    void descuentoNoRompeElIvaGuardado() {
        Integer producto = caja.producto("A", 5, "10000");
        caja.responsableDeIva19();
        var conDescuento = new LineaSolicitada(producto, null, 1,
                new Descuento(TipoDescuento.PORCENTAJE, new BigDecimal("10")), null);

        VentaRegistrada r = ventas.registrar(venta(conDescuento, efectivo("10710")));

        Map<String, Object> item = lineasGuardadas(r.id()).get(0);
        igual("10000", dinero(item.get("precio_unitario")));
        igual("10000", dinero(item.get("subtotal")));
        igual("1000", dinero(item.get("descuento")));
        igual("1710", dinero(item.get("valor_iva")));
        igual("0.19", dinero(item.get("tarifa_iva")));
        assertEquals("GRAVADO", item.get("tratamiento_iva"));
        igual("8000", dinero(item.get("utilidad")));
        Map<String, Object> v = ventaGuardada(r.id());
        igual("10000", dinero(v.get("subtotal")));
        igual("1000", dinero(v.get("descuento")));
        igual("1710", dinero(v.get("iva")));
        igual("10710", dinero(v.get("total")));
        igual("10710", r.total());
        assertNull(v.get("descuento_tipo"), "no hubo descuento sobre toda la compra");
    }

    @Test
    void elDescuentoSobreTodaLaCompraSeGuardaComoSePidioYSeRepartePorLinea() {
        Integer a = caja.producto("A", 5, "10000");
        Integer b = caja.producto("B", 5, "30000");
        var solicitud = new SolicitudVenta(UUID.randomUUID(), null,
                List.of(linea(a, 1), linea(b, 1)), new Descuento(TipoDescuento.PORCENTAJE, new BigDecimal("10")),
                List.of(efectivo("36000")), null, null, null);

        VentaRegistrada r = ventas.registrar(solicitud);

        Map<String, Object> v = ventaGuardada(r.id());
        assertEquals("PORCENTAJE", v.get("descuento_tipo"));
        igual("10", dinero(v.get("descuento_valor")));
        igual("40000", dinero(v.get("subtotal")));
        igual("4000", dinero(v.get("descuento")));
        igual("36000", dinero(v.get("total")));
        List<Map<String, Object>> lineas = lineasGuardadas(r.id());
        igual("1000", dinero(lineas.get(0).get("descuento")));
        igual("3000", dinero(lineas.get(1).get("descuento")));
    }

    @Test
    void lasLineasGuardanElCostoDelServidorYLaUtilidadQueSeCalculaConElDescuento() {
        Integer producto = caja.producto("A", 5, "10000");

        VentaRegistrada r = ventas.registrar(venta(linea(producto, 2), efectivo("20000")));

        Map<String, Object> item = lineasGuardadas(r.id()).get(0);
        igual("1000", dinero(item.get("costo_unitario")));
        igual("18000", dinero(item.get("utilidad")));
        assertEquals(0, item.get("cantidad_encargo"));
        assertNull(item.get("entrega_estimada"));
    }

    @Test
    void lasLineasRepetidasSeFusionanEnUnaSolaYUnSoloEgreso() {
        Integer producto = caja.producto("A", 10, "10000");

        VentaRegistrada r = ventas.registrar(venta(List.of(linea(producto, 2), linea(producto, 3)), efectivo("50000")));

        List<Map<String, Object>> lineas = lineasGuardadas(r.id());
        assertEquals(1, lineas.size());
        assertEquals(5, lineas.get(0).get("cantidad"));
        assertEquals(1, r.lineas().size());
        assertEquals(1, caja.movimientosDe(producto, "EGRESO"));
        assertEquals(5, caja.existencia(producto));
    }

    @Test
    void lineasRepetidasConDescuentoDistintoEs400YNoGuardaNada() {
        Integer producto = caja.producto("A", 10, "10000");
        var con10 = new LineaSolicitada(producto, null, 2, new Descuento(TipoDescuento.PORCENTAJE, new BigDecimal("10")), null);
        var sin = linea(producto, 3);

        assertThrows(VentaInvalidaException.class, () -> ventas.procesar(venta(List.of(con10, sin), efectivo("50000"))));

        nadaGuardado(producto);
        assertEquals(10, caja.existencia(producto));
    }

    // ------------------------------------------------------- lo que no se puede vender

    @Test
    void productoNoVendibleEs400AunqueElClienteLoMande() {
        Integer sinPrecio = caja.productoSinPrecio("S", 5);
        Integer precioCero = caja.productoConFactor("C", 5, "0.01", "1.2000");
        Integer desbordado = caja.productoConFactor("D", 5, "999999999999.99", "1.9000");
        Integer inactivo = caja.producto("I", 5, "10000");
        caja.desactivarProducto(inactivo);

        for (Integer producto : List.of(sinPrecio, precioCero, desbordado, inactivo)) {
            VentaInvalidaException e = assertThrows(VentaInvalidaException.class,
                    () -> ventas.procesar(venta(linea(producto, 1), efectivo("10000"))), "producto " + producto);
            assertTrue(e.getMessage().contains("no se puede vender"), e.getMessage());
        }

        nadaGuardado(sinPrecio, precioCero, desbordado, inactivo);
        assertEquals(5, caja.existencia(sinPrecio));
    }

    @Test
    void unProductoDeOtraEmpresaOInexistenteEs404YNoGuardaNada() {
        CajaDePrueba ajena = CajaDePrueba.crear("Ajena", jdbc, plataforma, categorias, proveedores, clientes);
        try {
            Integer deOtra = ajena.producto("X", 5, "10000");
            caja.entrarComoAdministrador();

            assertThrows(RecursoNoEncontradoException.class,
                    () -> ventas.procesar(venta(linea(deOtra, 1), efectivo("10000"))));
            assertThrows(RecursoNoEncontradoException.class,
                    () -> ventas.procesar(venta(linea(2_000_000_000, 1), efectivo("10000"))));

            nadaGuardado();
            assertEquals(5, ajena.existencia(deOtra), "el producto ajeno no se tocó");
        } finally {
            ajena.borrar();
        }
    }

    @Test
    void unClienteDeOtraEmpresaOInexistenteEs404YElPropioQuedaEnLaVenta() {
        CajaDePrueba ajena = CajaDePrueba.crear("Ajena", jdbc, plataforma, categorias, proveedores, clientes);
        try {
            Integer producto = caja.producto("A", 5, "10000");
            caja.entrarComoAdministrador();
            var deOtra = new SolicitudVenta(UUID.randomUUID(), ajena.clienteId, List.of(linea(producto, 1)), null,
                    List.of(efectivo("10000")), null, null, null);
            var inexistente = new SolicitudVenta(UUID.randomUUID(), 2_000_000_000, List.of(linea(producto, 1)), null,
                    List.of(efectivo("10000")), null, null, null);
            var propio = new SolicitudVenta(UUID.randomUUID(), caja.clienteId, List.of(linea(producto, 1)), null,
                    List.of(efectivo("10000")), null, null, null);

            assertThrows(RecursoNoEncontradoException.class, () -> ventas.procesar(deOtra));
            assertThrows(RecursoNoEncontradoException.class, () -> ventas.procesar(inexistente));
            nadaGuardado(producto);

            VentaRegistrada r = ventas.registrar(propio);
            assertEquals(caja.clienteId, ventaGuardada(r.id()).get("cliente_id"));
        } finally {
            ajena.borrar();
        }
    }

    // ------------------------------------------------------- los pagos

    @Test
    void pagoMixtoDebeSumarElTotal() {
        Integer producto = caja.producto("A", 5, "10000");
        var tarjeta = new PagoSolicitado(caja.tarjetaId, new BigDecimal("4000"), null, "AUT-123");

        VentaRegistrada r = ventas.registrar(venta(linea(producto, 1), efectivo("6000"), tarjeta));

        List<Map<String, Object>> pagos = pagosGuardados(r.id());
        assertEquals(2, pagos.size());
        igual("6000", dinero(pagos.get(0).get("monto")));
        igual("4000", dinero(pagos.get(1).get("monto")));
        assertEquals("AUT-123", pagos.get(1).get("referencia"));
        assertNull(pagos.get(0).get("referencia"));
    }

    @Test
    void pagosQueNoSumanElTotalEs400YNoGuardaNada() {
        Integer producto = caja.producto("A", 5, "10000");
        var tarjeta = new PagoSolicitado(caja.tarjetaId, new BigDecimal("3999"), null, "AUT-123");

        VentaInvalidaException e = assertThrows(VentaInvalidaException.class,
                () -> ventas.procesar(venta(linea(producto, 1), efectivo("6000"), tarjeta)));
        assertThrows(VentaInvalidaException.class, () -> ventas.procesar(venta(linea(producto, 1), efectivo("10001"))));
        assertThrows(VentaInvalidaException.class, () -> ventas.procesar(venta(linea(producto, 1), efectivo("9999"))));

        assertEquals("La suma de los pagos debe ser igual al total de la venta.", e.getMessage());
        nadaGuardado(producto);
        assertEquals(5, caja.existencia(producto));
    }

    @Test
    void efectivoCalculaElCambioYLoGuardaComoRecibido() {
        Integer producto = caja.producto("A", 5, "10000");
        caja.responsableDeIva19();
        var pago = new PagoSolicitado(caja.efectivoId, new BigDecimal("11900"), new BigDecimal("20000"), null);

        VentaRegistrada r = ventas.registrar(venta(linea(producto, 1), pago));

        igual("11900", r.total());
        igual("8100", r.cambio());
        igual("20000", dinero(pagosGuardados(r.id()).get(0).get("recibido")));
        igual("11900", dinero(pagosGuardados(r.id()).get(0).get("monto")));
    }

    @Test
    void efectivoSinRecibidoSeTomaPorExactoYElRecibidoMenorQueElMontoEs400() {
        Integer producto = caja.producto("A", 5, "10000");
        var exacto = efectivo("10000");
        var corto = new PagoSolicitado(caja.efectivoId, new BigDecimal("10000"), new BigDecimal("9999"), null);

        VentaRegistrada r = ventas.registrar(venta(linea(producto, 1), exacto));
        igual("0", r.cambio());
        assertThrows(co.com.bspstore.sgip.logica.EfectivoRecibidoInvalidoException.class,
                () -> ventas.procesar(venta(linea(producto, 1), corto)));

        assertEquals(1, caja.cuantas("ventas"), "solo la primera");
    }

    @Test
    void tarjetaYTransferenciaExigenReferencia() {
        Integer producto = caja.producto("A", 5, "10000");

        for (Integer medio : List.of(caja.tarjetaId, caja.transferenciaId)) {
            for (String referencia : new String[]{null, "", "   "}) {
                var pago = new PagoSolicitado(medio, new BigDecimal("10000"), null, referencia);
                assertThrows(VentaInvalidaException.class, () -> ventas.procesar(venta(linea(producto, 1), pago)));
            }
            var conReferencia = new PagoSolicitado(medio, new BigDecimal("10000"), null, "  REF-9  ");
            VentaRegistrada r = ventas.registrar(venta(linea(producto, 1), conReferencia));
            assertEquals("REF-9", pagosGuardados(r.id()).get(0).get("referencia"), "se guarda sin espacios sobrantes");
        }
    }

    @Test
    void elEfectivoRecibidoSoloSeAdmiteEnEfectivo() {
        Integer producto = caja.producto("A", 5, "10000");
        var tarjeta = new PagoSolicitado(caja.tarjetaId, new BigDecimal("10000"), new BigDecimal("10000"), "AUT-1");

        assertThrows(VentaInvalidaException.class, () -> ventas.procesar(venta(linea(producto, 1), tarjeta)));
        nadaGuardado(producto);
    }

    @Test
    void unMedioInactivoOAjenoOInexistenteEs400() {
        CajaDePrueba ajena = CajaDePrueba.crear("Ajena", jdbc, plataforma, categorias, proveedores, clientes);
        try {
            Integer producto = caja.producto("A", 5, "10000");
            caja.entrarComoAdministrador();
            for (Integer medio : List.of(caja.inactivoId, ajena.efectivoId, 2_000_000_000)) {
                var pago = new PagoSolicitado(medio, new BigDecimal("10000"), null, "REF");
                VentaInvalidaException e = assertThrows(VentaInvalidaException.class,
                        () -> ventas.procesar(venta(linea(producto, 1), pago)), "medio " + medio);
                assertEquals("El medio de pago no está disponible.", e.getMessage());
            }
            nadaGuardado(producto);
        } finally {
            ajena.borrar();
        }
    }

    @Test
    void elSaldoAFavorAunNoEstaDisponible() {
        Integer producto = caja.producto("A", 5, "10000");
        var pago = new PagoSolicitado(caja.saldoAFavorId, new BigDecimal("10000"), null, null);

        VentaInvalidaException e = assertThrows(VentaInvalidaException.class,
                () -> ventas.procesar(venta(linea(producto, 1), pago)));

        assertEquals("El saldo a favor aún no está disponible", e.getMessage());
        nadaGuardado(producto);
    }

    // ------------------------------------------------------- el turno

    @Test
    void sinTurnoAbiertoEs409YNoGuardaNada() {
        Integer producto = caja.producto("A", 5, "10000");
        caja.cerrarTurno(turnoDelAdministrador);

        assertThrows(SinTurnoAbiertoException.class, () -> ventas.procesar(venta(linea(producto, 1), efectivo("10000"))));

        nadaGuardado(producto);
        assertEquals(5, caja.existencia(producto));
    }

    // ------------------------------------------------------- las autorizaciones

    @Test
    void descuentoSinPinEs403YNoGuardaNada() {
        Integer producto = caja.producto("A", 5, "10000");
        caja.entrarComoVendedor();
        var conDescuento = new LineaSolicitada(producto, null, 1,
                new Descuento(TipoDescuento.PORCENTAJE, new BigDecimal("10")), null);

        assertThrows(AutorizacionRequeridaException.class,
                () -> ventas.procesar(venta(conDescuento, efectivo("9000"))));
        var sobreTodo = new SolicitudVenta(UUID.randomUUID(), null, List.of(linea(producto, 1)),
                new Descuento(TipoDescuento.PESOS, new BigDecimal("500")), List.of(efectivo("9500")), null, null, null);
        assertThrows(AutorizacionRequeridaException.class, () -> ventas.procesar(sobreTodo));

        nadaGuardado(producto);
        assertEquals(5, caja.existencia(producto));
    }

    @Test
    void camposDeEncargoSinPinEs403YNoGuardaNada() {
        Integer producto = caja.producto("A", 1, "10000");
        caja.entrarComoVendedor();
        LocalDate entrega = Hoy.de(Clock.systemUTC()).plusDays(5);

        assertThrows(AutorizacionRequeridaException.class, () -> ventas.procesar(
                conEncargo(UUID.randomUUID(), producto, 3, entrega, "Llega el viernes", "30000", null)));
        assertThrows(AutorizacionRequeridaException.class, () -> ventas.procesar(
                conEncargo(UUID.randomUUID(), producto, 1, null, "Solo el motivo", "10000", null)));

        nadaGuardado(producto);
        assertEquals(1, caja.existencia(producto));
    }

    @Test
    void conElPinDelAdministradorElVendedorDaUnDescuentoYQuedaQuienLoAutorizo() {
        Integer producto = caja.producto("A", 5, "10000");
        caja.entrarComoVendedor();
        var conDescuento = new LineaSolicitada(producto, null, 1,
                new Descuento(TipoDescuento.PESOS, new BigDecimal("1000")), null);
        var solicitud = new SolicitudVenta(UUID.randomUUID(), null, List.of(conDescuento), null,
                List.of(efectivo("9000")), null, new SolicitudAutorizacion(caja.administradorId, CajaDePrueba.PIN), null);

        VentaRegistrada r = ventas.registrar(solicitud);

        Map<String, Object> v = ventaGuardada(r.id());
        assertEquals(caja.administradorId, v.get("autorizada_por"));
        assertEquals(false, v.get("sin_existencia"), "un descuento autorizado no es una venta sin existencia");
        assertEquals(false, v.get("autorizacion_autoconcedida"));
        igual("1000", dinero(v.get("descuento")));
    }

    @Test
    void elAdministradorNoNecesitaPinYSuAutorizacionEsAutoconcedida() {
        Integer producto = caja.producto("A", 5, "10000");
        var conDescuento = new LineaSolicitada(producto, null, 1,
                new Descuento(TipoDescuento.PORCENTAJE, new BigDecimal("10")), null);

        VentaRegistrada r = ventas.registrar(venta(conDescuento, efectivo("9000")));

        Map<String, Object> v = ventaGuardada(r.id());
        assertEquals(caja.administradorId, v.get("autorizada_por"));
        assertEquals(true, v.get("autorizacion_autoconcedida"), "se autorizó a sí mismo");
    }

    @Test
    void unaVentaSinNadaQueAutorizarNoGuardaAutorizador() {
        Integer producto = caja.producto("A", 5, "10000");
        caja.entrarComoVendedor();

        VentaRegistrada r = ventas.registrar(venta(linea(producto, 1), efectivo("10000")));

        assertNull(ventaGuardada(r.id()).get("autorizada_por"));
        assertEquals(false, ventaGuardada(r.id()).get("autorizacion_autoconcedida"));
    }

    // ------------------------------------------------------- la existencia y el encargo

    @Test
    void faltaDeExistenciaSinPedirEncargoEs409AunqueElQueVendaSeaUnVendedor() {
        Integer producto = caja.producto("A", 3, "10000");
        caja.entrarComoVendedor();

        ExistenciaInsuficienteException e = assertThrows(ExistenciaInsuficienteException.class,
                () -> ventas.procesar(venta(linea(producto, 5), efectivo("50000"))));

        assertEquals("No hay existencia suficiente de Producto A.", e.getMessage());
        nadaGuardado(producto);
        assertEquals(3, caja.existencia(producto));
    }

    @Test
    void elAdministradorTampocoVendeSinExistenciaSiNoPideElEncargo() {
        Integer producto = caja.producto("A", 3, "10000");

        assertThrows(ExistenciaInsuficienteException.class,
                () -> ventas.procesar(venta(linea(producto, 4), efectivo("40000"))));

        nadaGuardado(producto);
    }

    @Test
    void encargoParcial() {
        Integer producto = caja.producto("A", 3, "10000");
        caja.entrarComoVendedor();
        LocalDate entrega = LocalDate.now().plusDays(7);
        var autorizacion = new SolicitudAutorizacion(caja.administradorId, CajaDePrueba.PIN);

        VentaRegistrada r = ventas.registrar(conEncargo(UUID.randomUUID(), producto, 5, entrega,
                "Llega el viernes", "50000", autorizacion));

        assertEquals(-2, caja.existencia(producto), "se venden las 3 que hay y las 2 que faltan quedan debiéndose");
        Map<String, Object> item = lineasGuardadas(r.id()).get(0);
        assertEquals(5, item.get("cantidad"));
        assertEquals(2, item.get("cantidad_encargo"));
        assertEquals(java.sql.Date.valueOf(entrega).toString(), String.valueOf(item.get("entrega_estimada")));
        igual("50000", dinero(item.get("subtotal")));
        Map<String, Object> v = ventaGuardada(r.id());
        assertEquals(true, v.get("sin_existencia"));
        assertEquals(true, v.get("reposicion_pendiente"));
        assertEquals(caja.administradorId, v.get("autorizada_por"));
        assertEquals("Llega el viernes", v.get("motivo_sin_existencia"));
        assertEquals(false, v.get("autorizacion_autoconcedida"));
        assertTrue(inventario.cuadre(producto).orElseThrow().cuadra());
    }

    @Test
    void encargoConExistenciaNegativaEsTodoEncargo() {
        Integer producto = caja.producto("A", -2, "10000");
        caja.entrarComoVendedor();
        var autorizacion = new SolicitudAutorizacion(caja.administradorId, CajaDePrueba.PIN);

        VentaRegistrada r = ventas.registrar(conEncargo(UUID.randomUUID(), producto, 3, LocalDate.now().plusDays(7),
                "Llega el viernes", "30000", autorizacion));

        Map<String, Object> item = lineasGuardadas(r.id()).get(0);
        assertEquals(3, item.get("cantidad"));
        assertEquals(3, item.get("cantidad_encargo"), "con la existencia en negativo nada se puede entregar");
        assertEquals(-5, caja.existencia(producto));
    }

    @Test
    void encargoConExistenciaCeroEsTodoEncargo() {
        Integer producto = caja.producto("A", 0, "10000");
        caja.entrarComoAdministrador();

        VentaRegistrada r = ventas.registrar(conEncargo(UUID.randomUUID(), producto, 2, LocalDate.now().plusDays(7),
                "Llega el lunes", "20000", null));

        assertEquals(2, lineasGuardadas(r.id()).get(0).get("cantidad_encargo"));
        assertEquals(-2, caja.existencia(producto));
    }

    @Test
    void encargoSinMotivoOFechaEs400() {
        Integer producto = caja.producto("A", 1, "10000");
        var autorizacion = new SolicitudAutorizacion(caja.administradorId, CajaDePrueba.PIN);
        caja.entrarComoVendedor();
        LocalDate entrega = LocalDate.now().plusDays(7);

        assertThrows(VentaInvalidaException.class, () -> ventas.procesar(
                conEncargo(UUID.randomUUID(), producto, 3, entrega, null, "30000", autorizacion)),
                "sin motivo");
        assertThrows(VentaInvalidaException.class, () -> ventas.procesar(
                conEncargo(UUID.randomUUID(), producto, 3, entrega, "   ", "30000", autorizacion)),
                "motivo en blanco");
        assertThrows(VentaInvalidaException.class, () -> ventas.procesar(
                conEncargo(UUID.randomUUID(), producto, 3, null, "Llega el viernes", "30000", autorizacion)),
                "sin fecha de entrega");

        nadaGuardado(producto);
        assertEquals(1, caja.existencia(producto));
    }

    @Test
    void siHayExistenciaDeSobraElEncargoPedidoNoMarcaLaVenta() {
        Integer producto = caja.producto("A", 10, "10000");
        caja.entrarComoAdministrador();

        VentaRegistrada r = ventas.registrar(conEncargo(UUID.randomUUID(), producto, 2, LocalDate.now().plusDays(7),
                "Llega el viernes", "20000", null));

        assertEquals(0, lineasGuardadas(r.id()).get(0).get("cantidad_encargo"));
        assertNull(lineasGuardadas(r.id()).get(0).get("entrega_estimada"));
        assertEquals(false, ventaGuardada(r.id()).get("sin_existencia"));
        assertEquals(8, caja.existencia(producto));
    }

    @Test
    void elKardexGuardaQuienAutorizo() {
        Integer conEncargo = caja.producto("A", 1, "10000");
        Integer normal = caja.producto("B", 5, "10000");
        caja.entrarComoVendedor();
        var autorizacion = new SolicitudAutorizacion(caja.administradorId, CajaDePrueba.PIN);

        ventas.registrar(conEncargo(UUID.randomUUID(), conEncargo, 3, LocalDate.now().plusDays(7),
                "Llega el viernes", "30000", autorizacion));
        ventas.registrar(venta(linea(normal, 1), efectivo("10000")));

        assertEquals(caja.administradorId, fila("""
                SELECT autorizado_por FROM movimientos_inventario
                 WHERE empresa_id = :e AND producto_id = :p AND tipo = 'EGRESO'
                """, "p", conEncargo).get("autorizado_por"));
        assertNull(fila("""
                SELECT autorizado_por FROM movimientos_inventario
                 WHERE empresa_id = :e AND producto_id = :p AND tipo = 'EGRESO'
                """, "p", normal).get("autorizado_por"));
    }

    @Test
    void elTopeDeExistenciaEs409YNoUnErrorDeLaBase() {
        Integer producto = caja.producto("A", -9990, "10000");
        caja.entrarComoAdministrador();

        ExistenciaInsuficienteException e = assertThrows(ExistenciaInsuficienteException.class,
                () -> ventas.procesar(conEncargo(UUID.randomUUID(), producto, 20, LocalDate.now().plusDays(7),
                        "Llega el viernes", "200000", null)));

        assertTrue(e.getMessage().contains("límite"), e.getMessage());
        nadaGuardado(producto);
        assertEquals(-9990, caja.existencia(producto));
        // Justo en el piso sí cabe.
        ventas.registrar(conEncargo(UUID.randomUUID(), producto, 9, LocalDate.now().plusDays(7),
                "Llega el viernes", "90000", null));
        assertEquals(-9999, caja.existencia(producto));
    }

    // ------------------------------------------------------- el consecutivo

    @Test
    void elConsecutivoTieneElFormatoPorEmpresa() {
        Integer producto = caja.producto("A", 10, "10000");
        CajaDePrueba otra = CajaDePrueba.crear("Otra", jdbc, plataforma, categorias, proveedores, clientes);
        try {
            Integer deOtra = otra.producto("A", 10, "10000");
            otra.abrirTurno(otra.administradorId);

            caja.entrarComoAdministrador();
            assertEquals("V-00000001", ventas.registrar(venta(linea(producto, 1), efectivo("10000"))).consecutivo());
            assertEquals("V-00000002", ventas.registrar(venta(linea(producto, 1), efectivo("10000"))).consecutivo());
            otra.entrarComoAdministrador();
            assertEquals("V-00000001", ventas.registrar(venta(linea(deOtra, 1),
                    new PagoSolicitado(otra.efectivoId, new BigDecimal("10000"), null, null))).consecutivo(),
                    "otra empresa también empieza en uno");
        } finally {
            otra.borrar();
        }
    }

    // ------------------------------------------------------- la idempotencia

    @Test
    void mismaClaveDosVecesUnaSolaVenta() {
        Integer producto = caja.producto("A", 5, "10000");
        SolicitudVenta solicitud = venta(linea(producto, 2), efectivo("20000"));

        ResultadoDeVenta primera = ventas.procesar(solicitud);
        ResultadoDeVenta segunda = ventas.procesar(solicitud);

        assertFalse(primera.yaExistia());
        assertTrue(segunda.yaExistia());
        assertEquals(primera.venta().id(), segunda.venta().id());
        assertEquals(primera.venta(), segunda.venta(), "la misma respuesta, línea por línea");
        assertEquals(1, caja.cuantas("ventas"));
        assertEquals(1, caja.movimientosDe(producto, "EGRESO"));
        assertEquals(3, caja.existencia(producto), "el descuento se hizo una sola vez");
        assertEquals("V-00000002", ventas.registrar(venta(linea(producto, 1), efectivo("10000"))).consecutivo(),
                "el reintento no consumió un número");
    }

    @Test
    void elReintentoDevuelveElCambioYLasLineasDeLaVentaOriginal() {
        Integer producto = caja.producto("A", 5, "10000");
        caja.responsableDeIva19();
        var pago = new PagoSolicitado(caja.efectivoId, new BigDecimal("11900"), new BigDecimal("20000"), null);
        SolicitudVenta solicitud = venta(linea(producto, 1), pago);

        VentaRegistrada original = ventas.registrar(solicitud);
        VentaRegistrada reintento = ventas.registrar(solicitud);

        igual("8100", reintento.cambio());
        assertEquals(original, reintento);
    }

    @Test
    void elReintentoMismoConOtroOrdenDeLineasOLineasPartidasEsLaMismaCarga() {
        Integer a = caja.producto("A", 5, "10000");
        Integer b = caja.producto("B", 5, "20000");
        UUID clave = UUID.randomUUID();
        var primera = con(venta(List.of(linea(a, 1), linea(b, 1), linea(a, 1)), efectivo("40000")), clave);
        var otroOrden = con(venta(List.of(linea(b, 1), linea(a, 2)), efectivo("40000")), clave);

        ResultadoDeVenta uno = ventas.procesar(primera);
        ResultadoDeVenta dos = ventas.procesar(otroOrden);

        assertTrue(dos.yaExistia());
        assertEquals(uno.venta().id(), dos.venta().id());
    }

    @Test
    void mismaClaveConOtraCargaEs409YNoCambiaNada() {
        Integer producto = caja.producto("A", 5, "10000");
        UUID clave = UUID.randomUUID();
        ventas.registrar(con(venta(linea(producto, 1), efectivo("10000")), clave));

        assertThrows(ClaveDeIdempotenciaEnUsoException.class,
                () -> ventas.procesar(con(venta(linea(producto, 2), efectivo("20000")), clave)));

        assertEquals(1, caja.cuantas("ventas"));
        assertEquals(4, caja.existencia(producto));
    }

    @Test
    void laHuellaNoGuardaElPinYNoCambiaConEl() {
        Integer producto = caja.producto("A", 5, "10000");
        caja.entrarComoVendedor();
        UUID clave = UUID.randomUUID();
        var conDescuento = new LineaSolicitada(producto, null, 1, new Descuento(TipoDescuento.PESOS, new BigDecimal("500")), null);
        SolicitudVenta base = con(venta(conDescuento, efectivo("9500")), clave);
        SolicitudVenta conPin = new SolicitudVenta(clave, null, base.lineas(), null, base.pagos(), null,
                new SolicitudAutorizacion(caja.administradorId, CajaDePrueba.PIN), null);

        VentaRegistrada r = ventas.registrar(conPin);
        VentaRegistrada reintento = ventas.registrar(conPin);

        assertEquals(r.id(), reintento.id());
        String huella = (String) ventaGuardada(r.id()).get("huella_carga");
        assertFalse(huella.contains(CajaDePrueba.PIN));
        assertTrue(huella.matches("[0-9a-f]{64}"), huella);
    }

    // ------------------------------------------------------- la atomicidad

    @Test
    void siFallaElKardexNoQuedaVentaNiExistenciaCambiadaNiConsecutivoConsumido() {
        Integer producto = caja.producto("A", 5, "10000");
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("falla el kárdex")).when(kardex)
                .registrarEgresos(any(), any(), any(), any());

        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> ventas.procesar(venta(linea(producto, 2), efectivo("20000"))));

        nadaGuardado(producto);
        assertEquals(5, caja.existencia(producto));
    }

    // ------------------------------------------------------- el reloj

    @Test
    void laFechaSaleDelRelojEnLaHoraDeBogota() {
        Integer producto = caja.producto("A", 5, "10000");
        Clock utc = Clock.fixed(Instant.parse("2026-11-12T13:30:45.678Z"), ZoneOffset.UTC);
        RegistradorDeVenta conRelojFijo = new RegistradorDeVenta(turnos, repositorioVentas, consecutivos, kardex,
                clientes, utc);
        SolicitudVenta solicitud = venta(linea(producto, 1), efectivo("10000"));

        ResultadoDeVenta r = new TransactionTemplate(transacciones).execute(estado ->
                conRelojFijo.registrar(solicitud, new co.com.bspstore.sgip.logica.Autorizacion(null, Set.of())));

        assertEquals(LocalDateTime.of(2026, 11, 12, 8, 30, 45), ventaGuardada(r.venta().id()).get("fecha"),
                "las 13:30 UTC son las 08:30 en Bogotá");
        assertEquals(LocalDateTime.of(2026, 11, 12, 8, 30, 45),
                pagosGuardados(r.venta().id()).get(0).get("registrado_en"));
    }

    // ------------------------------------------------------- de punta a punta por la API

    @Test
    void elPrecioLoPoneElServidor() throws Exception {
        Integer producto = caja.producto("A", 5, "10000");
        caja.conPruebaVigente();
        var u = new UsuarioAutenticado(caja.vendedorId, "vendedor@ejemplo.co", "VENDEDOR", caja.empresa.empresaId);
        String cuerpo = """
                {"claveIdempotencia":"%s",
                 "lineas":[{"productoId":%d,"cantidad":2,"precioUnitario":1,"precio":1,"costo":1,"utilidad":99}],
                 "precioTotal":1,
                 "pagos":[{"medioId":%d,"monto":20000,"recibido":50000}]}
                """.formatted(UUID.randomUUID(), producto, caja.efectivoId);

        mvc.perform(post("/api/v1/caja/ventas").contentType("application/json").content(cuerpo)
                        .with(authentication(new UsernamePasswordAuthenticationToken(u, null,
                                List.of(new SimpleGrantedAuthority("ROLE_VENDEDOR"))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.consecutivo").value("V-00000001"))
                .andExpect(jsonPath("$.total").value(20000))
                .andExpect(jsonPath("$.cambio").value(30000))
                .andExpect(jsonPath("$.lineas[0].precioUnitario").value(10000))
                .andExpect(jsonPath("$.lineas[0].costo").doesNotExist())
                .andExpect(jsonPath("$.lineas[0].utilidad").doesNotExist());
    }

    @Test
    void laRespuestaPorLaApiNuncaLlevaCostoNiUtilidadNiParaElAdministrador() throws Exception {
        Integer producto = caja.producto("A", 5, "10000");
        caja.conPruebaVigente();
        var u = new UsuarioAutenticado(caja.administradorId, "admin@ejemplo.co", "ADMINISTRADOR", caja.empresa.empresaId);
        String cuerpo = """
                {"claveIdempotencia":"%s","lineas":[{"productoId":%d,"cantidad":1}],
                 "pagos":[{"medioId":%d,"monto":10000}]}
                """.formatted(UUID.randomUUID(), producto, caja.efectivoId);

        String json = mvc.perform(post("/api/v1/caja/ventas").contentType("application/json").content(cuerpo)
                        .with(authentication(new UsernamePasswordAuthenticationToken(u, null,
                                List.of(new SimpleGrantedAuthority("ROLE_ADMINISTRADOR"))))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        for (String prohibida : List.of("costo", "utilidad", "margen", "huella")) {
            assertFalse(json.toLowerCase().contains(prohibida), prohibida + " en " + json);
        }
    }
}

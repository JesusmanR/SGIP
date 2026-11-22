package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.Domicilio;
import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.api.dto.ProductoNuevo;
import co.com.bspstore.sgip.api.dto.SolicitudAutorizacion;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.api.dto.VentaRegistrada;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.logica.venta.Descuento;
import co.com.bspstore.sgip.logica.venta.TipoDescuento;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * La fachada de la venta (RF-41): lo que decide ANTES de abrir la transacción de la venta.
 * Valida la forma de la solicitud (400 con texto fijo, sin eco del dato), fusiona las líneas
 * repetidas, deduce de la carga qué excepciones pide autorizar, llama a la autorización con PIN
 * y solo entonces entrega la solicitud ya normalizada al bean transaccional.
 *
 * Lo que depende de la base (existencia, precios, pagos, consecutivo, idempotencia) está en
 * {@code VentaConBaseTest}.
 */
@ExtendWith(MockitoExtension.class)
class ServicioVentasTest {

    /** 13:30 en UTC son las 08:30 del 12 de noviembre en Bogotá. */
    private static final Instant AHORA = Instant.parse("2026-11-12T13:30:45Z");
    private static final LocalDate HOY = LocalDate.of(2026, 11, 12);
    private static final UUID CLAVE = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Set<ExcepcionAutorizable> NADA = Set.of();

    @Mock ServicioAutorizacion autorizacion;
    @Mock RegistradorDeVenta registrador;

    private ServicioVentas servicio;

    @BeforeEach
    void comoVendedor() {
        entrar("VENDEDOR");
        servicio = new ServicioVentas(autorizacion, registrador, Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private static void entrar(String rol) {
        var u = new UsuarioAutenticado(7, "ana@tienda.co", rol, 3);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
    }

    // ------------------------------------------------------- ayudas

    private static LineaSolicitada linea(Integer producto, int cantidad) {
        return new LineaSolicitada(producto, null, cantidad, null, null);
    }

    private static PagoSolicitado efectivo(String monto) {
        return new PagoSolicitado(1, new BigDecimal(monto), null, null);
    }

    private static SolicitudVenta venta(LineaSolicitada... lineas) {
        return new SolicitudVenta(CLAVE, null, List.of(lineas), null, List.of(efectivo("10000")), null, null, null);
    }

    private static SolicitudVenta conLineas(SolicitudVenta base, List<LineaSolicitada> lineas) {
        return new SolicitudVenta(base.claveIdempotencia(), base.clienteId(), lineas, base.descuentoTotal(),
                base.pagos(), base.motivoEncargo(), base.autorizacion(), base.domicilio());
    }

    private static SolicitudVenta conPagos(SolicitudVenta base, List<PagoSolicitado> pagos) {
        return new SolicitudVenta(base.claveIdempotencia(), base.clienteId(), base.lineas(), base.descuentoTotal(),
                pagos, base.motivoEncargo(), base.autorizacion(), base.domicilio());
    }

    private static SolicitudVenta conDescuentoTotal(SolicitudVenta base, Descuento d) {
        return new SolicitudVenta(base.claveIdempotencia(), base.clienteId(), base.lineas(), d, base.pagos(),
                base.motivoEncargo(), base.autorizacion(), base.domicilio());
    }

    private static SolicitudVenta conMotivo(SolicitudVenta base, String motivo) {
        return new SolicitudVenta(base.claveIdempotencia(), base.clienteId(), base.lineas(), base.descuentoTotal(),
                base.pagos(), motivo, base.autorizacion(), base.domicilio());
    }

    private static SolicitudVenta conAutorizacion(SolicitudVenta base, SolicitudAutorizacion a) {
        return new SolicitudVenta(base.claveIdempotencia(), base.clienteId(), base.lineas(), base.descuentoTotal(),
                base.pagos(), base.motivoEncargo(), a, base.domicilio());
    }

    private static final VentaRegistrada REGISTRADA = new VentaRegistrada(9L, "V-00000001",
            new BigDecimal("10000.00"), BigDecimal.ZERO.setScale(2), List.of());

    private SolicitudVenta lasQueRecibeElRegistrador() {
        ArgumentCaptor<SolicitudVenta> recibida = ArgumentCaptor.forClass(SolicitudVenta.class);
        verify(registrador).registrar(recibida.capture(), any());
        return recibida.getValue();
    }

    private Set<ExcepcionAutorizable> excepcionesPedidas() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<ExcepcionAutorizable>> pedidas = ArgumentCaptor.forClass(Set.class);
        verify(autorizacion).autorizar(any(), pedidas.capture());
        return pedidas.getValue();
    }

    private void sinTocarNada() {
        verifyNoInteractions(autorizacion, registrador);
    }

    // ------------------------------------------------------- el camino feliz

    @Test
    void unaVentaSinNadaQueAutorizarNoLlamaALaAutorizacionYEntregaUnaAutorizacionVacia() {
        // Evita una transacción independiente (REQUIRES_NEW) vacía por cada venta.
        when(registrador.registrar(any(), any())).thenReturn(new ResultadoDeVenta(REGISTRADA, false));

        ResultadoDeVenta r = servicio.procesar(venta(linea(7, 2)));

        assertSame(REGISTRADA, r.venta());
        assertFalse(r.yaExistia());
        verifyNoInteractions(autorizacion);
        verify(registrador).registrar(any(), eq(new Autorizacion(null, NADA)));
    }

    @Test
    void registrarDevuelveSoloLaVenta() {
        when(registrador.registrar(any(), any())).thenReturn(new ResultadoDeVenta(REGISTRADA, true));

        assertSame(REGISTRADA, servicio.registrar(venta(linea(7, 2))));
    }

    // ------------------------------------------------------- qué se autoriza

    @Test
    void unDescuentoDeLineaPideAutorizarElDescuento() {
        when(autorizacion.autorizar(any(), anySet())).thenReturn(new Autorizacion(1, Set.of(ExcepcionAutorizable.DESCUENTO)));
        when(registrador.registrar(any(), any())).thenReturn(new ResultadoDeVenta(REGISTRADA, false));

        servicio.procesar(venta(new LineaSolicitada(7, null, 2, new Descuento(TipoDescuento.PORCENTAJE,
                new BigDecimal("10")), null)));

        assertEquals(Set.of(ExcepcionAutorizable.DESCUENTO), excepcionesPedidas());
    }

    @Test
    void unDescuentoSobreTodaLaCompraPideAutorizarElDescuento() {
        when(autorizacion.autorizar(any(), anySet())).thenReturn(new Autorizacion(1, Set.of(ExcepcionAutorizable.DESCUENTO)));
        when(registrador.registrar(any(), any())).thenReturn(new ResultadoDeVenta(REGISTRADA, false));

        servicio.procesar(conDescuentoTotal(venta(linea(7, 2)), new Descuento(TipoDescuento.PESOS, new BigDecimal("500"))));

        assertEquals(Set.of(ExcepcionAutorizable.DESCUENTO), excepcionesPedidas());
    }

    @Test
    void unaFechaDeEntregaEnUnaLineaPideAutorizarElEncargo() {
        when(autorizacion.autorizar(any(), anySet())).thenReturn(new Autorizacion(1, Set.of(ExcepcionAutorizable.ENCARGO)));
        when(registrador.registrar(any(), any())).thenReturn(new ResultadoDeVenta(REGISTRADA, false));

        servicio.procesar(conMotivo(venta(new LineaSolicitada(7, null, 2, null, HOY.plusDays(5))), "Llega el viernes"));

        assertEquals(Set.of(ExcepcionAutorizable.ENCARGO), excepcionesPedidas());
    }

    @Test
    void soloElMotivoDelEncargoTambienPideAutorizarElEncargo() {
        when(autorizacion.autorizar(any(), anySet())).thenReturn(new Autorizacion(1, Set.of(ExcepcionAutorizable.ENCARGO)));
        when(registrador.registrar(any(), any())).thenReturn(new ResultadoDeVenta(REGISTRADA, false));

        servicio.procesar(conMotivo(venta(linea(7, 2)), "Llega el viernes"));

        assertEquals(Set.of(ExcepcionAutorizable.ENCARGO), excepcionesPedidas());
    }

    @Test
    void unMotivoEnBlancoNoCuentaComoCampoDeEncargo() {
        when(registrador.registrar(any(), any())).thenReturn(new ResultadoDeVenta(REGISTRADA, false));

        servicio.procesar(conMotivo(venta(linea(7, 2)), "   "));

        verifyNoInteractions(autorizacion);
    }

    @Test
    void descuentoYEncargoJuntosPidenLasDosConUnSoloLlamado() {
        when(autorizacion.autorizar(any(), anySet())).thenReturn(new Autorizacion(1,
                Set.of(ExcepcionAutorizable.DESCUENTO, ExcepcionAutorizable.ENCARGO)));
        when(registrador.registrar(any(), any())).thenReturn(new ResultadoDeVenta(REGISTRADA, false));

        servicio.procesar(conMotivo(venta(new LineaSolicitada(7, null, 2,
                new Descuento(TipoDescuento.PESOS, new BigDecimal("100")), HOY)), "Llega hoy"));

        assertEquals(Set.of(ExcepcionAutorizable.DESCUENTO, ExcepcionAutorizable.ENCARGO), excepcionesPedidas());
    }

    @Test
    void laAutorizacionViajaAlAutorizadorTalComoVinoYSeEntregaTalComoLaDevolvio() {
        var s = new SolicitudAutorizacion(4, "483920");
        Autorizacion concedida = new Autorizacion(4, Set.of(ExcepcionAutorizable.DESCUENTO));
        when(autorizacion.autorizar(any(), anySet())).thenReturn(concedida);
        when(registrador.registrar(any(), any())).thenReturn(new ResultadoDeVenta(REGISTRADA, false));

        servicio.procesar(conAutorizacion(conDescuentoTotal(venta(linea(7, 2)),
                new Descuento(TipoDescuento.PESOS, new BigDecimal("500"))), s));

        verify(autorizacion).autorizar(eq(s), anySet());
        verify(registrador).registrar(any(), eq(concedida));
    }

    @Test
    void sinPinElDescuentoEs403YElRegistradorNiSeEntera() {
        doThrow(new AutorizacionRequeridaException()).when(autorizacion).autorizar(any(), anySet());

        assertThrows(AutorizacionRequeridaException.class, () -> servicio.procesar(
                conDescuentoTotal(venta(linea(7, 2)), new Descuento(TipoDescuento.PESOS, new BigDecimal("500")))));

        verify(registrador, never()).registrar(any(), any());
    }

    @Test
    void unPinErradoCortaLaVentaAntesDelRegistrador() {
        doThrow(new PinInvalidoException(15)).when(autorizacion).autorizar(any(), anySet());

        assertThrows(PinInvalidoException.class, () -> servicio.procesar(
                conAutorizacion(conMotivo(venta(linea(7, 2)), "Llega el viernes"), new SolicitudAutorizacion(4, "000000"))));

        verify(registrador, never()).registrar(any(), any());
    }

    // ------------------------------------------------------- la guardia de la transacción (M2)

    @Test
    void siHayUnaTransaccionActivaEsUnErrorDeProgramacionYNoSeToca() {
        // El PIN se valida en su propia transacción: si el hilo ya tiene una abierta (con la fila
        // del usuario bloqueada o referenciada), la de dentro esperaría a la de fuera hasta el
        // tiempo límite, fallaría, y el intento no se contaría.
        TransactionSynchronizationManager.setActualTransactionActive(true);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> servicio.procesar(venta(linea(7, 2))));

        assertTrue(e.getMessage().contains("transacción"), e.getMessage());
        sinTocarNada();
    }

    // ------------------------------------------------------- la forma de la solicitud (400)

    @Test
    void unaLineaConProductoNuevoPideAutorizarElProductoNuevoYElEncargoConUnSoloPin() {
        when(autorizacion.autorizar(any(), anySet())).thenReturn(new Autorizacion(1,
                Set.of(ExcepcionAutorizable.PRODUCTO_NUEVO, ExcepcionAutorizable.ENCARGO)));
        when(registrador.registrar(any(), any())).thenReturn(new ResultadoDeVenta(REGISTRADA, false));
        var nuevo = new LineaSolicitada(null, new ProductoNuevo("Tornillo", new BigDecimal("500"), null), 1, null,
                HOY.plusDays(5));

        servicio.procesar(conAutorizacion(conMotivo(venta(nuevo), "Llega el viernes"), new SolicitudAutorizacion(1, "483920")));

        assertEquals(Set.of(ExcepcionAutorizable.PRODUCTO_NUEVO, ExcepcionAutorizable.ENCARGO), excepcionesPedidas());
        assertNotNull(lasQueRecibeElRegistrador().lineas().get(0).productoNuevo());
    }

    @Test
    void unProductoNuevoSinFechaOSinMotivoEs400SinPedirElPinNiTocarNada() {
        var sinFecha = new LineaSolicitada(null, new ProductoNuevo("Tornillo", new BigDecimal("500"), null), 1, null, null);
        var conFecha = new LineaSolicitada(null, new ProductoNuevo("Tornillo", new BigDecimal("500"), null), 1, null,
                HOY.plusDays(5));

        assertThrows(VentaInvalidaException.class, () -> servicio.procesar(conMotivo(venta(sinFecha), "Llega el viernes")));
        assertThrows(VentaInvalidaException.class, () -> servicio.procesar(venta(conFecha)));

        sinTocarNada();
    }

    /**
     * Los productos se crean solo en el catálogo, por el administrador. Quien aún envíe un
     * {@code productoNuevo} (un cliente viejo) recibe un 400 claro: ignorarlo dejaría una línea sin
     * producto y la venta seguiría de largo.
     */
    @Test
    void unProductoNuevoEnElCuerpoEs400ConElMensajeDelCatalogoSinPedirElPinNiTocarNada() throws Exception {
        SolicitudVenta enviada = JsonMapper.builder().build().readValue("""
                {"claveIdempotencia":"11111111-2222-3333-4444-555555555555",
                 "lineas":[{"productoNuevo":{"nombre":"Tornillo","precioVenta":500},"cantidad":1,
                            "entregaEstimada":"2026-11-17"}],
                 "motivoEncargo":"Llega el viernes",
                 "pagos":[{"medioId":1,"monto":10000}],
                 "autorizacion":{"administradorId":1,"pin":"483920"}}
                """, SolicitudVenta.class);

        VentaInvalidaException e = assertThrows(VentaInvalidaException.class, () -> servicio.procesar(enviada));

        assertEquals("Los productos se crean en el catálogo.", e.getMessage());
        sinTocarNada();
    }

    @Test
    void unProductoNuevoJuntoAUnProductoDelCatalogoTambienEsRechazado() throws Exception {
        SolicitudVenta enviada = JsonMapper.builder().build().readValue("""
                {"claveIdempotencia":"11111111-2222-3333-4444-555555555555",
                 "lineas":[{"productoId":7,"cantidad":1},
                           {"productoId":8,"productoNuevo":{"nombre":"X","precioVenta":1},"cantidad":1}],
                 "pagos":[{"medioId":1,"monto":10000}]}
                """, SolicitudVenta.class);

        VentaInvalidaException e = assertThrows(VentaInvalidaException.class, () -> servicio.procesar(enviada));

        assertEquals("Los productos se crean en el catálogo.", e.getMessage());
        sinTocarNada();
    }

    @Test
    void unDomicilioNoPideAutorizacionYLlegaAlRegistradorConSusTextosLimpios() {
        when(registrador.registrar(any(), any())).thenReturn(new ResultadoDeVenta(REGISTRADA, false));
        var conDomicilio = new SolicitudVenta(CLAVE, 5, List.of(linea(7, 1)), null, List.of(efectivo("10000")), null, null,
                new Domicilio(" Calle 1 ", "Cali", "3001112233", " Ana ", false));

        servicio.procesar(conDomicilio);

        verifyNoInteractions(autorizacion);
        assertEquals(new Domicilio("Calle 1", "Cali", "3001112233", "Ana", false), lasQueRecibeElRegistrador().domicilio());
    }

    @Test
    void unDomicilioSinClienteIdentificadoEs400SinPedirElPinNiTocarElRegistrador() {
        var sinCliente = new SolicitudVenta(CLAVE, null, List.of(linea(7, 1)), null, List.of(efectivo("10000")), null, null,
                new Domicilio("Calle 1", "Cali", "3001112233", "Ana", false));

        assertThrows(DatosDeDomicilioIncompletosException.class, () -> servicio.procesar(sinCliente));

        sinTocarNada();
    }

    @Test
    void exactamenteUnoDeProductoIdYProductoNuevo() {
        var ninguno = new LineaSolicitada(null, null, 1, null, null);
        var losDos = new LineaSolicitada(7, new ProductoNuevo("X", new BigDecimal("1"), null), 1, null, null);

        assertThrows(VentaInvalidaException.class, () -> servicio.procesar(venta(ninguno)));
        assertThrows(VentaInvalidaException.class, () -> servicio.procesar(venta(losDos)));
        sinTocarNada();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 100_000, Integer.MAX_VALUE})
    void laCantidadVaDeUnoAlTope(int cantidad) {
        assertThrows(VentaInvalidaException.class, () -> servicio.procesar(venta(linea(7, cantidad))));
        sinTocarNada();
    }

    @Test
    void lasLineasRepetidasSumanSusCantidadesYSePasanOrdenadasPorProducto() {
        when(registrador.registrar(any(), any())).thenReturn(new ResultadoDeVenta(REGISTRADA, false));

        servicio.procesar(venta(linea(9, 1), linea(7, 2), linea(7, 3)));

        List<LineaSolicitada> pasadas = lasQueRecibeElRegistrador().lineas();
        assertEquals(2, pasadas.size());
        assertEquals(7, pasadas.get(0).productoId());
        assertEquals(5, pasadas.get(0).cantidad(), "2 + 3 del mismo producto");
        assertEquals(9, pasadas.get(1).productoId());
        assertEquals(1, pasadas.get(1).cantidad());
    }

    @Test
    void lineasRepetidasConDescuentoDistintoEs400() {
        var con10 = new LineaSolicitada(7, null, 2, new Descuento(TipoDescuento.PORCENTAJE, new BigDecimal("10")), null);
        var con20 = new LineaSolicitada(7, null, 3, new Descuento(TipoDescuento.PORCENTAJE, new BigDecimal("20")), null);
        var sin = linea(7, 1);

        assertThrows(VentaInvalidaException.class, () -> servicio.procesar(venta(con10, con20)));
        assertThrows(VentaInvalidaException.class, () -> servicio.procesar(venta(con10, sin)),
                "con descuento y sin descuento tampoco son lo mismo");
        sinTocarNada();
    }

    @Test
    void lineasRepetidasConElMismoDescuentoSeFusionanAunqueLosCerosDeLaEscalaDifieran() {
        when(autorizacion.autorizar(any(), anySet())).thenReturn(new Autorizacion(1, Set.of(ExcepcionAutorizable.DESCUENTO)));
        when(registrador.registrar(any(), any())).thenReturn(new ResultadoDeVenta(REGISTRADA, false));
        var a = new LineaSolicitada(7, null, 2, new Descuento(TipoDescuento.PORCENTAJE, new BigDecimal("10")), null);
        var b = new LineaSolicitada(7, null, 3, new Descuento(TipoDescuento.PORCENTAJE, new BigDecimal("10.00")), null);

        servicio.procesar(venta(a, b));

        assertEquals(1, lasQueRecibeElRegistrador().lineas().size());
    }

    @Test
    void lineasRepetidasConFechasDeEntregaDistintasEs400() {
        var primera = new LineaSolicitada(7, null, 2, null, HOY.plusDays(3));
        var segunda = new LineaSolicitada(7, null, 3, null, HOY.plusDays(9));

        assertThrows(VentaInvalidaException.class,
                () -> servicio.procesar(conMotivo(venta(primera, segunda), "Llega pronto")));
        sinTocarNada();
    }

    @Test
    void laCantidadFusionadaTambienRespetaElTope() {
        assertThrows(VentaInvalidaException.class, () -> servicio.procesar(venta(linea(7, 60_000), linea(7, 60_000))));
        sinTocarNada();
    }

    @Test
    void laEntregaEstimadaNoPuedeSerAnteriorAHoyEnBogota() {
        // Las 13:30 UTC son las 08:30 del 12 en Bogotá: «hoy» es el 12, y el 11 ya pasó.
        var ayer = new LineaSolicitada(7, null, 2, null, HOY.minusDays(1));

        assertThrows(VentaInvalidaException.class, () -> servicio.procesar(conMotivo(venta(ayer), "Llega pronto")));
        verify(registrador, never()).registrar(any(), any());
        verifyNoInteractions(autorizacion);
    }

    @Test
    void encargoConFechaImposibleEs400() {
        // Hoy + 1 año es el tope. LocalDate admite hasta el año 999999999 y la columna DATE hasta el 9999.
        var imposible = new LineaSolicitada(7, null, 2, null, LocalDate.of(10000, 1, 1));
        var pasadoElTope = new LineaSolicitada(7, null, 2, null, HOY.plusYears(1).plusDays(1));

        assertThrows(VentaInvalidaException.class, () -> servicio.procesar(conMotivo(venta(imposible), "Llega pronto")));
        assertThrows(VentaInvalidaException.class, () -> servicio.procesar(conMotivo(venta(pasadoElTope), "Llega pronto")));
        verify(registrador, never()).registrar(any(), any());
        verifyNoInteractions(autorizacion);
    }

    @Test
    void laEntregaEstimadaDeHoyMasUnAnoEsValida() {
        when(autorizacion.autorizar(any(), anySet())).thenReturn(new Autorizacion(1, Set.of(ExcepcionAutorizable.ENCARGO)));
        when(registrador.registrar(any(), any())).thenReturn(new ResultadoDeVenta(REGISTRADA, false));

        servicio.procesar(conMotivo(venta(new LineaSolicitada(7, null, 2, null, HOY.plusYears(1))), "Llega en un año"));

        verify(registrador).registrar(any(), any());
    }

    @Test
    void laEntregaEstimadaDeHoyEsValida() {
        when(autorizacion.autorizar(any(), anySet())).thenReturn(new Autorizacion(1, Set.of(ExcepcionAutorizable.ENCARGO)));
        when(registrador.registrar(any(), any())).thenReturn(new ResultadoDeVenta(REGISTRADA, false));

        servicio.procesar(conMotivo(venta(new LineaSolicitada(7, null, 2, null, HOY)), "Llega hoy"));

        verify(registrador).registrar(any(), any());
    }

    @Test
    void sinLineasOSinPagosEs400() {
        assertThrows(VentaInvalidaException.class, () -> servicio.procesar(conLineas(venta(linea(7, 1)), List.of())));
        assertThrows(VentaInvalidaException.class, () -> servicio.procesar(conPagos(venta(linea(7, 1)), List.of())));
        sinTocarNada();
    }

    @Test
    void masLineasOMasPagosQueElTopeEs400() {
        List<LineaSolicitada> muchas = new ArrayList<>();
        for (int i = 1; i <= 201; i++) {
            muchas.add(linea(i, 1));
        }
        List<PagoSolicitado> muchosPagos = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            muchosPagos.add(efectivo("1000"));
        }

        assertThrows(VentaInvalidaException.class, () -> servicio.procesar(conLineas(venta(linea(7, 1)), muchas)));
        assertThrows(VentaInvalidaException.class, () -> servicio.procesar(conPagos(venta(linea(7, 1)), muchosPagos)));
        sinTocarNada();
    }

    @Test
    void sinClaveDeIdempotenciaEs400() {
        var sinClave = new SolicitudVenta(null, null, List.of(linea(7, 1)), null, List.of(efectivo("10000")), null, null, null);

        assertThrows(VentaInvalidaException.class, () -> servicio.procesar(sinClave));
        sinTocarNada();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "10000.50", "1000000000000", "1E+20", "0.001"})
    void unMontoQueNoSonPesosEnterosPositivosDeHastaDoceCifrasEs400(String monto) {
        assertThrows(VentaInvalidaException.class,
                () -> servicio.procesar(conPagos(venta(linea(7, 1)), List.of(efectivo(monto)))));
        sinTocarNada();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "10000.50", "1000000000000", "1E+20"})
    void unEfectivoRecibidoQueNoSonPesosEnterosPositivosEs400(String recibido) {
        var pago = new PagoSolicitado(1, new BigDecimal("10000"), new BigDecimal(recibido), null);

        assertThrows(VentaInvalidaException.class, () -> servicio.procesar(conPagos(venta(linea(7, 1)), List.of(pago))));
        sinTocarNada();
    }

    @Test
    void unDescuentoQueNoCabeEnLoQueElCalculoAdmiteEs400SinLlegarAlCalculo() {
        for (String valor : List.of("1E+99999999", "0.00001", "1234567890123", "-1", "0")) {
            var descuento = new Descuento(TipoDescuento.PESOS, new BigDecimal(valor));
            assertThrows(VentaInvalidaException.class,
                    () -> servicio.procesar(conDescuentoTotal(venta(linea(7, 1)), descuento)), valor);
            assertThrows(VentaInvalidaException.class,
                    () -> servicio.procesar(venta(new LineaSolicitada(7, null, 1, descuento, null))), valor);
        }
        assertThrows(VentaInvalidaException.class, () -> servicio.procesar(conDescuentoTotal(venta(linea(7, 1)),
                new Descuento(null, new BigDecimal("5")))));
        assertThrows(VentaInvalidaException.class, () -> servicio.procesar(conDescuentoTotal(venta(linea(7, 1)),
                new Descuento(TipoDescuento.PESOS, null))));
        sinTocarNada();
    }

    @Test
    void laReferenciaYElMotivoTienenSuLargoMaximo() {
        var larga = new PagoSolicitado(1, new BigDecimal("10000"), null, "x".repeat(61));

        assertThrows(VentaInvalidaException.class, () -> servicio.procesar(conPagos(venta(linea(7, 1)), List.of(larga))));
        assertThrows(VentaInvalidaException.class,
                () -> servicio.procesar(conMotivo(venta(linea(7, 1)), "m".repeat(201))));
        sinTocarNada();
    }

    @Test
    void ningunMensajeDeValidacionRepiteLoEnviado() {
        var descuento = new Descuento(TipoDescuento.PESOS, new BigDecimal("1234567890123"));

        VentaInvalidaException e = assertThrows(VentaInvalidaException.class,
                () -> servicio.procesar(conDescuentoTotal(venta(linea(7, 1)), descuento)));

        assertFalse(e.getMessage().contains("1234567890123"), e.getMessage());
        assertNotNull(e.getMessage());
    }

    // ------------------------------------------------------- el reintento de una venta ya registrada

    @Test
    void unReintentoSeReconoceAntesDeAutorizarYAntesDeMirarElDia() {
        // Descuento sin PIN y una fecha de entrega que ya pasó: lo que a una venta nueva le daría 403 y
        // 400 no impide devolver la que ya se registró y cuya respuesta se perdió.
        when(registrador.siYaExiste(any())).thenReturn(Optional.of(new ResultadoDeVenta(REGISTRADA, true)));
        var conDescuentoYFechaVieja = new LineaSolicitada(7, null, 2,
                new Descuento(TipoDescuento.PESOS, new BigDecimal("500")), HOY.minusDays(1));

        ResultadoDeVenta r = servicio.procesar(conMotivo(venta(conDescuentoYFechaVieja), "Llega pronto"));

        assertTrue(r.yaExistia());
        assertSame(REGISTRADA, r.venta());
        verifyNoInteractions(autorizacion);
        verify(registrador, never()).registrar(any(), any());
    }

    @Test
    void elReconocimientoDelReintentoVeLaCargaNormalizada() {
        when(registrador.siYaExiste(any())).thenReturn(Optional.of(new ResultadoDeVenta(REGISTRADA, true)));

        servicio.procesar(venta(linea(9, 1), linea(7, 2), linea(7, 3)));

        ArgumentCaptor<SolicitudVenta> vista = ArgumentCaptor.forClass(SolicitudVenta.class);
        verify(registrador).siYaExiste(vista.capture());
        assertEquals(2, vista.getValue().lineas().size(), "la huella necesita las líneas ya fusionadas");
        assertEquals(5, vista.getValue().lineas().get(0).cantidad());
    }

    @Test
    void laMismaClaveConOtraCargaEs409AntesDeAutorizar() {
        doThrow(new ClaveDeIdempotenciaEnUsoException()).when(registrador).siYaExiste(any());

        assertThrows(ClaveDeIdempotenciaEnUsoException.class, () -> servicio.procesar(venta(linea(7, 2))));

        verifyNoInteractions(autorizacion);
        verify(registrador, never()).registrar(any(), any());
    }

    @Test
    void laFormaSeValidaAntesDeReconocerUnReintento() {
        assertThrows(VentaInvalidaException.class, () -> servicio.procesar(venta(linea(7, 0))));

        verifyNoInteractions(autorizacion, registrador);
    }

    @Test
    void siOtraPeticionConLaMismaClaveGanoDevuelveLaVentaQueYaExisteConElRegistradorDeLectura() {
        when(registrador.registrar(any(), any())).thenThrow(new VentaDuplicadaException(new RuntimeException("duplicado")));
        when(registrador.existente(any())).thenReturn(new ResultadoDeVenta(REGISTRADA, true));

        ResultadoDeVenta r = servicio.procesar(venta(linea(7, 2)));

        assertTrue(r.yaExistia());
        assertSame(REGISTRADA, r.venta());
        verify(registrador).existente(any());
    }

    @Test
    void sinNingunaExcepcionLaVentaNuevaNoConsultaLaExistente() {
        when(registrador.registrar(any(), any())).thenReturn(new ResultadoDeVenta(REGISTRADA, false));

        servicio.procesar(venta(linea(7, 2)));

        verify(registrador, never()).existente(any());
    }
}

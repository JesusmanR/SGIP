package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.DevolucionRegistrada;
import co.com.bspstore.sgip.api.dto.Reembolso;
import co.com.bspstore.sgip.api.dto.SolicitudAutorizacion;
import co.com.bspstore.sgip.api.dto.SolicitudDevolucion;
import co.com.bspstore.sgip.api.dto.UnidadDevuelta;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * La fachada de la devolución (RF-68): lo que decide ANTES de abrir la transacción. Valida la forma de
 * la solicitud (400 con texto fijo, sin eco del dato), fusiona las unidades repetidas, pide la
 * autorización DEVOLUCION y solo entonces entrega la solicitud ya normalizada al bean transaccional.
 *
 * Lo que depende de la base (la venta, lo ya devuelto, los medios, el saldo) está en
 * {@code DevolucionesConBaseTest}.
 */
@ExtendWith(MockitoExtension.class)
class ServicioDevolucionesTest {

    private static final Set<ExcepcionAutorizable> DEVOLUCION = Set.of(ExcepcionAutorizable.DEVOLUCION);
    private static final SolicitudAutorizacion PIN = new SolicitudAutorizacion(2, "483920");

    @Mock ServicioAutorizacion autorizacion;
    @Mock RegistradorDeDevolucion registrador;

    private ServicioDevoluciones servicio;

    @BeforeEach
    void comoVendedor() {
        entrar("VENDEDOR");
        servicio = new ServicioDevoluciones(autorizacion, registrador);
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

    private static SolicitudDevolucion devolucion(String motivo, List<UnidadDevuelta> unidades,
                                                  List<Reembolso> reembolsos) {
        return new SolicitudDevolucion(UUID.randomUUID(), 9L, unidades, motivo, reembolsos, PIN);
    }

    private static SolicitudDevolucion buena() {
        return devolucion("El cliente se arrepintió", List.of(new UnidadDevuelta(1, 1)),
                List.of(new Reembolso(5, new BigDecimal("10000"), null)));
    }

    private void esUn400(SolicitudDevolucion s) {
        assertThrows(VentaInvalidaException.class, () -> servicio.registrar(s));
        verifyNoInteractions(autorizacion, registrador);
    }

    // ------------------------------------------------------- la forma, antes del PIN

    @Test
    void motivoVacioEs400() {
        for (String motivo : new String[]{null, "", "   ", "\t \n"}) {
            esUn400(devolucion(motivo, List.of(new UnidadDevuelta(1, 1)), List.of()));
        }
    }

    @Test
    void unMotivoDeMasDe200CaracteresEs400() {
        esUn400(devolucion("x".repeat(201), List.of(new UnidadDevuelta(1, 1)), List.of()));
    }

    @Test
    void sinVentaOSinListaDeUnidadesEs400() {
        esUn400(new SolicitudDevolucion(UUID.randomUUID(), null, List.of(), "Motivo", List.of(), PIN));
        esUn400(new SolicitudDevolucion(UUID.randomUUID(), 0L, List.of(), "Motivo", List.of(), PIN));
        esUn400(devolucion("Motivo", null, List.of()));
        esUn400(null);
    }

    @Test
    void unaUnidadSinProductoOConCantidadNoPositivaEs400() {
        esUn400(devolucion("Motivo", List.of(new UnidadDevuelta(null, 1)), List.of()));
        esUn400(devolucion("Motivo", List.of(new UnidadDevuelta(1, 0)), List.of()));
        esUn400(devolucion("Motivo", List.of(new UnidadDevuelta(1, -2)), List.of()));
    }

    @Test
    void unReembolsoMalFormadoEs400() {
        List<UnidadDevuelta> unidades = List.of(new UnidadDevuelta(1, 1));
        esUn400(devolucion("Motivo", unidades, List.of(new Reembolso(null, new BigDecimal("100"), null))));
        esUn400(devolucion("Motivo", unidades, List.of(new Reembolso(5, null, null))));
        esUn400(devolucion("Motivo", unidades, List.of(new Reembolso(5, BigDecimal.ZERO, null))));
        esUn400(devolucion("Motivo", unidades, List.of(new Reembolso(5, new BigDecimal("-100"), null))));
        esUn400(devolucion("Motivo", unidades, List.of(new Reembolso(5, new BigDecimal("100.50"), null))));
        esUn400(devolucion("Motivo", unidades, List.of(new Reembolso(5, new BigDecimal("100"), "R".repeat(61)))));
    }

    // ------------------------------------------------------- el PIN

    @Test
    void devolverSinPinEs403YNoLlegaALaTransaccion() {
        doThrow(new AutorizacionRequeridaException()).when(autorizacion).autorizar(any(), anySet());

        assertThrows(AutorizacionRequeridaException.class, () -> servicio.registrar(buena()));

        verify(autorizacion).autorizar(PIN, DEVOLUCION);
        verifyNoInteractions(registrador);
    }

    @Test
    void unPinErradoEsUnPinInvalidoYTampocoLlegaALaTransaccion() {
        doThrow(new PinInvalidoException(15)).when(autorizacion).autorizar(any(), anySet());

        assertThrows(PinInvalidoException.class, () -> servicio.registrar(buena()));

        verifyNoInteractions(registrador);
    }

    @Test
    void conLaAutorizacionEntregaAlBeanTransaccionalLaSolicitudNormalizada() {
        Autorizacion autorizada = new Autorizacion(2, DEVOLUCION);
        when(autorizacion.autorizar(PIN, DEVOLUCION)).thenReturn(autorizada);
        DevolucionRegistrada respuesta = new DevolucionRegistrada(1L, "D-00000001", new BigDecimal("10000"),
                new BigDecimal("10000"), BigDecimal.ZERO, "DEVUELTA_PARCIAL", List.of());
        when(registrador.registrar(any(), any())).thenReturn(new ResultadoDeDevolucion(respuesta, false));
        SolicitudDevolucion pedida = devolucion("  El cliente se arrepintió  ",
                List.of(new UnidadDevuelta(3, 1), new UnidadDevuelta(1, 2), new UnidadDevuelta(3, 2)),
                List.of(new Reembolso(5, new BigDecimal("10000.00"), "  ")));

        DevolucionRegistrada r = servicio.registrar(pedida);

        assertSame(respuesta, r);
        ArgumentCaptor<SolicitudDevolucion> enviada = ArgumentCaptor.forClass(SolicitudDevolucion.class);
        verify(registrador).registrar(enviada.capture(), same(autorizada));
        SolicitudDevolucion n = enviada.getValue();
        assertEquals("El cliente se arrepintió", n.motivo(), "el motivo va sin espacios sobrantes");
        assertEquals(List.of(new UnidadDevuelta(1, 2), new UnidadDevuelta(3, 3)), n.unidades(),
                "las unidades repetidas se fusionan y van por producto");
        assertEquals(1, n.reembolsos().size());
        assertEquals(0, new BigDecimal("10000").compareTo(n.reembolsos().get(0).monto()));
        assertNull(n.reembolsos().get(0).referencia(), "una referencia en blanco es ninguna");
    }

    @Test
    void unaListaVaciaDeUnidadesEsUnaAnulacionYSePermite() {
        when(autorizacion.autorizar(PIN, DEVOLUCION)).thenReturn(new Autorizacion(2, DEVOLUCION));

        servicio.registrar(devolucion("Anulada por error de digitación", List.of(), null));

        ArgumentCaptor<SolicitudDevolucion> enviada = ArgumentCaptor.forClass(SolicitudDevolucion.class);
        verify(registrador).registrar(enviada.capture(), any());
        assertEquals(List.of(), enviada.getValue().unidades());
        assertEquals(List.of(), enviada.getValue().reembolsos(), "sin reembolsos es una lista vacía, no nula");
    }

    @Test
    void elAdministradorTambienPasaPorLaAutorizacionQueSeAutorizaASiMisma() {
        entrar("ADMINISTRADOR");
        when(autorizacion.autorizar(any(), anySet())).thenReturn(new Autorizacion(7, DEVOLUCION));

        servicio.registrar(new SolicitudDevolucion(UUID.randomUUID(), 9L, List.of(), "Motivo", List.of(), null));

        verify(autorizacion).autorizar(null, DEVOLUCION);
        verify(registrador).registrar(any(), any());
    }

    @Test
    void noSePuedeLlamarDentroDeUnaTransaccion() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThrows(IllegalStateException.class, () -> servicio.registrar(buena()));

        verifyNoInteractions(autorizacion, registrador);
    }

    // ------------------------------------------------------- la clave de idempotencia

    @Test
    void unReintentoDeUnaDevolucionYaRegistradaSeDevuelveSinPedirPinNiAbrirOtraTransaccion() {
        DevolucionRegistrada primera = new DevolucionRegistrada(1L, "D-00000001", new BigDecimal("10000"),
                new BigDecimal("10000"), BigDecimal.ZERO, "ANULADA", List.of());
        when(registrador.siYaExiste(any())).thenReturn(Optional.of(new ResultadoDeDevolucion(primera, true)));

        ResultadoDeDevolucion r = servicio.procesar(buena());

        assertTrue(r.yaExistia());
        assertSame(primera, r.devolucion());
        verify(registrador, never()).registrar(any(), any());
        verifyNoInteractions(autorizacion);
    }

    @Test
    void laClaveEnUsoConOtraCargaPasaTalCual() {
        when(registrador.siYaExiste(any())).thenThrow(new ClaveDeIdempotenciaEnUsoException());

        assertThrows(ClaveDeIdempotenciaEnUsoException.class, () -> servicio.procesar(buena()));

        verifyNoInteractions(autorizacion);
    }

    @Test
    void sinClaveEs400() {
        esUn400(new SolicitudDevolucion(null, 9L, List.of(), "Motivo", List.of(), PIN));
    }

    @Test
    void laHuellaNoDependeDelPinNiDelOrdenDeLasUnidadesPeroSiDeLoDemas() {
        SolicitudDevolucion base = SolicitudDevolucion_(List.of(new UnidadDevuelta(1, 1), new UnidadDevuelta(2, 3)));
        String huella = NormalizadorDeDevolucion.huella(NormalizadorDeDevolucion.forma(base));

        assertEquals(huella, NormalizadorDeDevolucion.huella(NormalizadorDeDevolucion.forma(
                SolicitudDevolucion_(List.of(new UnidadDevuelta(2, 3), new UnidadDevuelta(1, 1))))),
                "el orden de las unidades no cambia la carga");
        assertEquals(huella, NormalizadorDeDevolucion.huella(NormalizadorDeDevolucion.forma(
                new SolicitudDevolucion(base.clave(), 9L, base.unidades(), base.motivo(), base.reembolsos(), null))),
                "quién autoriza no es parte de la carga");
        for (SolicitudDevolucion otra : List.of(
                new SolicitudDevolucion(base.clave(), 10L, base.unidades(), base.motivo(), base.reembolsos(), PIN),
                new SolicitudDevolucion(base.clave(), 9L, List.of(), base.motivo(), base.reembolsos(), PIN),
                new SolicitudDevolucion(base.clave(), 9L, base.unidades(), "Otro motivo", base.reembolsos(), PIN),
                new SolicitudDevolucion(base.clave(), 9L, base.unidades(), base.motivo(),
                        List.of(new Reembolso(5, new BigDecimal("10001"), null)), PIN),
                new SolicitudDevolucion(base.clave(), 9L, base.unidades(), base.motivo(),
                        List.of(new Reembolso(5, new BigDecimal("10000"), "REF")), PIN),
                new SolicitudDevolucion(base.clave(), 9L, base.unidades(), base.motivo(),
                        List.of(new Reembolso(6, new BigDecimal("10000"), null)), PIN),
                new SolicitudDevolucion(base.clave(), 9L, List.of(new UnidadDevuelta(1, 2), new UnidadDevuelta(2, 3)),
                        base.motivo(), base.reembolsos(), PIN))) {
            assertTrue(!huella.equals(NormalizadorDeDevolucion.huella(NormalizadorDeDevolucion.forma(otra))),
                    "una variante tiene que cambiar la huella: " + otra);
        }
    }

    private static SolicitudDevolucion SolicitudDevolucion_(List<UnidadDevuelta> unidades) {
        return new SolicitudDevolucion(UUID.fromString("11111111-2222-3333-4444-555555555555"), 9L, unidades,
                "El cliente se arrepintió", List.of(new Reembolso(5, new BigDecimal("10000"), null)), PIN);
    }
}

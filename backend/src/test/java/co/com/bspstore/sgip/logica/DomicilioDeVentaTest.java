package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.Domicilio;
import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La forma del domicilio dentro de la venta (RF-44, RF-69): lo que se rechaza antes de abrir
 * ninguna transacción y lo que cambia la huella de la carga. El domicilio lo entrega un tercero:
 * la venta guarda a dónde, a quién y si se cobra al entregarlo (pago contra entrega).
 */
class DomicilioDeVentaTest {

    private static final LocalDate HOY = LocalDate.of(2026, 11, 12);
    private static final UUID CLAVE = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Domicilio COMPLETO = new Domicilio("Calle 1 # 2-3", "Cali", "3001112233", "Ana Pérez", false);

    private static List<PagoSolicitado> pagos() {
        return List.of(new PagoSolicitado(1, new BigDecimal("10000"), null, null));
    }

    private static SolicitudVenta venta(Integer cliente, List<PagoSolicitado> pagos, Domicilio domicilio) {
        return new SolicitudVenta(CLAVE, cliente, List.of(new LineaSolicitada(7, null, 1, null, null)), null, pagos,
                null, null, domicilio);
    }

    private static SolicitudVenta forma(Integer cliente, List<PagoSolicitado> pagos, Domicilio domicilio) {
        return NormalizadorDeVenta.forma(venta(cliente, pagos, domicilio));
    }

    private static String huella(Domicilio d, boolean contraEntrega) {
        Domicilio completo = d == null ? null : new Domicilio(d.direccion(), d.ciudad(), d.telefono(), d.recibe(), contraEntrega);
        List<PagoSolicitado> pagos = contraEntrega ? List.of() : pagos();
        return NormalizadorDeVenta.huella(NormalizadorDeVenta.normalizar(venta(5, pagos, completo), HOY));
    }

    // ------------------------------------------------------- lo que se acepta

    @Test
    void unDomicilioCompletoConUnClienteIdentificadoPasaConSusTextosLimpios() {
        SolicitudVenta s = forma(5, pagos(), new Domicilio("  Calle 1 # 2-3 ", " Cali ", " 3001112233", "Ana Pérez  ", false));

        assertEquals(new Domicilio("Calle 1 # 2-3", "Cali", "3001112233", "Ana Pérez", false), s.domicilio());
    }

    @Test
    void laCiudadEnBlancoQuedaNulaParaQueLaVentaTomeLaDeLaEmpresa() {
        assertNull(forma(5, pagos(), new Domicilio("Calle 1", "   ", "3001112233", "Ana", false)).domicilio().ciudad());
        assertNull(forma(5, pagos(), new Domicilio("Calle 1", null, "3001112233", "Ana", false)).domicilio().ciudad());
    }

    @Test
    void unaVentaSinDomicilioSigueSinDomicilio() {
        assertNull(forma(null, pagos(), null).domicilio());
    }

    @Test
    void conPagoContraEntregaNoSeEnvianPagosYSeAceptaLaListaVaciaOLaAusente() {
        Domicilio contraEntrega = new Domicilio("Calle 1", "Cali", "3001112233", "Ana", true);

        assertEquals(List.of(), forma(5, List.of(), contraEntrega).pagos());
        assertEquals(List.of(), forma(5, null, contraEntrega).pagos());
    }

    // ------------------------------------------------------- lo que se rechaza (400)

    @Test
    void unDomicilioSinClienteIdentificadoEs400() {
        DatosDeDomicilioIncompletosException e = assertThrows(DatosDeDomicilioIncompletosException.class,
                () -> forma(null, pagos(), COMPLETO));

        assertTrue(e.getMessage().contains("cliente"), e.getMessage());
    }

    @Test
    void faltarLaDireccionElTelefonoOQuienRecibeEs400() {
        for (Domicilio incompleto : List.of(
                new Domicilio(null, "Cali", "3001112233", "Ana", false),
                new Domicilio("   ", "Cali", "3001112233", "Ana", false),
                new Domicilio("Calle 1", "Cali", null, "Ana", false),
                new Domicilio("Calle 1", "Cali", " ", "Ana", false),
                new Domicilio("Calle 1", "Cali", "3001112233", null, false),
                new Domicilio("Calle 1", "Cali", "3001112233", "", false))) {
            assertThrows(DatosDeDomicilioIncompletosException.class, () -> forma(5, pagos(), incompleto), String.valueOf(incompleto));
        }
    }

    @Test
    void cadaTextoDelDomicilioCabeEnSuColumnaYElMensajeNoRepiteLoEnviado() {
        VentaInvalidaException direccion = assertThrows(VentaInvalidaException.class,
                () -> forma(5, pagos(), new Domicilio("D".repeat(201), "Cali", "3001112233", "Ana", false)));
        assertThrows(VentaInvalidaException.class, () -> forma(5, pagos(), new Domicilio("Calle 1", "C".repeat(81), "3001112233", "Ana", false)));
        assertThrows(VentaInvalidaException.class, () -> forma(5, pagos(), new Domicilio("Calle 1", "Cali", "3".repeat(21), "Ana", false)));
        assertThrows(VentaInvalidaException.class, () -> forma(5, pagos(), new Domicilio("Calle 1", "Cali", "3001112233", "A".repeat(121), false)));

        assertTrue(!direccion.getMessage().contains("DDDD"), direccion.getMessage());
        assertDoesNotThrow(() -> forma(5, pagos(), new Domicilio("D".repeat(200), "C".repeat(80), "3".repeat(20), "A".repeat(120), false)));
    }

    @Test
    void conPagoContraEntregaMandarPagosEs400() {
        Domicilio contraEntrega = new Domicilio("Calle 1", "Cali", "3001112233", "Ana", true);

        VentaInvalidaException e = assertThrows(VentaInvalidaException.class, () -> forma(5, pagos(), contraEntrega));

        assertTrue(e.getMessage().contains("contra entrega"), e.getMessage());
    }

    @Test
    void sinPagoContraEntregaLosPagosSiguenSiendoObligatorios() {
        assertThrows(VentaInvalidaException.class, () -> forma(5, List.of(), COMPLETO));
        assertThrows(VentaInvalidaException.class, () -> forma(5, null, COMPLETO));
        assertThrows(VentaInvalidaException.class, () -> forma(null, List.of(), null));
    }

    // ------------------------------------------------------- la huella

    @Test
    void cadaCampoDelDomicilioCambiaLaHuella() {
        String base = huella(COMPLETO, false);

        assertNotEquals(base, huella(new Domicilio("Calle 9", "Cali", "3001112233", "Ana Pérez", false), false), "la dirección");
        assertNotEquals(base, huella(new Domicilio("Calle 1 # 2-3", "Bogotá", "3001112233", "Ana Pérez", false), false), "la ciudad");
        assertNotEquals(base, huella(new Domicilio("Calle 1 # 2-3", null, "3001112233", "Ana Pérez", false), false), "sin ciudad");
        assertNotEquals(base, huella(new Domicilio("Calle 1 # 2-3", "Cali", "3009998877", "Ana Pérez", false), false), "el teléfono");
        assertNotEquals(base, huella(new Domicilio("Calle 1 # 2-3", "Cali", "3001112233", "Luis Gómez", false), false), "quien recibe");
        assertNotEquals(base, huella(null, false), "con y sin domicilio");
    }

    @Test
    void elPagoContraEntregaCambiaLaHuella() {
        // Con los mismos pagos, solo cambia la marca: se compara la carga con pagos en las dos.
        SolicitudVenta pagado = NormalizadorDeVenta.normalizar(venta(5, pagos(), COMPLETO), HOY);
        SolicitudVenta contraEntrega = new SolicitudVenta(pagado.claveIdempotencia(), pagado.clienteId(), pagado.lineas(),
                pagado.descuentoTotal(), pagado.pagos(), pagado.motivoEncargo(), pagado.autorizacion(),
                new Domicilio(COMPLETO.direccion(), COMPLETO.ciudad(), COMPLETO.telefono(), COMPLETO.recibe(), true));

        assertNotEquals(NormalizadorDeVenta.huella(pagado), NormalizadorDeVenta.huella(contraEntrega));
    }

    @Test
    void unTextoQueImitaElSeparadorNoPuedeHacerPasarDosDomiciliosDistintosPorUno() {
        assertNotEquals(huella(new Domicilio("Calle 1:Cali", "3001112233", "Ana", "Luis", false), false),
                huella(new Domicilio("Calle 1", "Cali:3001112233", "Ana", "Luis", false), false));
        assertNotEquals(huella(new Domicilio("Calle 1", "Cali", "3001112233", "Ana:false|", false), false),
                huella(new Domicilio("Calle 1", "Cali", "3001112233", "Ana", false), false));
    }

    @Test
    void ladireccionConEspaciosSobrantesDaLaMismaHuellaQueSinEllos() {
        assertEquals(huella(COMPLETO, false),
                huella(new Domicilio(" Calle 1 # 2-3 ", "Cali ", " 3001112233", "Ana Pérez ", false), false));
    }
}

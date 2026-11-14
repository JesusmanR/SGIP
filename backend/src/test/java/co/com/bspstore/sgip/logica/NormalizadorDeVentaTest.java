package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.api.dto.SolicitudAutorizacion;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.logica.venta.Descuento;
import co.com.bspstore.sgip.logica.venta.TipoDescuento;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La huella de la carga (idempotencia): lo que tiene que cambiarla y, sobre todo, lo que NO.
 * Un reintento lleva la misma venta, a veces con otro PIN u otro administrador, y no es una venta
 * distinta; una cantidad distinta sí lo es.
 */
class NormalizadorDeVentaTest {

    private static final LocalDate HOY = LocalDate.of(2026, 11, 12);
    private static final UUID CLAVE = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static SolicitudVenta venta(int cantidad, SolicitudAutorizacion autorizacion) {
        return new SolicitudVenta(CLAVE, null, List.of(new LineaSolicitada(7, null, cantidad, null, null)), null,
                List.of(new PagoSolicitado(1, new BigDecimal("10000"), null, null)), null, autorizacion, null);
    }

    private static String huella(SolicitudVenta s) {
        return NormalizadorDeVenta.huella(NormalizadorDeVenta.normalizar(s, HOY));
    }

    @Test
    void laHuellaEsUnSha256EnHexadecimal() {
        assertTrue(huella(venta(1, null)).matches("[0-9a-f]{64}"));
    }

    @Test
    void dosSolicitudesQueSoloDifierenEnLaAutorizacionDanLaMismaHuella() {
        String sin = huella(venta(1, null));

        assertEquals(sin, huella(venta(1, new SolicitudAutorizacion(4, "483920"))), "con PIN");
        assertEquals(sin, huella(venta(1, new SolicitudAutorizacion(4, "000000"))), "con otro PIN");
        assertEquals(sin, huella(venta(1, new SolicitudAutorizacion(9, "483920"))), "con otro administrador");
    }

    @Test
    void laHuellaNoContieneElPin() {
        assertFalse(huella(venta(1, new SolicitudAutorizacion(4, "483920"))).contains("483920"));
    }

    @Test
    void unaCantidadDistintaDaOtraHuella() {
        assertNotEquals(huella(venta(1, null)), huella(venta(2, null)));
    }

    @Test
    void cadaPartePropiaDeLaVentaCambiaLaHuella() {
        String base = huella(venta(1, null));
        var otroProducto = new SolicitudVenta(CLAVE, null, List.of(new LineaSolicitada(8, null, 1, null, null)), null,
                venta(1, null).pagos(), null, null, null);
        var otroCliente = new SolicitudVenta(CLAVE, 5, venta(1, null).lineas(), null, venta(1, null).pagos(), null, null, null);
        var conDescuento = new SolicitudVenta(CLAVE, null, venta(1, null).lineas(),
                new Descuento(TipoDescuento.PESOS, new BigDecimal("500")), venta(1, null).pagos(), null, null, null);
        var otroPago = new SolicitudVenta(CLAVE, null, venta(1, null).lineas(), null,
                List.of(new PagoSolicitado(2, new BigDecimal("10000"), null, "REF")), null, null, null);
        var otroMotivo = new SolicitudVenta(CLAVE, null, venta(1, null).lineas(), null, venta(1, null).pagos(),
                "Llega el viernes", null, null);

        for (SolicitudVenta distinta : List.of(otroProducto, otroCliente, conDescuento, otroPago, otroMotivo)) {
            assertNotEquals(base, huella(distinta), String.valueOf(distinta));
        }
    }

    @Test
    void lasLineasPartidasOReordenadasYLosCerosDeLaEscalaDanLaMismaHuella() {
        var partida = new SolicitudVenta(CLAVE, null, List.of(
                new LineaSolicitada(9, null, 1, null, null),
                new LineaSolicitada(7, null, 2, null, null),
                new LineaSolicitada(7, null, 3, null, null)), null,
                List.of(new PagoSolicitado(1, new BigDecimal("10000.00"), null, null)), null, null, null);
        var junta = new SolicitudVenta(CLAVE, null, List.of(
                new LineaSolicitada(7, null, 5, null, null),
                new LineaSolicitada(9, null, 1, null, null)), null,
                List.of(new PagoSolicitado(1, new BigDecimal("1E+4"), null, null)), null, null, null);

        assertEquals(huella(partida), huella(junta));
    }

    @Test
    void lasReglasDeFormaNoMiranElDiaPeroLasDeFechaSi() {
        var vieja = new SolicitudVenta(CLAVE, null,
                List.of(new LineaSolicitada(7, null, 1, null, HOY.minusDays(1))), null,
                venta(1, null).pagos(), "Llega pronto", null, null);

        // La forma y la huella se pueden calcular de una venta cuya fecha de entrega ya pasó: es el reintento.
        String huella = NormalizadorDeVenta.huella(NormalizadorDeVenta.forma(vieja));
        assertTrue(huella.matches("[0-9a-f]{64}"));
        // La regla del día es aparte.
        org.junit.jupiter.api.Assertions.assertThrows(VentaInvalidaException.class,
                () -> NormalizadorDeVenta.fechas(NormalizadorDeVenta.forma(vieja), HOY));
    }
}

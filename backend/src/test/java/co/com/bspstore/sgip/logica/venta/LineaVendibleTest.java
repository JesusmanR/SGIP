package co.com.bspstore.sgip.logica.venta;

import co.com.bspstore.sgip.datos.RepositorioVentas.PrecioVigente;
import co.com.bspstore.sgip.logica.VentaInvalidaException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cuándo una línea se puede vender y a qué precio: la regla que comparten la caja y la cotización.
 * Es la misma que tenía el registrador de ventas; aquí se prueba sin base.
 */
class LineaVendibleTest {

    private static PrecioVigente vigente(String precio, String tarifa, String tratamiento) {
        return new PrecioVigente(7, precio == null ? null : new BigDecimal(precio),
                tarifa == null ? null : new BigDecimal(tarifa), tratamiento);
    }

    @Test
    void sinPrecioNoSePuedeVender() {
        assertTrue(LineaVendible.de(7, 1, null, vigente(null, "0.1900", "GRAVADO")).isEmpty());
    }

    @Test
    void conPrecioCeroNoSePuedeVender() {
        assertTrue(LineaVendible.de(7, 1, null, vigente("0", "0.1900", "GRAVADO")).isEmpty());
    }

    @Test
    void unPrecioQueRedondeaACeroNoSePuedeVender() {
        assertTrue(LineaVendible.de(7, 1, null, vigente("0.4", "0.0000", "NO_GRAVADO")).isEmpty());
    }

    @Test
    void unPrecioMayorQueElMaximoNoSePuedeVender() {
        assertTrue(LineaVendible.de(7, 1, null, vigente("1000000000000", "0.0000", "NO_GRAVADO")).isEmpty());
    }

    @Test
    void elPrecioMaximoSiSePuedeVender() {
        Optional<LineaPedida> l = LineaVendible.de(7, 1, null, vigente("999999999999", "0.0000", "NO_GRAVADO"));

        assertTrue(l.isPresent());
        assertEquals(new BigDecimal("999999999999"), l.get().precioUnitario());
    }

    @Test
    void elPrecioSeRedondeaAPesosConMitadHaciaArriba() {
        assertEquals(new BigDecimal("12345"),
                LineaVendible.de(7, 1, null, vigente("12345.4", "0.1900", "GRAVADO")).orElseThrow().precioUnitario());
        assertEquals(new BigDecimal("12346"),
                LineaVendible.de(7, 1, null, vigente("12345.5", "0.1900", "GRAVADO")).orElseThrow().precioUnitario());
    }

    @Test
    void conservaProductoCantidadDescuentoTarifaYTratamiento() {
        Descuento descuento = new Descuento(TipoDescuento.PORCENTAJE, new BigDecimal("10"));

        LineaPedida l = LineaVendible.de(7, 3, descuento, vigente("12000", "0.1900", "GRAVADO")).orElseThrow();

        assertEquals(7, l.productoId());
        assertEquals(3, l.cantidad());
        assertEquals(descuento, l.descuento());
        assertEquals(new BigDecimal("0.1900"), l.tarifaIva());
        assertEquals("GRAVADO", l.tratamientoIva());
    }

    @Test
    void sinTarifaSeTomaCero() {
        LineaPedida l = LineaVendible.de(7, 1, null, vigente("12000", null, "NO_GRAVADO")).orElseThrow();

        assertEquals(BigDecimal.ZERO, l.tarifaIva());
    }

    @Test
    void unTratamientoQueElCalculoNoAdmiteNoSePuedeVender() {
        assertTrue(LineaVendible.de(7, 1, null, vigente("12000", "0.1900", "INVENTADO")).isEmpty());
    }

    @Test
    void unaTarifaConUnTratamientoSinIvaNoSePuedeVender() {
        assertTrue(LineaVendible.de(7, 1, null, vigente("12000", "0.1900", "EXENTO")).isEmpty());
    }

    @Test
    void elTextoDeNoVendibleEsElDeLaCaja() {
        assertEquals("Este producto no se puede vender: no tiene un precio de venta válido.",
                LineaVendible.NO_VENDIBLE);
    }

    // ------------------------------------------------------------ el tope del subtotal

    private static LineaPedida pedida(int cantidad, String precio) {
        return new LineaPedida(1, cantidad, new BigDecimal(precio), BigDecimal.ZERO, "NO_GRAVADO", null);
    }

    @Test
    void unSubtotalQueCabeNoSeTopa() {
        LineaVendible.toparSubtotal(List.of(pedida(1, "499999999999")));
    }

    @Test
    void unSubtotalQueNoDejaSitioAlIvaSeTopa() {
        VentaInvalidaException e = assertThrows(VentaInvalidaException.class,
                () -> LineaVendible.toparSubtotal(List.of(pedida(1, "499999999999"), pedida(1, "1"))));

        assertEquals(LineaVendible.IMPORTE_DESMEDIDO, e.getMessage());
    }

    @Test
    void elDescuentoNoEsNuloSiNoSeDio() {
        assertNull(LineaVendible.de(7, 1, null, vigente("12000", "0.0000", "NO_GRAVADO")).orElseThrow().descuento());
        assertFalse(LineaVendible.de(7, 1, null, vigente("12000", "0.0000", "NO_GRAVADO")).isEmpty());
    }
}

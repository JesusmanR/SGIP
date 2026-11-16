package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.api.dto.ProductoNuevo;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.logica.venta.Descuento;
import co.com.bspstore.sgip.logica.venta.TipoDescuento;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * La forma de un producto nuevo dentro de la venta (RF-42): lo que se rechaza antes de abrir
 * ninguna transacción y lo que cambia la huella de la carga. Es el producto que nace con la
 * venta, con existencia cero: toda su venta es un encargo, así que la línea trae la fecha de
 * entrega y la venta, el motivo.
 */
class ProductoNuevoTest {

    private static final LocalDate HOY = LocalDate.of(2026, 11, 12);
    private static final LocalDate ENTREGA = HOY.plusDays(7);
    private static final UUID CLAVE = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static ProductoNuevo nuevo(String nombre, String precio, String barras) {
        return new ProductoNuevo(nombre, precio == null ? null : new BigDecimal(precio), barras);
    }

    private static LineaSolicitada lineaNueva(ProductoNuevo p) {
        return new LineaSolicitada(null, p, 2, null, ENTREGA);
    }

    private static SolicitudVenta venta(String motivo, LineaSolicitada... lineas) {
        return new SolicitudVenta(CLAVE, null, List.of(lineas), null,
                List.of(new PagoSolicitado(1, new BigDecimal("10000"), null, null)), motivo, null, null);
    }

    private static SolicitudVenta ventaConNuevo(ProductoNuevo p) {
        return venta("Llega el viernes", lineaNueva(p));
    }

    private static SolicitudVenta forma(SolicitudVenta s) {
        return NormalizadorDeVenta.forma(s);
    }

    private static String huella(SolicitudVenta s) {
        return NormalizadorDeVenta.huella(NormalizadorDeVenta.normalizar(s, HOY));
    }

    // ------------------------------------------------------- lo que se acepta

    @Test
    void unProductoNuevoValidoPasaConSusTextosSinEspaciosSobrantes() {
        SolicitudVenta s = forma(ventaConNuevo(nuevo("  Tornillo hexagonal  ", "1500", "  7701234567890 ")));

        ProductoNuevo p = s.lineas().get(0).productoNuevo();
        assertEquals("Tornillo hexagonal", p.nombre());
        assertEquals(0, new BigDecimal("1500").compareTo(p.precioVenta()));
        assertEquals("7701234567890", p.codigoBarras());
        assertNull(s.lineas().get(0).productoId());
    }

    @Test
    void elCodigoDeBarrasEnBlancoEsNulo() {
        assertNull(forma(ventaConNuevo(nuevo("Tornillo", "1500", "   "))).lineas().get(0).productoNuevo().codigoBarras());
        assertNull(forma(ventaConNuevo(nuevo("Tornillo", "1500", null))).lineas().get(0).productoNuevo().codigoBarras());
    }

    @Test
    void elPrecioConCerosDeLaEscalaEsUnPrecioEnteroYSeGuardaSinCentavos() {
        BigDecimal precio = forma(ventaConNuevo(nuevo("Tornillo", "1500.00", null))).lineas().get(0)
                .productoNuevo().precioVenta();

        assertEquals(0, precio.scale());
        assertEquals(0, new BigDecimal("1500").compareTo(precio));
        assertDoesNotThrow(() -> forma(ventaConNuevo(nuevo("Tornillo", "999999999999", null))), "doce cifras caben");
    }

    @Test
    void dosLineasDeProductoNuevoIgualesNoSeFusionanPorqueCadaUnaCreaSuProducto() {
        SolicitudVenta s = forma(venta("Llega el viernes", lineaNueva(nuevo("Tornillo", "1500", null)),
                lineaNueva(nuevo("Tornillo", "1500", null))));

        assertEquals(2, s.lineas().size());
    }

    @Test
    void lasLineasNuevasVanDespuesDeLasDelCatalogoYElCatalogoSigueOrdenadoPorProducto() {
        var delCatalogo9 = new LineaSolicitada(9, null, 1, null, null);
        var delCatalogo7 = new LineaSolicitada(7, null, 1, null, null);

        SolicitudVenta s = forma(venta("Llega el viernes", lineaNueva(nuevo("Tornillo", "1500", null)),
                delCatalogo9, delCatalogo7));

        assertEquals(List.of(7, 9), s.lineas().subList(0, 2).stream().map(LineaSolicitada::productoId).toList());
        assertNull(s.lineas().get(2).productoId());
        assertEquals("Tornillo", s.lineas().get(2).productoNuevo().nombre());
    }

    // ------------------------------------------------------- lo que se rechaza (400)

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1500", "1500.50", "0.5", "1000000000000", "1E+20", "1E+99999999", "-1E+99999999", "1E-99999999"})
    void unPrecioQueNoEsUnEnteroPositivoDeHastaDoceCifrasEs400(String precio) {
        assertThrows(VentaInvalidaException.class, () -> forma(ventaConNuevo(nuevo("Tornillo", precio, null))));
    }

    @Test
    void sinPrecioEs400() {
        assertThrows(VentaInvalidaException.class, () -> forma(ventaConNuevo(nuevo("Tornillo", null, null))));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void sinNombreEs400(String nombre) {
        assertThrows(VentaInvalidaException.class, () -> forma(ventaConNuevo(nuevo(nombre, "1500", null))));
    }

    @Test
    void unNombreOUnCodigoDeBarrasMasLargosQueSuColumnaEs400SinEcoDelDato() {
        VentaInvalidaException nombre = assertThrows(VentaInvalidaException.class,
                () -> forma(ventaConNuevo(nuevo("N".repeat(161), "1500", null))));
        VentaInvalidaException barras = assertThrows(VentaInvalidaException.class,
                () -> forma(ventaConNuevo(nuevo("Tornillo", "1500", "9".repeat(61)))));

        assertTrue(!nombre.getMessage().contains("NNNN"), nombre.getMessage());
        assertTrue(!barras.getMessage().contains("9999"), barras.getMessage());
        assertDoesNotThrow(() -> forma(ventaConNuevo(nuevo("N".repeat(160), "1500", "9".repeat(60)))));
    }

    @Test
    void unProductoNuevoSinFechaDeEntregaEs400PorqueTodaSuVentaEsEncargo() {
        var sinFecha = new LineaSolicitada(null, nuevo("Tornillo", "1500", null), 2, null, null);

        VentaInvalidaException e = assertThrows(VentaInvalidaException.class,
                () -> forma(venta("Llega el viernes", sinFecha)));

        assertTrue(e.getMessage().contains("entrega"), e.getMessage());
    }

    @Test
    void unProductoNuevoSinMotivoDelEncargoEs400() {
        VentaInvalidaException e = assertThrows(VentaInvalidaException.class,
                () -> forma(venta(null, lineaNueva(nuevo("Tornillo", "1500", null)))));

        assertTrue(e.getMessage().contains("motivo"), e.getMessage());
        assertThrows(VentaInvalidaException.class,
                () -> forma(venta("   ", lineaNueva(nuevo("Tornillo", "1500", null)))));
    }

    @Test
    void unaVentaAdmiteComoMaximoDiezProductosNuevos() {
        List<LineaSolicitada> diez = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            diez.add(lineaNueva(nuevo("Tornillo " + i, "1500", null)));
        }
        List<LineaSolicitada> once = new ArrayList<>(diez);
        once.add(lineaNueva(nuevo("Tornillo 10", "1500", null)));

        assertDoesNotThrow(() -> forma(venta("Llega el viernes", diez.toArray(LineaSolicitada[]::new))));
        assertThrows(VentaInvalidaException.class,
                () -> forma(venta("Llega el viernes", once.toArray(LineaSolicitada[]::new))));
    }

    @Test
    void unaLineaConUnProductoDelCatalogoYUnProductoNuevoSigueSiendoUnError() {
        var losDos = new LineaSolicitada(7, nuevo("Tornillo", "1500", null), 1, null, ENTREGA);

        assertThrows(VentaInvalidaException.class, () -> forma(venta("Llega el viernes", losDos)));
    }

    // ------------------------------------------------------- la huella

    @Test
    void cadaCampoDelProductoNuevoCambiaLaHuella() {
        String base = huella(ventaConNuevo(nuevo("Tornillo", "1500", "7701234567890")));

        assertNotEquals(base, huella(ventaConNuevo(nuevo("Tornillo largo", "1500", "7701234567890"))), "el nombre");
        assertNotEquals(base, huella(ventaConNuevo(nuevo("Tornillo", "1600", "7701234567890"))), "el precio");
        assertNotEquals(base, huella(ventaConNuevo(nuevo("Tornillo", "1500", "7709999999999"))), "el código de barras");
        assertNotEquals(base, huella(ventaConNuevo(nuevo("Tornillo", "1500", null))), "quitar el código de barras");
    }

    @Test
    void cadaCampoDeLaLineaNuevaCambiaLaHuella() {
        ProductoNuevo p = nuevo("Tornillo", "1500", null);
        String base = huella(venta("Llega el viernes", new LineaSolicitada(null, p, 2, null, ENTREGA)));

        assertNotEquals(base, huella(venta("Llega el viernes", new LineaSolicitada(null, p, 3, null, ENTREGA))), "la cantidad");
        assertNotEquals(base, huella(venta("Llega el viernes", new LineaSolicitada(null, p, 2, null, ENTREGA.plusDays(1)))),
                "la fecha de entrega");
        assertNotEquals(base, huella(venta("Llega el viernes", new LineaSolicitada(null, p, 2,
                new Descuento(TipoDescuento.PESOS, new BigDecimal("100")), ENTREGA))), "el descuento");
        assertNotEquals(base, huella(venta("Llega el lunes", new LineaSolicitada(null, p, 2, null, ENTREGA))), "el motivo");
    }

    @Test
    void unProductoNuevoYUnoDelCatalogoDanHuellasDistintas() {
        var nuevaLinea = new LineaSolicitada(null, nuevo("Tornillo", "1500", null), 2, null, ENTREGA);
        var delCatalogo = new LineaSolicitada(7, null, 2, null, ENTREGA);

        assertNotEquals(huella(venta("Llega el viernes", nuevaLinea)), huella(venta("Llega el viernes", delCatalogo)));
    }

    @Test
    void lasLineasNuevasPartidasEnOtroOrdenDanLaMismaHuella() {
        var a = lineaNueva(nuevo("Tornillo", "1500", null));
        var b = lineaNueva(nuevo("Tuerca", "800", "7701234567890"));

        assertEquals(huella(venta("Llega el viernes", a, b)), huella(venta("Llega el viernes", b, a)));
    }

    @Test
    void unTextoQueImitaElSeparadorNoPuedeHacerPasarDosCargasDistintasPorUna() {
        // Con los textos pegados con «:» y «|», estas dos cargas darían el mismo canon.
        var unaSola = lineaNueva(nuevo("A|LN:B", "1500", null));
        var dos = List.of(lineaNueva(nuevo("A", "1500", null)), lineaNueva(nuevo("B", "1500", null)));

        assertNotEquals(huella(venta("Llega el viernes", unaSola)),
                huella(venta("Llega el viernes", dos.toArray(LineaSolicitada[]::new))));
        assertNotEquals(huella(ventaConNuevo(nuevo("A:1500:-", "7", null))),
                huella(ventaConNuevo(nuevo("A", "1500", "-:7"))));
    }

    /**
     * Quien ya tiene ventas registradas con la huella anterior: una venta del mostrador, sin producto
     * nuevo ni domicilio, tiene que seguir dando exactamente la misma huella que antes de la V45 (un
     * reintento posterior a la actualización la reconoce).
     */
    @Test
    void laHuellaDeUnaVentaDelMostradorSigueSiendoLaMismaDeAntesDeLaV45() throws Exception {
        SolicitudVenta mostrador = new SolicitudVenta(CLAVE, null, List.of(new LineaSolicitada(7, null, 2, null, null)),
                null, List.of(new PagoSolicitado(1, new BigDecimal("10000"), null, null)), null, null, null);
        String canonDeSiempre = "venta1|cliente=-|descuento=-|motivo=-|L:7:2:-:-|P:1:10000:-:-|";
        String esperada = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonDeSiempre.getBytes(StandardCharsets.UTF_8)));

        assertEquals(esperada, huella(mostrador));
    }
}

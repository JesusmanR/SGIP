package co.com.bspstore.sgip.logica.venta;

import co.com.bspstore.sgip.logica.DescuentoInvalidoException;
import co.com.bspstore.sgip.logica.EfectivoRecibidoInvalidoException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El cálculo de la venta es el único sitio donde se decide cuánto se cobra, así que las cifras
 * de estas pruebas son las del diseño. Los importes se comparan con {@code compareTo}: la escala
 * es un detalle de cómo se guardan, no del valor.
 */
class CalculoDeVentaTest {

    private static final BigDecimal SIN_IVA = BigDecimal.ZERO;
    private static final BigDecimal IVA_19 = new BigDecimal("0.19");

    private static LineaPedida linea(int productoId, int cantidad, long precio, BigDecimal tarifa,
            String tratamiento, Descuento descuento) {
        return new LineaPedida(productoId, cantidad, BigDecimal.valueOf(precio), tarifa, tratamiento, descuento);
    }

    private static LineaPedida simple(int productoId, int cantidad, long precio) {
        return linea(productoId, cantidad, precio, SIN_IVA, "EXCLUIDO", null);
    }

    private static Descuento porcentaje(String valor) {
        return new Descuento(TipoDescuento.PORCENTAJE, new BigDecimal(valor));
    }

    private static Descuento pesos(long valor) {
        return new Descuento(TipoDescuento.PESOS, BigDecimal.valueOf(valor));
    }

    private static void igual(long esperado, BigDecimal real, String que) {
        assertEquals(0, BigDecimal.valueOf(esperado).compareTo(real), que + ": esperado " + esperado + " y fue " + real);
    }

    private static List<Long> descuentos(VentaCalculada v) {
        return v.lineas().stream().map(l -> l.descuento().longValueExact()).toList();
    }

    // ---- Sumas -----------------------------------------------------------------------------

    @Test
    void sinDescuentosElTotalEsLaSumaDeLineas() {
        VentaCalculada v = CalculoDeVenta.calcular(List.of(simple(1, 2, 1_500), simple(2, 1, 3_000)), null);

        igual(6_000, v.subtotal(), "subtotal");
        igual(0, v.descuento(), "descuento");
        igual(0, v.iva(), "iva");
        igual(6_000, v.total(), "total");
        assertFalse(v.tieneDescuento());
        igual(3_000, v.lineas().get(0).subtotal(), "subtotal de la primera línea");
    }

    @Test
    void lasLineasConservanElOrdenDeEntradaYNoSeFusionan() {
        VentaCalculada v = CalculoDeVenta.calcular(
                List.of(simple(9, 1, 100), simple(3, 1, 100), simple(9, 1, 100)), null);

        assertEquals(List.of(9, 3, 9), v.lineas().stream().map(LineaCalculada::productoId).toList());
    }

    @Test
    void laLineaCalculadaCuadraSusPropiasCifras() {
        VentaCalculada v = CalculoDeVenta.calcular(
                List.of(linea(1, 3, 3_333, IVA_19, "GRAVADO", porcentaje("7"))), pesos(500));

        LineaCalculada l = v.lineas().get(0);
        assertAll(
                () -> assertEquals(0, l.subtotal().subtract(l.descuento()).compareTo(l.base())),
                () -> assertEquals(0, l.base().add(l.iva()).compareTo(l.total())),
                () -> assertEquals(0, l.total().compareTo(v.total())),
                () -> assertEquals(3, l.cantidad()),
                () -> assertEquals(0, new BigDecimal("3333").compareTo(l.precioUnitario())),
                () -> assertEquals(0, IVA_19.compareTo(l.tarifaIva())));
    }

    // ---- Descuento de la línea ---------------------------------------------------------------

    @Test
    void descuentoPorcentajeEnLinea() {
        VentaCalculada v = CalculoDeVenta.calcular(
                List.of(linea(1, 1, 10_000, SIN_IVA, "EXCLUIDO", porcentaje("10"))), null);

        LineaCalculada l = v.lineas().get(0);
        igual(1_000, l.descuento(), "descuento");
        igual(9_000, l.base(), "base");
        igual(9_000, v.total(), "total");
        assertTrue(v.tieneDescuento());
    }

    @Test
    void descuentoPorcentajeSeRedondeaAPesos() {
        VentaCalculada v = CalculoDeVenta.calcular(
                List.of(linea(1, 1, 1_005, SIN_IVA, "EXCLUIDO", porcentaje("10"))), null);

        igual(101, v.lineas().get(0).descuento(), "100,5 con HALF_UP");
        igual(904, v.lineas().get(0).base(), "base");
    }

    @Test
    void descuentoEnPesosEnLinea() {
        VentaCalculada v = CalculoDeVenta.calcular(
                List.of(linea(1, 1, 10_000, SIN_IVA, "EXCLUIDO", pesos(2_500))), null);

        igual(2_500, v.lineas().get(0).descuento(), "descuento");
        igual(7_500, v.lineas().get(0).base(), "base");
    }

    @Test
    void elDescuentoDeLaLineaSeCalculaSobreElSubtotalDeLaCantidad() {
        VentaCalculada v = CalculoDeVenta.calcular(
                List.of(linea(1, 3, 1_000, SIN_IVA, "EXCLUIDO", porcentaje("10"))), null);

        igual(300, v.lineas().get(0).descuento(), "10 % de 3 000");
    }

    @Test
    void unDescuentoDelCienPorCientoOIgualAlValorEsValidoYDejaLaBaseEnCero() {
        VentaCalculada completo = CalculoDeVenta.calcular(
                List.of(linea(1, 1, 5_000, IVA_19, "GRAVADO", porcentaje("100"))), null);
        VentaCalculada exacto = CalculoDeVenta.calcular(
                List.of(linea(1, 1, 5_000, IVA_19, "GRAVADO", pesos(5_000))), null);

        for (VentaCalculada v : List.of(completo, exacto)) {
            igual(0, v.lineas().get(0).base(), "base");
            igual(0, v.lineas().get(0).iva(), "iva sobre base cero");
            igual(0, v.total(), "total");
        }
    }

    @Test
    void descuentoMayorQueLaLineaEs400() {
        List<LineaPedida> lineas = List.of(linea(1, 1, 10_000, SIN_IVA, "EXCLUIDO", pesos(10_001)));

        DescuentoInvalidoException e = assertThrows(DescuentoInvalidoException.class,
                () -> CalculoDeVenta.calcular(lineas, null));
        assertFalse(e.getMessage().contains("10001"), "el mensaje no repite el dato");
    }

    @Test
    void porcentajeNegativoOMayorA100Es400() {
        for (String malo : List.of("-1", "-0.01", "100.01", "101")) {
            List<LineaPedida> lineas = List.of(linea(1, 1, 10_000, SIN_IVA, "EXCLUIDO", porcentaje(malo)));
            assertThrows(DescuentoInvalidoException.class, () -> CalculoDeVenta.calcular(lineas, null), malo);
        }
    }

    @Test
    void unDescuentoSinValorSinTipoNegativoOConCentavosEs400() {
        List<Descuento> malos = List.of(
                new Descuento(TipoDescuento.PESOS, null),
                new Descuento(TipoDescuento.PORCENTAJE, null),
                new Descuento(null, BigDecimal.TEN),
                pesos(-1),
                new Descuento(TipoDescuento.PESOS, new BigDecimal("10.5")));
        for (Descuento malo : malos) {
            List<LineaPedida> lineas = List.of(linea(1, 1, 10_000, SIN_IVA, "EXCLUIDO", malo));
            assertThrows(DescuentoInvalidoException.class, () -> CalculoDeVenta.calcular(lineas, null), malo.toString());
            assertThrows(DescuentoInvalidoException.class,
                    () -> CalculoDeVenta.calcular(List.of(simple(1, 1, 10_000)), malo), "total: " + malo);
        }
    }

    // ---- Descuento sobre toda la compra ------------------------------------------------------

    @Test
    void descuentoTotalSeRepartePorValor() {
        VentaCalculada v = CalculoDeVenta.calcular(List.of(simple(1, 1, 6_000), simple(2, 1, 3_000)), pesos(1_000));

        assertEquals(List.of(667L, 333L), descuentos(v));
        igual(1_000, v.descuento(), "la suma es exactamente el descuento total");
        igual(8_000, v.total(), "total");
        assertTrue(v.tieneDescuento());
    }

    @Test
    void elDescuentoTotalEnPorcentajeSeCalculaSobreLasBasesYSeRedondeaAPesos() {
        // Bases 1 005 y 2 000: el 10 % de 3 005 es 300,5 y con HALF_UP son 301.
        VentaCalculada v = CalculoDeVenta.calcular(List.of(simple(1, 1, 1_005), simple(2, 1, 2_000)), porcentaje("10"));

        igual(301, v.descuento(), "descuento total");
        igual(2_704, v.total(), "total");
    }

    @Test
    void elDescuentoTotalVaDespuesDelDeLaLinea() {
        // Línea 1: 10 000 con 50 % de su propio descuento deja base 5 000; la 2 es de 5 000.
        VentaCalculada v = CalculoDeVenta.calcular(
                List.of(linea(1, 1, 10_000, SIN_IVA, "EXCLUIDO", porcentaje("50")), simple(2, 1, 5_000)),
                porcentaje("10"));

        // 10 % de 10 000 de bases = 1 000, repartido 500 y 500.
        assertEquals(List.of(5_500L, 500L), descuentos(v));
        igual(9_000, v.total(), "total");
    }

    @Test
    void elResiduoVaALaLineaMayor() {
        VentaCalculada v = CalculoDeVenta.calcular(
                List.of(simple(1, 1, 1_000), simple(2, 1, 1_000), simple(3, 1, 1_000)), pesos(100));

        assertEquals(List.of(34L, 33L, 33L), descuentos(v));
    }

    @Test
    void elResiduoNegativoVaALaLineaMayor() {
        VentaCalculada v = CalculoDeVenta.calcular(
                List.of(simple(1, 1, 100), simple(2, 1, 100), simple(3, 1, 100)), pesos(50));

        assertEquals(List.of(16L, 17L, 17L), descuentos(v));
        igual(50, v.descuento(), "la suma es exactamente el descuento total");
    }

    @Test
    void siLaLineaMayorNoAbsorbeElResiduoSigueLaSiguiente() {
        // Cuatro líneas de 1 peso y 2 de descuento: cada parte es 0,5 y sube a 1 (suman 4), y el residuo
        // de −2 no cabe en una sola línea sin dejarla en −1. Se resta a las dos de menor productoId.
        VentaCalculada v = CalculoDeVenta.calcular(
                List.of(simple(1, 1, 1), simple(2, 1, 1), simple(3, 1, 1), simple(4, 1, 1)), pesos(2));

        assertEquals(List.of(0L, 0L, 1L, 1L), descuentos(v));
        igual(2, v.descuento(), "la suma sigue siendo el descuento total");
    }

    @Test
    void elEmpateDelResiduoLoGanaElMenorProductoIdAunqueNoVayaPrimero() {
        VentaCalculada v = CalculoDeVenta.calcular(
                List.of(simple(3, 1, 100), simple(2, 1, 100), simple(1, 1, 100)), pesos(50));

        // Mismo caso con los ids al revés: el 1 sobrante se resta a la línea del producto 1.
        assertEquals(List.of(17L, 17L, 16L), descuentos(v));
    }

    @Test
    void elMayorValorSeMideDespuesDelDescuentoPropioDeLaLinea() {
        // La línea 1 vale 1 000 antes de su descuento del 60 % y queda en 400; la 2 queda en 500, la mayor.
        // Con 5 de descuento total el reparto es 2, 3 y 1: suman 6, y el 1 que sobra se resta a la línea 2.
        VentaCalculada v = CalculoDeVenta.calcular(
                List.of(linea(1, 1, 1_000, SIN_IVA, "EXCLUIDO", porcentaje("60")), simple(2, 1, 500), simple(3, 1, 100)),
                pesos(5));

        assertEquals(List.of(602L, 2L, 1L), descuentos(v));
    }

    @Test
    void elResiduoPositivoTambienVaALaLineaMayorDespuesDeSuDescuentoPropio() {
        // Bases 400, 450 y 150 con 1 peso de descuento: el reparto da 0, 0 y 0 y el peso entero va a la línea 2.
        VentaCalculada v = CalculoDeVenta.calcular(
                List.of(linea(1, 1, 1_000, SIN_IVA, "EXCLUIDO", porcentaje("60")), simple(2, 1, 450), simple(3, 1, 150)),
                pesos(1));

        assertEquals(List.of(600L, 1L, 0L), descuentos(v));
    }

    @Test
    void elDescuentoTotalPuedeSerIgualALaSumaDeLasBases() {
        VentaCalculada v = CalculoDeVenta.calcular(List.of(simple(1, 1, 700), simple(2, 1, 301)), porcentaje("100"));

        assertEquals(List.of(700L, 301L), descuentos(v));
        igual(0, v.total(), "total");
    }

    @Test
    void unDescuentoTotalMayorQueLaSumaDeLasBasesEs400() {
        List<LineaPedida> lineas = List.of(simple(1, 1, 700), simple(2, 1, 300));

        assertThrows(DescuentoInvalidoException.class, () -> CalculoDeVenta.calcular(lineas, pesos(1_001)));
        assertThrows(DescuentoInvalidoException.class, () -> CalculoDeVenta.calcular(lineas, porcentaje("100.5")));
    }

    @Test
    void conTodasLasBasesEnCeroUnDescuentoTotalDeCeroSeAceptaYUnoMayorEs400() {
        List<LineaPedida> lineas = List.of(
                linea(1, 1, 1_000, SIN_IVA, "EXCLUIDO", porcentaje("100")),
                simple(2, 1, 0));

        VentaCalculada v = CalculoDeVenta.calcular(lineas, pesos(0));
        igual(1_000, v.descuento(), "solo el descuento propio de la línea 1");
        igual(0, v.total(), "total");
        assertFalse(CalculoDeVenta.calcular(List.of(simple(1, 1, 0)), pesos(0)).tieneDescuento());

        assertThrows(DescuentoInvalidoException.class, () -> CalculoDeVenta.calcular(lineas, pesos(1)));
    }

    // ---- tieneDescuento ----------------------------------------------------------------------

    @Test
    void tieneDescuentoSiAlgunaLineaOElTotalLlevanUnoMayorQueCero() {
        List<LineaPedida> conCero = List.of(linea(1, 1, 1_000, SIN_IVA, "EXCLUIDO", pesos(0)), simple(2, 1, 1_000));

        assertFalse(CalculoDeVenta.calcular(conCero, null).tieneDescuento());
        assertFalse(CalculoDeVenta.calcular(conCero, pesos(0)).tieneDescuento());
        assertTrue(CalculoDeVenta.calcular(conCero, pesos(1)).tieneDescuento());
        assertTrue(CalculoDeVenta.calcular(List.of(linea(1, 1, 1_000, SIN_IVA, "EXCLUIDO", porcentaje("5"))), null)
                .tieneDescuento());
    }

    @Test
    void unDescuentoPedidoCuentaAunqueAlRedondearDeCeroPesos() {
        // 1 % de 10 pesos son 0,10: se redondea a 0, pero el vendedor pidió un descuento y requiere autorización.
        VentaCalculada v = CalculoDeVenta.calcular(
                List.of(linea(1, 1, 10, SIN_IVA, "EXCLUIDO", porcentaje("1"))), null);

        igual(0, v.descuento(), "descuento");
        assertTrue(v.tieneDescuento());
    }

    // ---- IVA ---------------------------------------------------------------------------------

    @Test
    void ivaSobreLaBaseDescontada() {
        VentaCalculada con = CalculoDeVenta.calcular(
                List.of(linea(1, 1, 10_000, IVA_19, "GRAVADO", porcentaje("10"))), null);
        VentaCalculada sin = CalculoDeVenta.calcular(
                List.of(linea(1, 1, 10_000, IVA_19, "GRAVADO", null)), null);

        LineaCalculada l = con.lineas().get(0);
        igual(1_000, l.descuento(), "descuento");
        igual(9_000, l.base(), "base");
        igual(1_710, l.iva(), "iva");
        igual(10_710, l.total(), "total");
        igual(10_710, con.total(), "total de la venta");
        igual(1_900, sin.iva(), "iva sin descuento");
        igual(11_900, sin.total(), "total sin descuento");
    }

    @Test
    void elIvaSeCalculaSobreLaBaseQueDejaElDescuentoTotal() {
        VentaCalculada v = CalculoDeVenta.calcular(
                List.of(linea(1, 1, 10_000, IVA_19, "GRAVADO", null)), porcentaje("10"));

        igual(1_710, v.iva(), "iva");
        igual(10_710, v.total(), "total");
    }

    @Test
    void elIvaSeRedondeaPorLineaConHalfUp() {
        VentaCalculada v = CalculoDeVenta.calcular(
                List.of(linea(1, 1, 1_003, IVA_19, "GRAVADO", null), linea(2, 1, 1_003, IVA_19, "GRAVADO", null)), null);

        igual(191, v.lineas().get(0).iva(), "190,57 se redondea a 191");
        igual(382, v.iva(), "la suma de los IVA de las líneas, no el redondeo de la suma (381,14 daría 381)");
        igual(2_388, v.total(), "total");
    }

    @Test
    void elMedioPesoDelIvaSubeConHalfUp() {
        // 50 · 0,19 = 9,5 y 50 · 0,05 = 2,5: ambos suben.
        VentaCalculada v = CalculoDeVenta.calcular(
                List.of(linea(1, 1, 50, IVA_19, "GRAVADO", null),
                        linea(2, 1, 50, new BigDecimal("0.05"), "GRAVADO", null)), null);

        igual(10, v.lineas().get(0).iva(), "9,5");
        igual(3, v.lineas().get(1).iva(), "2,5");
    }

    /**
     * La vista de precios entrega {@code total_contado = precio · (1 + tarifa)} (V12 a V25). El CSV
     * {@code database/muestras/casos_de_precio.csv} no tiene esa columna, así que se contrastan casos
     * propios con las mismas cifras: con precios enteros y una unidad, el total de la venta es la
     * columna de la vista.
     */
    @Test
    void elTotalDeUnaUnidadSinDescuentoCoincideConElTotalContadoDeLaVista() {
        record Caso(long precio, String tarifa, long totalContado) {}
        for (Caso c : List.of(
                new Caso(10_000, "0.19", 11_900),
                new Caso(2_000, "0.05", 2_100),
                new Caso(1_200, "0", 1_200),
                new Caso(450_000, "0.19", 535_500))) {
            VentaCalculada v = CalculoDeVenta.calcular(
                    List.of(linea(1, 1, c.precio(), new BigDecimal(c.tarifa()), "GRAVADO", null)), null);
            igual(c.totalContado(), v.total(), "precio " + c.precio() + " a tarifa " + c.tarifa());
        }
    }

    // ---- Tratamiento guardado (M6) -------------------------------------------------------------

    @Test
    void excluidoHastaUmbralSeGuardaComoExcluidoOGravado() {
        VentaCalculada v = CalculoDeVenta.calcular(List.of(
                linea(1, 1, 1_000, SIN_IVA, "EXCLUIDO_HASTA_UMBRAL", null),
                linea(2, 1, 1_000, IVA_19, "EXCLUIDO_HASTA_UMBRAL", null)), null);

        assertEquals("EXCLUIDO", v.lineas().get(0).tratamientoIva());
        assertEquals("GRAVADO", v.lineas().get(1).tratamientoIva());
        igual(190, v.lineas().get(1).iva(), "la que pasó el umbral cobra IVA");
    }

    @Test
    void tratamientoNuloSeGuardaComoNoGravado() {
        VentaCalculada v = CalculoDeVenta.calcular(List.of(linea(1, 1, 1_000, SIN_IVA, null, null)), null);

        assertEquals("NO_GRAVADO", v.lineas().get(0).tratamientoIva());
    }

    @Test
    void losDemasTratamientosPasanTalCual() {
        for (String t : List.of("GRAVADO", "EXCLUIDO", "EXENTO", "NO_GRAVADO")) {
            VentaCalculada v = CalculoDeVenta.calcular(List.of(linea(1, 1, 1_000, SIN_IVA, t, null)), null);
            assertEquals(t, v.lineas().get(0).tratamientoIva());
        }
    }

    @Test
    void unTratamientoDesconocidoEsUnErrorDeProgramacion() {
        assertThrows(IllegalArgumentException.class,
                () -> CalculoDeVenta.calcular(List.of(linea(1, 1, 1_000, SIN_IVA, "INVENTADO", null)), null));
    }

    // ---- Entradas inválidas ------------------------------------------------------------------

    @Test
    void unaListaVaciaONulaEsInvalida() {
        assertThrows(IllegalArgumentException.class, () -> CalculoDeVenta.calcular(List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> CalculoDeVenta.calcular(null, null));
        List<LineaPedida> conNula = new ArrayList<>();
        conNula.add(null);
        assertThrows(IllegalArgumentException.class, () -> CalculoDeVenta.calcular(conNula, null));
    }

    @Test
    void laCantidadDebeSerPositiva() {
        assertThrows(IllegalArgumentException.class, () -> CalculoDeVenta.calcular(List.of(simple(1, 0, 100)), null));
        assertThrows(IllegalArgumentException.class, () -> CalculoDeVenta.calcular(List.of(simple(1, -1, 100)), null));
    }

    @Test
    void elPrecioDebeSerUnMontoEnPesosEnterosNoNegativo() {
        for (BigDecimal malo : new BigDecimal[] {null, new BigDecimal("-1"), new BigDecimal("10.5")}) {
            List<LineaPedida> lineas = List.of(new LineaPedida(1, 1, malo, SIN_IVA, "EXCLUIDO", null));
            assertThrows(IllegalArgumentException.class, () -> CalculoDeVenta.calcular(lineas, null), String.valueOf(malo));
        }
    }

    @Test
    void laTarifaDebeEstarEntreCeroYUnoConCuatroDecimales() {
        for (BigDecimal mala : new BigDecimal[] {null, new BigDecimal("-0.01"), new BigDecimal("1.01"), new BigDecimal("0.19001")}) {
            List<LineaPedida> lineas = List.of(new LineaPedida(1, 1, BigDecimal.TEN, mala, "GRAVADO", null));
            assertThrows(IllegalArgumentException.class, () -> CalculoDeVenta.calcular(lineas, null), String.valueOf(mala));
        }
        // Los extremos son válidos.
        CalculoDeVenta.calcular(List.of(linea(1, 1, 10, BigDecimal.ONE, "GRAVADO", null)), null);
    }

    @Test
    void laLineaSinProductoEsInvalida() {
        List<LineaPedida> lineas = List.of(new LineaPedida(null, 1, BigDecimal.TEN, SIN_IVA, "EXCLUIDO", null));
        assertThrows(IllegalArgumentException.class, () -> CalculoDeVenta.calcular(lineas, null));
    }

    // ---- Cambio --------------------------------------------------------------------------------

    @Test
    void cambioDeEfectivo() {
        igual(1_300, CalculoDeVenta.cambio(new BigDecimal("18700"), new BigDecimal("20000")), "cambio");
        igual(0, CalculoDeVenta.cambio(new BigDecimal("18700"), new BigDecimal("18700")), "pago exacto");
        igual(0, CalculoDeVenta.cambio(BigDecimal.ZERO, BigDecimal.ZERO), "nada que cobrar");

        EfectivoRecibidoInvalidoException e = assertThrows(EfectivoRecibidoInvalidoException.class,
                () -> CalculoDeVenta.cambio(new BigDecimal("18700"), new BigDecimal("18699")));
        assertFalse(e.getMessage().contains("18699"), "el mensaje no repite el dato");
    }

    @Test
    void elEfectivoRecibidoAusenteONegativoOConCentavosEs400() {
        for (BigDecimal malo : new BigDecimal[] {null, new BigDecimal("-1"), new BigDecimal("20000.5")}) {
            assertThrows(EfectivoRecibidoInvalidoException.class,
                    () -> CalculoDeVenta.cambio(new BigDecimal("100"), malo), String.valueOf(malo));
        }
        assertThrows(EfectivoRecibidoInvalidoException.class,
                () -> CalculoDeVenta.cambio(BigDecimal.ZERO, new BigDecimal("-1")));
    }

    @Test
    void unTotalEnEfectivoAusenteONegativoEsUnErrorDeProgramacion() {
        assertThrows(IllegalArgumentException.class, () -> CalculoDeVenta.cambio(null, BigDecimal.TEN));
        assertThrows(IllegalArgumentException.class, () -> CalculoDeVenta.cambio(new BigDecimal("-1"), BigDecimal.TEN));
    }

    // ---- Números que vienen de fuera: nada que cuelgue el hilo (I1) --------------------------------

    private static final Duration PLAZO = Duration.ofSeconds(2);
    private static final List<String> EXPONENTES_EXTREMOS = List.of("1E+99999999", "1E-99999999");

    @Test
    void unDescuentoDeExponenteExtremoSeRechazaSinColgarElHilo() {
        for (String extremo : EXPONENTES_EXTREMOS) {
            BigDecimal valor = new BigDecimal(extremo);
            for (TipoDescuento tipo : TipoDescuento.values()) {
                Descuento d = new Descuento(tipo, valor);
                assertTimeoutPreemptively(PLAZO, () -> {
                    assertThrows(DescuentoInvalidoException.class, () -> CalculoDeVenta.calcular(
                            List.of(linea(1, 1, 10_000, SIN_IVA, "EXCLUIDO", d)), null), "en la línea " + tipo + " " + extremo);
                    assertThrows(DescuentoInvalidoException.class, () -> CalculoDeVenta.calcular(
                            List.of(simple(1, 1, 10_000)), d), "en el total " + tipo + " " + extremo);
                });
            }
        }
    }

    @Test
    void unEfectivoRecibidoDeExponenteExtremoSeRechazaSinColgarElHilo() {
        for (String extremo : EXPONENTES_EXTREMOS) {
            assertTimeoutPreemptively(PLAZO, () -> assertThrows(EfectivoRecibidoInvalidoException.class,
                    () -> CalculoDeVenta.cambio(new BigDecimal("100"), new BigDecimal(extremo)), extremo));
        }
    }

    @Test
    void unPrecioUnaTarifaOUnTotalEnEfectivoDeExponenteExtremoSeRechazanSinColgarElHilo() {
        for (String extremo : EXPONENTES_EXTREMOS) {
            BigDecimal valor = new BigDecimal(extremo);
            assertTimeoutPreemptively(PLAZO, () -> {
                assertThrows(IllegalArgumentException.class, () -> CalculoDeVenta.calcular(
                        List.of(new LineaPedida(1, 1, valor, SIN_IVA, "EXCLUIDO", null)), null), "precio " + extremo);
                assertThrows(IllegalArgumentException.class, () -> CalculoDeVenta.calcular(
                        List.of(new LineaPedida(1, 1, BigDecimal.TEN, valor, "GRAVADO", null)), null), "tarifa " + extremo);
                assertThrows(IllegalArgumentException.class,
                        () -> CalculoDeVenta.cambio(valor, BigDecimal.TEN), "total en efectivo " + extremo);
            });
        }
    }

    @Test
    void unNumeroConMasDeDoceCifrasEnterasOCuatroDecimalesSeRechaza() {
        BigDecimal trece = new BigDecimal("1000000000000");
        assertThrows(DescuentoInvalidoException.class, () -> CalculoDeVenta.calcular(
                List.of(simple(1, 1, 10_000)), new Descuento(TipoDescuento.PESOS, trece)));
        assertThrows(DescuentoInvalidoException.class, () -> CalculoDeVenta.calcular(
                List.of(simple(1, 1, 10_000)), new Descuento(TipoDescuento.PORCENTAJE, new BigDecimal("10.00001"))));
        assertThrows(EfectivoRecibidoInvalidoException.class, () -> CalculoDeVenta.cambio(BigDecimal.TEN, trece));
        assertThrows(IllegalArgumentException.class, () -> CalculoDeVenta.calcular(
                List.of(new LineaPedida(1, 1, trece, SIN_IVA, "EXCLUIDO", null)), null));
    }

    @Test
    void unaEscalaNegativaConValorRazonableSeAcepta() {
        // 1E+3 es 1 000: la notación científica no hace inválido un monto legítimo.
        Descuento mil = new Descuento(TipoDescuento.PESOS, new BigDecimal("1E+3"));
        Descuento cien = new Descuento(TipoDescuento.PORCENTAJE, new BigDecimal("1E+2"));

        igual(1_000, CalculoDeVenta.calcular(List.of(simple(1, 1, 5_000)), mil).descuento(), "1E+3 pesos");
        igual(5_000, CalculoDeVenta.calcular(List.of(simple(1, 1, 5_000)), cien).descuento(), "1E+2 por ciento");
        igual(1_000, CalculoDeVenta.cambio(new BigDecimal("9000"), new BigDecimal("1E+4")), "1E+4 recibidos");
    }

    // ---- Tope de importes (M1) -----------------------------------------------------------------

    @Test
    void unImporteQueNoCabeEnDecimal14Con2EsInvalido() {
        BigDecimal alto = new BigDecimal("999999999999");

        assertThrows(IllegalArgumentException.class, () -> CalculoDeVenta.calcular(
                List.of(new LineaPedida(1, Integer.MAX_VALUE, alto, SIN_IVA, "EXCLUIDO", null)), null));
        // Cada línea cabe, pero la venta no.
        assertThrows(IllegalArgumentException.class, () -> CalculoDeVenta.calcular(List.of(
                new LineaPedida(1, 1, alto, SIN_IVA, "EXCLUIDO", null),
                new LineaPedida(2, 1, alto, SIN_IVA, "EXCLUIDO", null)), null));
        // La línea cabe, pero con su IVA el total de la línea no.
        assertThrows(IllegalArgumentException.class, () -> CalculoDeVenta.calcular(
                List.of(new LineaPedida(1, 1, alto, IVA_19, "GRAVADO", null)), null));
    }

    @Test
    void elMayorImporteQueCabeSeAcepta() {
        VentaCalculada v = CalculoDeVenta.calcular(
                List.of(new LineaPedida(1, 1, new BigDecimal("999999999999"), SIN_IVA, "EXCLUIDO", null)), null);

        assertEquals(0, new BigDecimal("999999999999.00").compareTo(v.total()));
    }

    // ---- Tratamiento sin IVA con tarifa (M2) ---------------------------------------------------

    @Test
    void unTratamientoSinIvaConTarifaMayorQueCeroEsInvalido() {
        for (String t : new String[] {"EXENTO", "EXCLUIDO", "NO_GRAVADO", null}) {
            assertThrows(IllegalArgumentException.class, () -> CalculoDeVenta.calcular(
                    List.of(linea(1, 1, 1_000, IVA_19, t, null)), null), String.valueOf(t));
        }
    }

    @Test
    void gravadoConTarifaCeroYExcluidoHastaUmbralSiguenSiendoValidos() {
        CalculoDeVenta.calcular(List.of(linea(1, 1, 1_000, SIN_IVA, "GRAVADO", null)), null);
        CalculoDeVenta.calcular(List.of(linea(1, 1, 1_000, SIN_IVA, "EXCLUIDO_HASTA_UMBRAL", null)), null);
        CalculoDeVenta.calcular(List.of(linea(1, 1, 1_000, IVA_19, "EXCLUIDO_HASTA_UMBRAL", null)), null);
    }

    // ---- Pruebas baratas (M4) ------------------------------------------------------------------

    private static Map<Integer, Long> descuentoPorProducto(List<LineaPedida> lineas, Descuento total) {
        VentaCalculada v = CalculoDeVenta.calcular(lineas, total);
        Map<Integer, Long> porProducto = new HashMap<>();
        for (LineaCalculada l : v.lineas()) {
            porProducto.put(l.productoId(), l.descuento().longValueExact());
        }
        return porProducto;
    }

    private static void permutaciones(List<LineaPedida> lineas, int desde, List<List<LineaPedida>> salida) {
        if (desde == lineas.size()) {
            salida.add(new ArrayList<>(lineas));
            return;
        }
        for (int i = desde; i < lineas.size(); i++) {
            Collections.swap(lineas, desde, i);
            permutaciones(lineas, desde + 1, salida);
            Collections.swap(lineas, desde, i);
        }
    }

    @Test
    void unaPermutacionDeLasLineasDaElMismoRepartoPorProductoId() {
        // Empates de valor (se resuelven por productoId), valores distintos y el caso en cascada.
        record Escenario(List<LineaPedida> lineas, long total, Map<Integer, Long> esperado) {}
        List<Escenario> escenarios = List.of(
                new Escenario(List.of(simple(1, 1, 100), simple(2, 1, 100), simple(3, 1, 100)), 50,
                        Map.of(1, 16L, 2, 17L, 3, 17L)),
                new Escenario(List.of(simple(1, 1, 1_000), simple(2, 1, 1_000), simple(3, 1, 1_000)), 100,
                        Map.of(1, 34L, 2, 33L, 3, 33L)),
                new Escenario(List.of(simple(1, 1, 400), simple(2, 1, 450), simple(3, 1, 150)), 1,
                        Map.of(1, 0L, 2, 1L, 3, 0L)),
                new Escenario(List.of(simple(1, 1, 1), simple(2, 1, 1), simple(3, 1, 1), simple(4, 1, 1)), 2,
                        Map.of(1, 0L, 2, 0L, 3, 1L, 4, 1L)));
        for (Escenario e : escenarios) {
            List<List<LineaPedida>> todas = new ArrayList<>();
            permutaciones(new ArrayList<>(e.lineas()), 0, todas);
            for (List<LineaPedida> permutada : todas) {
                assertEquals(e.esperado(), descuentoPorProducto(permutada, pesos(e.total())), permutada.toString());
            }
        }
    }

    @Test
    void conBaseCeroPorElDescuentoTotalElIvaEsCero() {
        for (Descuento total : List.of(porcentaje("100"), pesos(10_000))) {
            VentaCalculada v = CalculoDeVenta.calcular(List.of(linea(1, 1, 10_000, IVA_19, "GRAVADO", null)), total);

            igual(0, v.lineas().get(0).base(), "base");
            igual(0, v.lineas().get(0).iva(), "iva de la línea");
            igual(0, v.iva(), "iva de la venta");
            igual(0, v.total(), "total");
        }
    }

    // ---- Propiedad -----------------------------------------------------------------------------

    private static final BigDecimal[] TARIFAS = {SIN_IVA, new BigDecimal("0.05"), IVA_19};

    private record Estadistica(int conResiduo, int enCascada, int totalIgualALaSuma) {}

    private static Descuento porcentajeConDecimales(Random azar) {
        return new Descuento(TipoDescuento.PORCENTAJE, BigDecimal.valueOf(azar.nextInt(10_001), 2));
    }

    private static Descuento descuentoPropio(Random azar, long subtotal) {
        return switch (azar.nextInt(4)) {
            case 0 -> null;
            case 1 -> porcentajeConDecimales(azar);
            case 2 -> porcentaje(String.valueOf(azar.nextInt(101)));
            default -> pesos(azar.nextLong(subtotal + 1));
        };
    }

    private static List<LineaPedida> generarLineas(Random azar, boolean pequenas) {
        int n = pequenas ? 2 + azar.nextInt(7) : 1 + azar.nextInt(6);
        List<LineaPedida> lineas = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int cantidad = pequenas ? 1 : 1 + azar.nextInt(5);
            long precio = pequenas ? azar.nextInt(4) : 1 + azar.nextInt(azar.nextBoolean() ? 100 : 50_000);
            Descuento propio = pequenas && azar.nextInt(4) > 0 ? null : descuentoPropio(azar, cantidad * precio);
            lineas.add(linea(azar.nextInt(pequenas ? 6 : 8), cantidad, precio,
                    TARIFAS[azar.nextInt(TARIFAS.length)], "GRAVADO", propio));
        }
        return lineas;
    }

    /** El descuento total que se pide y los pesos que deben salir (calculados aparte del código). */
    private record TotalPedido(Descuento descuento, long enPesos) {}

    private static TotalPedido generarTotal(Random azar, long sumaDeBases) {
        return switch (azar.nextInt(6)) {
            case 0 -> new TotalPedido(null, 0);
            case 1 -> {
                long pesos = azar.nextLong(sumaDeBases + 1);
                yield new TotalPedido(pesos(pesos), pesos);
            }
            case 2 -> new TotalPedido(pesos(sumaDeBases), sumaDeBases);
            case 3 -> new TotalPedido(porcentaje("100"), sumaDeBases);
            default -> {
                Descuento d = porcentajeConDecimales(azar);
                long pesos = BigDecimal.valueOf(sumaDeBases).multiply(d.valor())
                        .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValueExact();
                yield new TotalPedido(d, pesos);
            }
        };
    }

    private static long parteProporcional(long total, long base, long suma) {
        if (suma == 0) {
            return 0;
        }
        return BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(base))
                .divide(BigDecimal.valueOf(suma), 0, RoundingMode.HALF_UP).longValueExact();
    }

    /** La regla literal del diseño, escrita aparte: el residuo va entero a la línea mayor. */
    private static long[] repartoLiteral(List<LineaPedida> lineas, List<Long> bases, long total) {
        long suma = bases.stream().mapToLong(Long::longValue).sum();
        long[] partes = new long[bases.size()];
        long repartido = 0;
        for (int i = 0; i < partes.length; i++) {
            partes[i] = parteProporcional(total, bases.get(i), suma);
            repartido += partes[i];
        }
        int elegida = 0;
        for (int i = 1; i < partes.length; i++) {
            int porValor = Long.compare(bases.get(i), bases.get(elegida));
            if (porValor > 0 || (porValor == 0 && lineas.get(i).productoId() < lineas.get(elegida).productoId())) {
                elegida = i;
            }
        }
        partes[elegida] += total - repartido;
        return partes;
    }

    private static Estadistica ejercitar(Random azar, int casos, boolean pequenas) {
        int conResiduo = 0;
        int enCascada = 0;
        int totalIgualALaSuma = 0;

        for (int caso = 0; caso < casos; caso++) {
            String donde = " en el caso " + caso + (pequenas ? " (pequeñas)" : "");
            List<LineaPedida> lineas = generarLineas(azar, pequenas);
            int n = lineas.size();
            VentaCalculada sinTotal = CalculoDeVenta.calcular(lineas, null);
            List<Long> bases = sinTotal.lineas().stream().map(l -> l.base().longValueExact()).toList();
            long sumaDeBases = bases.stream().mapToLong(Long::longValue).sum();
            TotalPedido pedido = generarTotal(azar, sumaDeBases);

            VentaCalculada v = CalculoDeVenta.calcular(lineas, pedido.descuento());

            long[] literal = repartoLiteral(lineas, bases, pedido.enPesos());
            boolean literalValido = true;
            long redondeado = 0;
            for (int i = 0; i < n; i++) {
                literalValido &= literal[i] >= 0 && literal[i] <= bases.get(i);
                redondeado += parteProporcional(pedido.enPesos(), bases.get(i), sumaDeBases);
            }
            if (redondeado != pedido.enPesos()) {
                conResiduo++;
            }
            if (!literalValido) {
                enCascada++;
            }
            if (sumaDeBases > 0 && pedido.enPesos() == sumaDeBases) {
                totalIgualALaSuma++;
            }

            long repartido = 0;
            BigDecimal ivaEsperado = BigDecimal.ZERO;
            for (int i = 0; i < n; i++) {
                LineaCalculada con = v.lineas().get(i);
                LineaCalculada sin = sinTotal.lineas().get(i);
                long parte = con.descuento().longValueExact() - sin.descuento().longValueExact();
                repartido += parte;
                assertTrue(con.descuento().signum() >= 0, "descuento negativo" + donde);
                assertTrue(con.descuento().compareTo(con.subtotal()) <= 0, "descuento mayor que la línea" + donde);
                assertTrue(con.base().signum() >= 0, "base negativa" + donde);
                assertTrue(parte >= 0 && parte <= sin.base().longValueExact(), "parte fuera de la base" + donde);
                if (literalValido) {
                    assertEquals(literal[i], parte, "la parte sigue la regla literal" + donde);
                }
                BigDecimal iva = con.base().multiply(lineas.get(i).tarifaIva()).setScale(0, RoundingMode.HALF_UP);
                assertEquals(0, iva.compareTo(con.iva()), "iva = round(base · tarifa)" + donde);
                assertEquals(0, con.base().add(con.iva()).compareTo(con.total()), "total de línea" + donde);
                ivaEsperado = ivaEsperado.add(iva);
            }
            assertEquals(pedido.enPesos(), repartido, "lo repartido suma el descuento total" + donde);
            assertEquals(0, ivaEsperado.compareTo(v.iva()), "iva de la venta" + donde);
            assertEquals(0, v.subtotal().subtract(v.descuento()).add(v.iva()).compareTo(v.total()),
                    "total = subtotal − descuento + iva" + donde);
        }
        return new Estadistica(conResiduo, enCascada, totalIgualALaSuma);
    }

    /**
     * Combinaciones generadas con semilla fija. En cada una: lo repartido suma exactamente el
     * descuento total (calculado aparte), ningún descuento de línea es negativo ni pasa de su valor,
     * el IVA de cada línea es {@code round(base · tarifa)} y la venta cuadra. Cuando la regla
     * literal del diseño da un reparto válido, el resultado es exactamente ese.
     */
    @Test
    void propiedadDelRepartoYDeLosTotales() {
        Estadistica e = ejercitar(new Random(20261109L), 2_000, false);

        assertAll(
                () -> assertTrue(e.conResiduo() >= MIN_RESIDUO_GRANDES, "casos con residuo: " + e),
                () -> assertTrue(e.totalIgualALaSuma() >= MIN_IGUAL_GRANDES, "casos con total igual a la suma: " + e));
    }

    /** Bases de 0 a 3 pesos: aquí el residuo es la regla y la cascada aparece a menudo. */
    @Test
    void propiedadDelRepartoConBasesPequenasEjercitaLaCascada() {
        Estadistica e = ejercitar(new Random(20261110L), 20_000, true);

        assertAll(
                () -> assertTrue(e.conResiduo() >= MIN_RESIDUO_PEQUENAS, "casos con residuo: " + e),
                () -> assertTrue(e.enCascada() >= MIN_CASCADA, "casos en cascada: " + e),
                () -> assertTrue(e.totalIgualALaSuma() >= MIN_IGUAL_PEQUENAS, "casos con total igual a la suma: " + e));
    }

    private static final int MIN_RESIDUO_GRANDES = 150;
    private static final int MIN_IGUAL_GRANDES = 400;
    private static final int MIN_RESIDUO_PEQUENAS = 3_000;
    private static final int MIN_CASCADA = 40;
    private static final int MIN_IGUAL_PEQUENAS = 5_000;
}

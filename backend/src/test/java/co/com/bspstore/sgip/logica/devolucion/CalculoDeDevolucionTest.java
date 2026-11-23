package co.com.bspstore.sgip.logica.devolucion;

import co.com.bspstore.sgip.api.dto.UnidadDevuelta;
import co.com.bspstore.sgip.logica.VentaInvalidaException;
import co.com.bspstore.sgip.logica.devolucion.CalculoDeDevolucion.LineaVendida;
import co.com.bspstore.sgip.logica.devolucion.CalculoDeDevolucion.Resultado;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Lo que vale devolver unidades de una línea (RF-68): {@code HALF_UP(total · k / n)} y, para la
 * devolución que completa la línea, el resto. Sin base de datos.
 */
class CalculoDeDevolucionTest {

    private static BigDecimal pesos(String v) {
        return new BigDecimal(v);
    }

    private static void igual(String esperado, BigDecimal obtenido) {
        assertEquals(0, pesos(esperado).compareTo(obtenido), "esperaba " + esperado + " y fue " + obtenido);
    }

    private static LineaVendida linea(String total, int cantidad, int yaDevueltas, String yaDevuelto) {
        return new LineaVendida(1, cantidad, pesos(total), yaDevueltas, pesos(yaDevuelto));
    }

    @Test
    void lasDevolucionesDeUnaLineaSumanSuTotal() {
        // Una línea de 1 000 con 3 unidades: 333, 333 y la que completa toma el resto, 334.
        BigDecimal primera = CalculoDeDevolucion.valorDe(linea("1000", 3, 0, "0"), 1);
        BigDecimal segunda = CalculoDeDevolucion.valorDe(linea("1000", 3, 1, primera.toPlainString()), 1);
        BigDecimal ultima = CalculoDeDevolucion.valorDe(
                linea("1000", 3, 2, primera.add(segunda).toPlainString()), 1);

        igual("333", primera);
        igual("333", segunda);
        igual("334", ultima);
        igual("1000", primera.add(segunda).add(ultima));
    }

    @Test
    void devolverDosDeTresDeUnaVezValeSeiscientosSesentaYSiete() {
        igual("667", CalculoDeDevolucion.valorDe(linea("1000", 3, 0, "0"), 2));
    }

    @Test
    void laQueCompletaLaLineaTomaElRestoAunqueElRedondeoDiga() {
        // 1 000 / 3 = 333,33: la última unidad no vale 333 sino lo que falta para sumar el total.
        igual("667", CalculoDeDevolucion.valorDe(linea("1000", 3, 1, "333"), 2));
        igual("1000", CalculoDeDevolucion.valorDe(linea("1000", 3, 0, "0"), 3));
    }

    @Test
    void elValorSeRedondeaHalfUpAPesosEnteros() {
        igual("1", CalculoDeDevolucion.valorDe(linea("1", 2, 0, "0"), 1)); // 0,5 sube
        igual("2667", CalculoDeDevolucion.valorDe(linea("2667", 1, 0, "0"), 1));
        igual("2", CalculoDeDevolucion.valorDe(linea("5", 3, 0, "0"), 1)); // 1,67
    }

    @Test
    void elValorNuncaSuperaLoQueFaltaDeLaLineaNiQuedaNegativo() {
        // Cinco pesos en diez unidades: devolver una a una redondea 0,5 hacia arriba cada vez; sin tope, la
        // sexta ya pasaría del total y la que completa quedaría en negativo.
        BigDecimal devuelto = BigDecimal.ZERO;
        for (int devueltas = 0; devueltas < 10; devueltas++) {
            BigDecimal valor = CalculoDeDevolucion.valorDe(linea("5", 10, devueltas, devuelto.toPlainString()), 1);
            assertEquals(true, valor.signum() >= 0, "valor " + valor);
            devuelto = devuelto.add(valor);
        }
        igual("5", devuelto);
    }

    @Test
    void masDeLoQueQuedaEs400() {
        assertThrows(VentaInvalidaException.class, () -> CalculoDeDevolucion.valorDe(linea("1000", 3, 2, "666"), 2));
        assertThrows(VentaInvalidaException.class, () -> CalculoDeDevolucion.valorDe(linea("1000", 3, 3, "1000"), 1));
    }

    // ------------------------------------------------------- varias líneas

    @Test
    void sinUnidadesPedidasSeDevuelveLoQueQuedaDeCadaLinea() {
        List<LineaVendida> lineas = List.of(
                new LineaVendida(1, 3, pesos("1000"), 1, pesos("333")),
                new LineaVendida(2, 2, pesos("500"), 2, pesos("500")),
                new LineaVendida(3, 1, pesos("200"), 0, pesos("0")));

        Resultado r = CalculoDeDevolucion.calcular(lineas, List.of());

        assertEquals(2, r.unidades().size(), "la línea ya devuelta del todo no aparece");
        assertEquals(1, r.unidades().get(0).productoId());
        assertEquals(2, r.unidades().get(0).cantidad());
        igual("667", r.unidades().get(0).valor());
        assertEquals(3, r.unidades().get(1).productoId());
        igual("867", r.valor());
        assertEquals(0, r.unidadesQueQuedan());
    }

    @Test
    void conUnidadesPedidasSoloSeDevuelvenEsasYSeCuentaLoQueQueda() {
        List<LineaVendida> lineas = List.of(
                new LineaVendida(1, 3, pesos("1000"), 0, pesos("0")),
                new LineaVendida(2, 2, pesos("500"), 0, pesos("0")));

        Resultado r = CalculoDeDevolucion.calcular(lineas, List.of(new UnidadDevuelta(1, 1)));

        assertEquals(1, r.unidades().size());
        igual("333", r.valor());
        assertEquals(4, r.unidadesQueQuedan());
    }

    @Test
    void devolverTodoPorUnidadesDejaCeroPorDevolver() {
        List<LineaVendida> lineas = List.of(new LineaVendida(1, 3, pesos("1000"), 0, pesos("0")),
                new LineaVendida(2, 2, pesos("500"), 0, pesos("0")));

        Resultado r = CalculoDeDevolucion.calcular(lineas, List.of(new UnidadDevuelta(1, 3), new UnidadDevuelta(2, 2)));

        igual("1500", r.valor());
        assertEquals(0, r.unidadesQueQuedan());
    }

    @Test
    void unProductoQueNoEstaEnLaVentaOMasDeLoVendidoEs400() {
        List<LineaVendida> lineas = List.of(new LineaVendida(1, 3, pesos("1000"), 2, pesos("666")));

        assertThrows(VentaInvalidaException.class,
                () -> CalculoDeDevolucion.calcular(lineas, List.of(new UnidadDevuelta(9, 1))));
        assertThrows(VentaInvalidaException.class,
                () -> CalculoDeDevolucion.calcular(lineas, List.of(new UnidadDevuelta(1, 2))),
                "una unidad queda y se pidieron dos: cuenta lo devuelto antes");
    }
}

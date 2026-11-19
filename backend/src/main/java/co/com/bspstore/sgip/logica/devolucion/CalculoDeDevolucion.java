package co.com.bspstore.sgip.logica.devolucion;

import co.com.bspstore.sgip.api.dto.UnidadDevuelta;
import co.com.bspstore.sgip.logica.VentaInvalidaException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lo que vale devolver unidades de una venta (RF-68), sin base de datos ni transacción: recibe lo que
 * la venta guardó y lo que ya se devolvió, y dice qué se devuelve ahora y por cuánto.
 *
 * <h2>El valor</h2>
 *
 * El valor devuelto de {@code k} unidades de una línea de {@code n} es
 * {@code HALF_UP(total de la línea · k / n)}, a pesos enteros, donde el total de la línea es el ya
 * descontado y con IVA ({@code subtotal − descuento + valor_iva}). La devolución que <b>completa</b> la
 * línea (las {@code k} son todas las que quedaban) toma el resto: el total de la línea menos lo ya
 * devuelto. Así la suma de todas las devoluciones de una línea es exactamente su total, aunque cada
 * parte se haya redondeado: una línea de 1 000 con tres unidades devuelve 333, 333 y 334.
 *
 * Una parte que no completa la línea nunca pasa de lo que falta devolver de ella. Con totales
 * pequeños frente a las unidades (una línea muy descontada) el redondeo hacia arriba de varias partes
 * podría sumar más que el total y dejar la última en negativo; el tope lo impide.
 *
 * <h2>Las unidades</h2>
 *
 * Una lista vacía devuelve todo lo que aún no se había devuelto de cada línea (la anulación). Una
 * unidad de un producto que no está en la venta, o más de lo que quedaba, es un 400 con texto fijo.
 */
public final class CalculoDeDevolucion {

    static final String MAS_DE_LO_VENDIDO = "No se puede devolver más de lo vendido.";
    static final String PRODUCTO_AJENO = "El producto no está en la venta.";

    /** Una línea de la venta con lo que ya se devolvió de ella. */
    public record LineaVendida(Integer productoId, int cantidad, BigDecimal total, int yaDevueltas,
                               BigDecimal yaDevuelto) {

        int quedan() {
            return cantidad - yaDevueltas;
        }
    }

    /** Las unidades de un producto que se devuelven ahora y lo que valen. */
    public record UnidadesDevueltas(Integer productoId, int cantidad, BigDecimal valor) {}

    /** Lo que se devuelve ahora, lo que vale y cuántas unidades de la venta siguen sin devolver. */
    public record Resultado(List<UnidadesDevueltas> unidades, BigDecimal valor, int unidadesQueQuedan) {}

    private CalculoDeDevolucion() {}

    /** Lo que vale devolver {@code unidades} de esa línea ahora. 400 si pasan de las que quedan. */
    public static BigDecimal valorDe(LineaVendida linea, int unidades) {
        int quedan = linea.quedan();
        if (unidades <= 0 || unidades > quedan) {
            throw new VentaInvalidaException(MAS_DE_LO_VENDIDO);
        }
        BigDecimal loQueFalta = linea.total().subtract(linea.yaDevuelto());
        BigDecimal valor;
        if (unidades == quedan) {
            valor = loQueFalta;
        } else {
            valor = linea.total().multiply(BigDecimal.valueOf(unidades))
                    .divide(BigDecimal.valueOf(linea.cantidad()), 0, RoundingMode.HALF_UP).min(loQueFalta);
        }
        return valor.max(BigDecimal.ZERO).setScale(2, RoundingMode.UNNECESSARY);
    }

    /**
     * Qué se devuelve ahora: lo pedido, o todo lo que quede si no se pidió nada. Las unidades pedidas
     * vienen sin repetir ({@code NormalizadorDeDevolucion} las fusiona).
     */
    public static Resultado calcular(List<LineaVendida> lineas, List<UnidadDevuelta> pedidas) {
        Map<Integer, LineaVendida> porProducto = new HashMap<>();
        for (LineaVendida l : lineas) {
            porProducto.put(l.productoId(), l);
        }
        List<UnidadesDevueltas> devueltas = new ArrayList<>();
        if (pedidas.isEmpty()) {
            for (LineaVendida l : lineas) {
                if (l.quedan() > 0) {
                    devueltas.add(new UnidadesDevueltas(l.productoId(), l.quedan(), valorDe(l, l.quedan())));
                }
            }
        } else {
            for (UnidadDevuelta u : pedidas) {
                LineaVendida l = porProducto.get(u.productoId());
                if (l == null) {
                    throw new VentaInvalidaException(PRODUCTO_AJENO);
                }
                devueltas.add(new UnidadesDevueltas(l.productoId(), u.cantidad(), valorDe(l, u.cantidad())));
            }
        }
        BigDecimal valor = BigDecimal.ZERO.setScale(2);
        int quedan = lineas.stream().mapToInt(LineaVendida::quedan).sum();
        for (UnidadesDevueltas d : devueltas) {
            valor = valor.add(d.valor());
            quedan -= d.cantidad();
        }
        return new Resultado(List.copyOf(devueltas), valor, quedan);
    }
}

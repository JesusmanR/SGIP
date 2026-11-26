package co.com.bspstore.sgip.logica.venta;

import co.com.bspstore.sgip.datos.RepositorioVentas.PrecioVigente;
import co.com.bspstore.sgip.logica.VentaInvalidaException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Cuándo un producto se puede vender y a qué precio: la regla que comparten la caja
 * ({@code RegistradorDeVenta}) y la cotización ({@code ServicioCotizaciones}). Así una cotización congela
 * exactamente el triple (precio, tarifa, tratamiento) que hoy cobraría la caja, y no hay dos reglas que se
 * puedan separar.
 *
 * Es lógica pura, sin base de datos: recibe el precio que la vista de precios dio para el producto.
 */
public final class LineaVendible {

    /** Lo que cabe en {@code DECIMAL(14,2)} sin centavos: 12 dígitos. */
    private static final BigDecimal PRECIO_MAXIMO = new BigDecimal("999999999999");
    /** La suma de los subtotales tiene que dejar sitio al IVA (hasta el 100 %) dentro de DECIMAL(14,2). */
    private static final BigDecimal SUBTOTAL_MAXIMO = new BigDecimal("499999999999");

    public static final String NO_VENDIBLE = "Este producto no se puede vender: no tiene un precio de venta válido.";
    public static final String IMPORTE_DESMEDIDO = "El importe de la venta es demasiado grande para guardarse.";

    private LineaVendible() {}

    /**
     * La línea con el precio del servidor, o vacío si el producto no se puede vender: sin precio, con
     * precio cero o desmedido, o con una tarifa o un tratamiento que el cálculo no admite. Lo último
     * se averigua ensayando el propio cálculo con una unidad, de modo que la regla es una sola.
     *
     * El precio se redondea a pesos con HALF_UP, y una tarifa nula vale cero.
     */
    public static Optional<LineaPedida> de(Integer productoId, int cantidad, Descuento descuento, PrecioVigente v) {
        if (v.precio() == null) {
            return Optional.empty();
        }
        BigDecimal precio = v.precio().setScale(0, RoundingMode.HALF_UP);
        if (precio.signum() <= 0 || precio.compareTo(PRECIO_MAXIMO) > 0) {
            return Optional.empty();
        }
        BigDecimal tarifa = v.tarifa() == null ? BigDecimal.ZERO : v.tarifa();
        try {
            CalculoDeVenta.calcular(List.of(new LineaPedida(productoId, 1, precio, tarifa, v.tratamiento(), null)), null);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return Optional.of(new LineaPedida(productoId, cantidad, precio, tarifa, v.tratamiento(), descuento));
    }

    /** Que la suma de los subtotales quepa, con su IVA, en DECIMAL(14,2) antes de calcular. */
    public static void toparSubtotal(List<LineaPedida> pedidas) {
        BigDecimal suma = BigDecimal.ZERO;
        for (LineaPedida p : pedidas) {
            suma = suma.add(p.precioUnitario().multiply(BigDecimal.valueOf(p.cantidad())));
            if (suma.compareTo(SUBTOTAL_MAXIMO) > 0) {
                throw new VentaInvalidaException(IMPORTE_DESMEDIDO);
            }
        }
    }
}

package co.com.bspstore.sgip.logica.venta;

import java.math.BigDecimal;
import java.util.List;

/**
 * El resultado de calcular una venta. {@code descuento} es la suma de los descuentos de todas las
 * líneas (el propio más el repartido), de modo que {@code total = subtotal − descuento + iva}.
 */
public record VentaCalculada(List<LineaCalculada> lineas, BigDecimal subtotal, BigDecimal descuento,
        BigDecimal iva, BigDecimal total, boolean tieneDescuento) {
}

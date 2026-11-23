package co.com.bspstore.sgip.logica.venta;

import java.math.BigDecimal;

/**
 * Una línea con todo lo que se cobra. {@code descuento} suma el propio de la línea y la parte que le
 * tocó del descuento sobre toda la compra; {@code base = subtotal − descuento} y
 * {@code total = base + iva}. Los importes son pesos enteros con escala 2, la de {@code venta_items}.
 */
public record LineaCalculada(Integer productoId, int cantidad, BigDecimal precioUnitario,
        BigDecimal subtotal, BigDecimal descuento, BigDecimal base, BigDecimal tarifaIva,
        String tratamientoIva, BigDecimal iva, BigDecimal total) {
}

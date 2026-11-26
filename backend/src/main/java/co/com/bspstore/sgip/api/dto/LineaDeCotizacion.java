package co.com.bspstore.sgip.api.dto;

import java.math.BigDecimal;

/**
 * Una línea de una cotización tal como se guardó: el precio, la tarifa y el tratamiento congelados al cotizar,
 * y lo que sumó cada una (el impuesto redondeado a pesos con la misma regla de la venta).
 *
 * {@code precioHoy} y {@code disponible} solo dicen algo en una cotización vencida: el precio sin IVA que la caja
 * cobraría hoy, o, si el producto está inactivo o ya no tiene precio, {@code disponible = false} y {@code precioHoy}
 * nulo. En cualquier otro estado {@code precioHoy} es nulo y {@code disponible} es verdadero.
 */
public record LineaDeCotizacion(Integer productoId, String codigo, String nombre, int cantidad,
                                BigDecimal precioUnitario, BigDecimal tarifaIva, String tratamientoIva,
                                BigDecimal subtotal, BigDecimal valorIva, BigDecimal precioHoy, boolean disponible) {
}

package co.com.bspstore.sgip.api.dto;

import java.math.BigDecimal;

/**
 * Una línea de una cotización tal como se guardó: el precio, la tarifa y el tratamiento congelados al cotizar,
 * y lo que sumó cada una (el impuesto redondeado a pesos con la misma regla de la venta).
 *
 * {@code disponible} dice si la línea se puede cobrar (RF-17 prohíbe vender un producto inactivo):
 * <ul>
 *   <li>en una cotización <b>vencida</b>, si el producto tiene precio hoy: {@code precioHoy} trae el precio sin IVA
 *       que la caja cobraría; si el producto está inactivo o ya no tiene precio, {@code disponible = false} y
 *       {@code precioHoy} es nulo;</li>
 *   <li>en cualquier otro estado, {@code precioHoy} es nulo y {@code disponible} es verdadero mientras el producto
 *       siga activo, aunque hoy no tenga regla de precio: el precio congelado vale.</li>
 * </ul>
 */
public record LineaDeCotizacion(Integer productoId, String codigo, String nombre, int cantidad,
                                BigDecimal precioUnitario, BigDecimal tarifaIva, String tratamientoIva,
                                BigDecimal subtotal, BigDecimal valorIva, BigDecimal precioHoy, boolean disponible) {
}

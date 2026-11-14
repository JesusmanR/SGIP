package co.com.bspstore.sgip.logica.venta;

import java.math.BigDecimal;

/**
 * Una línea de la venta tal como la pide el mostrador, ya con el precio que fijó el servidor.
 * El {@code precioUnitario} es SIN IVA; {@code descuento} puede ser nulo.
 */
public record LineaPedida(Integer productoId, int cantidad, BigDecimal precioUnitario,
        BigDecimal tarifaIva, String tratamientoIva, Descuento descuento) {
}

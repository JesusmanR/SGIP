package co.com.bspstore.sgip.logica.venta;

import java.math.BigDecimal;

/** Un descuento pedido, en porcentaje (0 a 100) o en pesos enteros. Se valida al calcular. */
public record Descuento(TipoDescuento tipo, BigDecimal valor) {
}

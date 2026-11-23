package co.com.bspstore.sgip.logica.venta;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Un descuento pedido, en porcentaje (0 a 100) o en pesos enteros. {@code CalculoDeVenta} lo
 * valida al calcular; las anotaciones son la primera puerta cuando llega por la API: un
 * descuento que se envía es un descuento de verdad (mayor que cero) y cabe en lo que el
 * cálculo admite (hasta 12 cifras enteras y los decimales de una columna de dinero).
 */
public record Descuento(
        @NotNull(message = "El descuento necesita un tipo")
        TipoDescuento tipo,

        @NotNull(message = "El descuento necesita un valor")
        @Positive(message = "El descuento debe ser mayor que cero")
        @Digits(integer = 12, fraction = 2, message = "El descuento admite hasta doce enteros y dos decimales")
        BigDecimal valor) {
}

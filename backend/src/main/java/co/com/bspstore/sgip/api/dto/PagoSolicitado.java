package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.logica.venta.LimitesDeVenta;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Una parte de lo que paga el cliente: el medio, el monto en pesos enteros y, según el medio,
 * la referencia de la tarjeta o de la transferencia o el efectivo recibido (solo en efectivo,
 * para calcular el cambio).
 */
public record PagoSolicitado(

        @NotNull(message = "El pago necesita un medio de pago")
        @Positive(message = "El medio de pago no es válido")
        Integer medioId,

        @NotNull(message = "El pago necesita un monto")
        @Positive(message = "El monto del pago debe ser mayor que cero")
        @Digits(integer = 12, fraction = 2, message = "El monto del pago admite hasta doce enteros y dos decimales")
        BigDecimal monto,

        @Positive(message = "El efectivo recibido debe ser mayor que cero")
        @Digits(integer = 12, fraction = 2, message = "El efectivo recibido admite hasta doce enteros y dos decimales")
        BigDecimal recibido,

        @Size(max = LimitesDeVenta.REFERENCIA, message = "La referencia admite hasta 60 caracteres")
        String referencia
) {
}

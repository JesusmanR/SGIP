package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.logica.venta.LimitesDeVenta;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Una parte de lo que se le devuelve al cliente: el medio, el monto en pesos enteros y la referencia
 * cuando el reembolso de tarjeta o de transferencia se hizo por fuera (datáfono o banco). Con el medio
 * de sistema «Saldo a favor» el dinero no sale de la caja: se abona al saldo del cliente.
 */
public record Reembolso(

        @NotNull(message = "El reembolso necesita un medio de pago")
        @Positive(message = "El medio de pago no es válido")
        Integer medioId,

        @NotNull(message = "El reembolso necesita un monto")
        @Positive(message = "El monto del reembolso debe ser mayor que cero")
        @Digits(integer = 12, fraction = 2, message = "El monto del reembolso admite hasta doce enteros y dos decimales")
        BigDecimal monto,

        @Size(max = LimitesDeVenta.REFERENCIA, message = "La referencia admite hasta 60 caracteres")
        String referencia
) {
}

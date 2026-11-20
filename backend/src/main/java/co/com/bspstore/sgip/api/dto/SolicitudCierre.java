package co.com.bspstore.sgip.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Cerrar el turno de caja (RF-45): el efectivo que el cajero contó en el cajón y, si no coincide con lo
 * esperado, la explicación.
 *
 * El contado es obligatorio y va en pesos enteros, como la base de la apertura: la anotación deja pasar
 * hasta dos decimales (lo que cabe en la columna) y quien rechaza los centavos distintos de cero es el
 * servicio, con un 400. La observación cabe en los 200 caracteres de la columna. Que la diferencia la
 * exija depende del esperado, que solo se conoce con el turno bloqueado, y por eso lo decide el
 * servicio. Los mensajes no repiten lo escrito.
 */
public record SolicitudCierre(

        @NotNull(message = "El efectivo contado es obligatorio")
        @DecimalMin(value = "0", message = "El efectivo contado no puede ser negativo")
        @Digits(integer = 12, fraction = 2,
                message = "El efectivo contado va en pesos enteros y de hasta 12 dígitos")
        BigDecimal efectivoContado,

        @Size(max = 200, message = "La observación admite hasta 200 caracteres")
        String observacion
) {
}

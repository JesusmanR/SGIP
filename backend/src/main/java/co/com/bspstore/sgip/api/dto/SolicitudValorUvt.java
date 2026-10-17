package co.com.bspstore.sgip.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * El valor de la unidad de valor tributario que se fija para un año
 * (RF-05). El año va en la ruta y no aquí, para que no pueda discrepar.
 *
 * El mínimo no es cero sino un peso: una UVT en cero haría que todos los
 * umbrales del Art. 424 valieran cero y, de golpe, todo producto quedaría
 * gravado. La columna ya lo prohíbe con {@code ck_uvt_valor}; aquí se
 * repite para que el rechazo llegue como un 400 con su motivo en lugar
 * de como un error de integridad.
 */
public record SolicitudValorUvt(

        @NotNull(message = "El valor es obligatorio")
        @DecimalMin(value = "1.00", message = "El valor de la UVT tiene que ser mayor que cero")
        @Digits(integer = 10, fraction = 2,
                message = "El valor admite hasta diez enteros y dos decimales")
        BigDecimal valor,

        @Size(max = 60, message = "La resolución no puede pasar de 60 caracteres")
        String resolucion,

        @Size(max = 200, message = "El motivo no puede pasar de 200 caracteres")
        String motivo
) {}

package co.com.bspstore.sgip.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Lo que se puede cambiar de un tipo de impuesto nacional (RF-05).
 *
 * No están ni el código ni el tratamiento, y no es un olvido. El código
 * es la referencia por la que `reglas_iva` apunta a este tipo y por la
 * que la bitácora lo nombra; cambiarlo rompería las dos. El tratamiento
 * no es un ajuste sino la clase de impuesto: pasar de
 * EXCLUIDO_HASTA_UMBRAL a GRAVADO no es corregir una cifra, es decidir
 * que otra norma aplica, y eso se hace dando de alta otro tipo y
 * reapuntando las reglas, donde se ve lo que se está haciendo.
 *
 * La tarifa se expresa como fracción y no como porcentaje —0,1900 y no
 * 19— porque es como la guarda la columna y como la multiplican las
 * vistas. Convertirla en la frontera dejaría dos convenciones vivas y la
 * primera confusión costaría un 1900 % de impuesto.
 */
public record SolicitudTipoImpuesto(

        @NotNull(message = "La tarifa es obligatoria")
        @DecimalMin(value = "0.0000", message = "La tarifa no puede ser negativa")
        @DecimalMax(value = "1.0000",
                message = "La tarifa es una fracción: 0,1900 para el 19 %, nunca mayor que 1")
        @Digits(integer = 1, fraction = 4,
                message = "La tarifa admite un entero y cuatro decimales")
        BigDecimal tarifa,

        /**
         * Obligatorio si el tratamiento es EXCLUIDO_HASTA_UMBRAL, y sin
         * sentido en los demás. No se puede exigir con una anotación
         * porque depende del tratamiento, que vive en la base; lo
         * comprueba el servicio.
         */
        @DecimalMin(value = "0.01", message = "El umbral en UVT tiene que ser mayor que cero")
        @Digits(integer = 6, fraction = 2,
                message = "El umbral admite hasta seis enteros y dos decimales")
        BigDecimal umbralUvt,

        @Size(max = 160, message = "El fundamento no puede pasar de 160 caracteres")
        String fundamento,

        @Size(max = 200, message = "El motivo no puede pasar de 200 caracteres")
        String motivo
) {}

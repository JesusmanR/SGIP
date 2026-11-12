package co.com.bspstore.sgip.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Abrir el turno de caja (RF-45): el efectivo con que el cajero empieza, la base.
 *
 * Es obligatoria; quien abre sin base manda cero. Va en pesos enteros (RNF-16) y cabe en
 * la columna {@code DECIMAL(14,2)}: 12 dígitos. Las reglas están aquí para dar un 400
 * antes de llegar al servicio, y el servicio las repite porque no depende de quién lo
 * llame. Los mensajes no repiten lo escrito.
 */
public record SolicitudApertura(

        @NotNull(message = "La base de efectivo es obligatoria")
        @DecimalMin(value = "0", message = "La base de efectivo no puede ser negativa")
        @Digits(integer = 12, fraction = 0,
                message = "La base de efectivo va en pesos enteros y de hasta 12 dígitos")
        BigDecimal baseEfectivo
) {
}

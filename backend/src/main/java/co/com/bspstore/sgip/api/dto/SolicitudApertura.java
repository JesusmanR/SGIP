package co.com.bspstore.sgip.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Abrir el turno de caja (RF-45): el efectivo con que el cajero empieza, la base.
 *
 * Es obligatoria; quien abre sin base manda cero. Va en pesos enteros (RNF-16) y cabe en
 * la columna {@code DECIMAL(14,2)}: 12 dígitos. Un formulario que formatea el monto con dos
 * decimales manda {@code 50000.00}, que son pesos enteros y se acepta; por eso la anotación
 * deja pasar hasta dos decimales (lo que cabe en la columna) y quien decide que los centavos
 * distintos de cero no valen es el servicio, que responde 400. Las demás reglas están aquí
 * para dar un 400 antes de llegar al servicio, y el servicio las repite porque no depende de
 * quién lo llame. Los mensajes no repiten lo escrito.
 */
public record SolicitudApertura(

        @NotNull(message = "La base de efectivo es obligatoria")
        @DecimalMin(value = "0", message = "La base de efectivo no puede ser negativa")
        @Digits(integer = 12, fraction = 2,
                message = "La base de efectivo va en pesos enteros y de hasta 12 dígitos")
        BigDecimal baseEfectivo
) {
}

package co.com.bspstore.sgip.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Una regla de ganancia que se crea o se corrige (RF-30, RF-32).
 *
 * `categoriaId` y `proveedorId` admiten nulo, y esa es la forma de
 * expresar el alcance: las dos vacías es la regla general de la empresa,
 * una de las dos acota, y las dos juntas es la más específica. No hay un
 * campo «alcance» que pudiera contradecir a las columnas.
 *
 * El factor es **uno más el porcentaje**, para las dos bases: quien
 * configura un 20 % escribe 1,2000 tanto si es recargo sobre el costo
 * como si es margen sobre el precio, y lo que cambia es la fórmula que
 * lo aplica. El mínimo de 1,0001 es el `ck_reglas_margen_factor` del
 * esquema: un factor de 1 sería vender al costo, que no es una regla de
 * ganancia sino la ausencia de una.
 *
 * El tope depende de la base y por eso no se puede expresar con una
 * anotación: en el margen sobre el precio, el factor tiene que quedar
 * por debajo de 2 porque un margen del 100 % significaría precio
 * infinito. Lo comprueba el servicio, y la restricción
 * `ck_reglas_margen_sobre_precio` lo sostiene por debajo.
 */
public record SolicitudReglaMargen(

        Integer categoriaId,

        Integer proveedorId,

        @NotBlank(message = "La forma de pago es obligatoria")
        @Pattern(regexp = "CONTADO|CREDITO",
                message = "La forma de pago es CONTADO o CREDITO")
        String formaPago,

        @NotBlank(message = "La forma de cálculo es obligatoria")
        @Pattern(regexp = "SOBRE_COSTO|SOBRE_PRECIO",
                message = "La forma de cálculo es SOBRE_COSTO o SOBRE_PRECIO")
        String formaCalculo,

        @NotNull(message = "El factor es obligatorio")
        @DecimalMin(value = "1.0001",
                message = "El factor tiene que ser mayor que 1: con 1 se vendería al costo")
        @Digits(integer = 2, fraction = 4,
                message = "El factor admite dos enteros y cuatro decimales")
        BigDecimal factor,

        @NotNull(message = "La fecha de vigencia es obligatoria")
        LocalDate vigenteDesde,

        LocalDate vigenteHasta
) {}

package co.com.bspstore.sgip.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * La configuración tributaria que el administrador fija de una vez (PUT).
 *
 * Es un PUT y lleva todos los campos: guardar es decir «así queda», y un
 * campo ausente no puede significar «falso» sin que nadie lo note. Por eso
 * los interruptores son {@code Boolean} obligatorios y no {@code boolean}.
 *
 * Los límites repiten los del esquema ({@code ck_empresa_config_ica} y
 * {@code ck_empresa_config_vigencia}) para que el rechazo llegue como un
 * 400 con su motivo y no como un error de integridad. Que el ICA exija
 * practicar retenciones no se puede expresar con una anotación, porque
 * compara dos campos: lo comprueba el servicio.
 */
public record SolicitudConfiguracionTributaria(

        @NotNull(message = "Indica si la empresa es responsable de IVA")
        Boolean responsableIva,

        @NotNull(message = "Indica si la empresa aplica la exclusión por umbral")
        Boolean aplicaExclusionUmbral,

        @NotNull(message = "Indica si la empresa practica retenciones")
        Boolean practicaRetenciones,

        @DecimalMin(value = "0", message = "La tarifa de ICA no puede ser negativa")
        @DecimalMax(value = "1", message = "La tarifa de ICA es una fracción entre 0 y 1")
        @Digits(integer = 1, fraction = 6,
                message = "La tarifa de ICA admite hasta seis decimales")
        BigDecimal tarifaIca,

        @NotNull(message = "Indica si la empresa maneja envíos")
        Boolean manejaEnvios,

        @NotNull(message = "La vigencia de la cotización es obligatoria")
        @Min(value = 1, message = "La cotización vale al menos un día")
        @Max(value = 365, message = "La cotización no puede valer más de 365 días")
        Integer cotizacionVigenciaDias
) {}

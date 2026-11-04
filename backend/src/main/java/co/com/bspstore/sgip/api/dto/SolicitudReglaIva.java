package co.com.bspstore.sgip.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Una regla de IVA que se crea.
 *
 * El alcance se expresa con las dos columnas, como en la regla de margen:
 * las dos vacías es la regla general de la empresa, {@code categoriaId}
 * acota a una categoría y {@code productoId} a un producto. Las dos a la
 * vez no tienen sentido —la precedencia de {@code v_regla_iva} sería
 * ambigua—, y como la restricción del esquema quedó fuera de la cadena
 * (database/extras/restriccion_ambito_regla_iva.sql) lo comprueba el
 * servicio.
 *
 * {@code codigoImpuesto} nombra un tipo del catálogo nacional: la empresa
 * escoge cuál le aplica, no cuánto vale (ADR-0016).
 */
public record SolicitudReglaIva(

        Integer categoriaId,

        Integer productoId,

        @NotBlank(message = "El código del impuesto es obligatorio")
        @Size(max = 30, message = "El código del impuesto admite hasta 30 caracteres")
        String codigoImpuesto,

        @NotNull(message = "La fecha de inicio de la vigencia es obligatoria")
        LocalDate vigenteDesde,

        LocalDate vigenteHasta,

        @Size(max = 160, message = "El fundamento admite hasta 160 caracteres")
        String fundamento
) {}

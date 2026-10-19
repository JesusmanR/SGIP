package co.com.bspstore.sgip.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Ingreso de mercancía (RF-48).
 *
 * El mínimo de uno es el criterio del requisito —«una cantidad menor o
 * igual a cero devuelve 400»— y además lo que la V23 exige con
 * `ck_movimientos_cantidad`. Puesto aquí, el rechazo llega como un 400
 * con el motivo escrito; sin él, llegaría como un error de integridad.
 *
 * El motivo es opcional en un ingreso: la mercancía que entra se explica
 * por sí sola y el documento dice de dónde viene. Solo el ajuste lo
 * exige, porque ahí la cifra la decide una persona.
 */
public record SolicitudIngreso(

        @NotNull(message = "El producto es obligatorio")
        Integer productoId,

        @NotNull(message = "La cantidad es obligatoria")
        @Min(value = 1, message = "La cantidad tiene que ser al menos una unidad")
        Integer cantidad,

        @Size(max = 60, message = "El documento no puede pasar de 60 caracteres")
        String documento,

        @Size(max = 200, message = "El motivo no puede pasar de 200 caracteres")
        String motivo
) {}

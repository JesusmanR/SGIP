package co.com.bspstore.sgip.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * El motivo con que se anula una cotización (RF-37). Obligatorio y de máximo 200 caracteres. Se recorta al
 * construirse, antes de validar: un motivo de solo espacios es un 400 y uno de 200 letras con espacios alrededor
 * vale.
 */
public record SolicitudAnulacion(

        @NotBlank(message = "El motivo es obligatorio")
        @Size(max = 200, message = "El motivo admite hasta 200 caracteres")
        String motivo
) {

    public SolicitudAnulacion {
        motivo = motivo == null ? null : motivo.strip();
    }
}

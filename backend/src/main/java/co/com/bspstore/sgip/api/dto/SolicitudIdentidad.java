package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.modelo.TipoDocumentoEmpresa;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * El cambio de identidad de una empresa (PUT /plataforma/empresas/{id}/identidad): el documento con que se
 * identifica (NIT o CC más el número) y su razón social, siempre con el motivo.
 *
 * El número llega como lo escribió el usuario, con o sin puntos o espacios, y el servicio lo normaliza;
 * nunca lleva dígito de verificación, y un guion es un 400. El motivo es obligatorio y mide de 10 a 200 caracteres:
 * 200 es lo que cabe en {@code auditoria_plataforma.motivo}, y menos de 10 no explica nada.
 *
 * Los textos se recortan antes de validar, de modo que diez espacios no cuentan como un motivo.
 */
public record SolicitudIdentidad(

        @NotNull(message = "El tipo de documento es obligatorio")
        TipoDocumentoEmpresa tipoDocumento,

        @NotBlank(message = "El número de documento es obligatorio")
        @Size(max = 40, message = "El número de documento no puede pasar de 40 caracteres")
        String numero,

        @NotBlank(message = "La razón social es obligatoria")
        @Size(max = 160, message = "La razón social no puede pasar de 160 caracteres")
        String razonSocial,

        @NotBlank(message = "El motivo es obligatorio")
        @Size(min = 10, max = 200, message = "El motivo debe tener entre 10 y 200 caracteres")
        String motivo
) {

    public SolicitudIdentidad {
        numero = numero == null ? null : numero.strip();
        razonSocial = razonSocial == null ? null : razonSocial.strip();
        motivo = motivo == null ? null : motivo.strip();
    }
}

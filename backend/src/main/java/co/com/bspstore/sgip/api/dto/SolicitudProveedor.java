package co.com.bspstore.sgip.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record SolicitudProveedor(

        @NotBlank(message = "La razón social es obligatoria")
        @Size(max = 160)
        String razonSocial,

        @NotBlank(message = "El NIT es obligatorio")
        @Size(max = 20)
        String nit,

        @Size(max = 120)
        String contacto,

        @Email(message = "El correo no tiene un formato válido")
        @Size(max = 120)
        String correo,

        @Size(max = 20)
        String telefono,

        // El esquema lo exige no negativo. Cero significa de contado.
        @PositiveOrZero(message = "El plazo no puede ser negativo")
        Integer plazoDias
) {}

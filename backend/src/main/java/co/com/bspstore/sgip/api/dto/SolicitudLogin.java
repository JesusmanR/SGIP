package co.com.bspstore.sgip.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SolicitudLogin(

        @NotBlank(message = "El correo es obligatorio")
        @Email(message = "El correo no tiene un formato válido")
        @Size(max = 120)
        String correo,

        @NotBlank(message = "La contraseña es obligatoria")
        @Size(max = 200)
        String clave
) {}
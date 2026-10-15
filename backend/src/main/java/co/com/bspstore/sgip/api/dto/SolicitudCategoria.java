package co.com.bspstore.sgip.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SolicitudCategoria(

        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 80, message = "El nombre no puede pasar de 80 caracteres")
        String nombre,

        @Size(max = 200, message = "La descripción no puede pasar de 200 caracteres")
        String descripcion
) {}

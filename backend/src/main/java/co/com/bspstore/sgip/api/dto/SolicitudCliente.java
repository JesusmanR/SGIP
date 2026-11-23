package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.modelo.TipoDocumento;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Alta o edición de un cliente desde la caja (RF-66).
 *
 * El nombre, la identificación y el correo son obligatorios. El teléfono, la
 * dirección y la ciudad son opcionales aquí: solo un domicilio los exige, y la
 * ciudad, si no viene, toma la de la empresa. {@code autorizacionDatos} es el
 * consentimiento para tratar los datos personales (Ley 1581, RNF-18); que sea
 * verdadero lo exige el servicio, porque ahí está la regla.
 */
public record SolicitudCliente(

        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 160, message = "El nombre no puede pasar de 160 caracteres")
        String nombre,

        @NotNull(message = "El tipo de documento es obligatorio")
        TipoDocumento tipoDocumento,

        @NotBlank(message = "La identificación es obligatoria")
        @Size(max = 20, message = "La identificación no puede pasar de 20 caracteres")
        String identificacion,

        @NotBlank(message = "El correo es obligatorio")
        @Email(message = "El correo no es válido")
        @Size(max = 120, message = "El correo no puede pasar de 120 caracteres")
        String correo,

        @Size(max = 20, message = "El teléfono no puede pasar de 20 caracteres")
        String telefono,

        @Size(max = 200, message = "La dirección no puede pasar de 200 caracteres")
        String direccion,

        @Size(max = 80, message = "La ciudad no puede pasar de 80 caracteres")
        String ciudad,

        boolean autorizacionDatos
) {}

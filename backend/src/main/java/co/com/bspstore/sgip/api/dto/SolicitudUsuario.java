package co.com.bspstore.sgip.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Un usuario nuevo del propio negocio (RF-12).
 *
 * No hay campo de empresa, y es la pieza central del requisito: «el
 * usuario creado queda ligado a la empresa de quien lo creó y no puede
 * cambiarse de empresa». La empresa sale del contexto de la sesión, de
 * modo que no existe un número que mandar para crear un usuario en otro
 * comercio. Omitir el campo es más fuerte que validarlo.
 */
public record SolicitudUsuario(

        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 120, message = "El nombre no puede pasar de 120 caracteres")
        String nombre,

        @NotBlank(message = "El correo es obligatorio")
        @Email(message = "El correo no tiene forma de correo")
        @Size(max = 120, message = "El correo no puede pasar de 120 caracteres")
        String correo,

        /**
         * La clave con la que entrará la primera vez. El mínimo de ocho
         * es el que ya aplican el inicializador de arranque y el alta de
         * empresa; no se inventa otro. El usuario nace con el cambio de
         * clave exigido, de modo que esta sirve una vez.
         */
        @NotBlank(message = "La contraseña es obligatoria")
        @Size(min = 8, max = 100, message = "La contraseña debe tener al menos 8 caracteres")
        String clave,

        @NotBlank(message = "El rol es obligatorio")
        @Size(max = 30, message = "El rol no puede pasar de 30 caracteres")
        String rol
) {}

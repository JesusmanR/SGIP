package co.com.bspstore.sgip.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * El cambio de contraseña de quien tiene la sesión abierta.
 *
 * No lleva identificador de usuario: se cambia siempre la clave de quien
 * hace la petición, que sale de su sesión. Un número en el cuerpo sería
 * una puerta para cambiar la de otra persona.
 *
 * Un registro imprime todos sus campos por omisión; aquí {@code toString}
 * los oculta para que una contraseña no llegue a un log por accidente.
 */
public record SolicitudCambioClave(

        @NotBlank(message = "La contraseña actual es obligatoria")
        @Size(max = 100, message = "La contraseña actual no puede pasar de 100 caracteres")
        String claveActual,

        // El mínimo de ocho es el mismo de SolicitudUsuario, el
        // inicializador de arranque y el alta de empresa.
        @NotBlank(message = "La contraseña nueva es obligatoria")
        @Size(min = 8, max = 100, message = "La contraseña debe tener al menos 8 caracteres")
        String claveNueva
) {
    @Override
    public String toString() {
        return "SolicitudCambioClave[***]";
    }
}

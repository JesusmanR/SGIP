package co.com.bspstore.sgip.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Lo que el administrador puede cambiar de un usuario de su negocio
 * (RF-12): el nombre y el rol.
 *
 * No está el correo. Es la credencial con la que se entra y la que la
 * auditoría de accesos guarda en cada intento, de modo que cambiarlo
 * desde una pantalla de administración rompe el rastro —los intentos
 * viejos quedan apuntando a un correo que ya no existe— y permite que un
 * administrador redirija la cuenta de otra persona a un correo suyo.
 * Cuando haga falta, es una operación con su propio nombre y su propia
 * verificación, no un campo más de este formulario.
 *
 * Tampoco está la clave. Un administrador que pudiera fijar la
 * contraseña de otro podría entrar como él, y entonces el rastro de
 * auditoría dejaría de identificar a una persona. Para la cuenta
 * bloqueada u olvidada está el desbloqueo, que no cambia la clave.
 */
public record SolicitudEdicionUsuario(

        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 120, message = "El nombre no puede pasar de 120 caracteres")
        String nombre,

        @NotBlank(message = "El rol es obligatorio")
        @Size(max = 30, message = "El rol no puede pasar de 30 caracteres")
        String rol
) {}

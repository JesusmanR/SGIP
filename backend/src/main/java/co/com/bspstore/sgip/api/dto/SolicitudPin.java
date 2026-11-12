package co.com.bspstore.sgip.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Fijar o cambiar el PIN del propio administrador (ADR-0032).
 *
 * {@code pinActual} es nulo la primera vez y obligatorio para cambiarlo: el
 * servicio lo exige. Vacío o de solo espacios (lo que manda un formulario con el
 * campo sin llenar) no es un error de formato: pasa tal cual y el servicio lo trata
 * como si faltara. No lleva identificador de usuario: el PIN es siempre el de
 * quien hace la petición.
 *
 * El PIN son de 4 a 6 dígitos ASCII. {@code [0-9]} y no {@code \d}: este último
 * admitiría dígitos de otros alfabetos. Los mensajes no repiten lo escrito.
 */
public record SolicitudPin(

        @Pattern(regexp = "[0-9]{4,6}|\\s*", message = "El PIN actual son de 4 a 6 dígitos")
        String pinActual,

        @NotBlank(message = "El PIN nuevo es obligatorio")
        @Pattern(regexp = "[0-9]{4,6}", message = "El PIN nuevo debe tener de 4 a 6 dígitos")
        String pinNuevo
) {
    @Override
    public String toString() {
        return "SolicitudPin[***]";
    }
}

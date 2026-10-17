package co.com.bspstore.sgip.api.dto;

import jakarta.validation.constraints.Size;

/**
 * El motivo de una activación o una desactivación (RF-02, RF-03).
 *
 * Solo lleva el motivo. El estado al que se va no viene en el cuerpo: lo
 * dice la ruta —{@code /activar}, {@code /desactivar}— y así no hay
 * forma de pedir un estado que el esquema no conozca.
 */
public record SolicitudCambioEstado(

        @Size(max = 200, message = "El motivo no puede pasar de 200 caracteres")
        String motivo
) {

    /**
     * El motivo de una solicitud que puede no haber llegado.
     *
     * El cuerpo es opcional en estos dos endpoints, de modo que la
     * solicitud llega nula cuando el cliente no manda nada. Resolverlo
     * aquí evita repetir la comprobación en cada método del controlador,
     * que es donde se olvida.
     */
    public static String motivoDe(SolicitudCambioEstado solicitud) {
        return solicitud == null ? null : solicitud.motivo();
    }
}

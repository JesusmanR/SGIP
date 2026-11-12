package co.com.bspstore.sgip.api.dto;

/** Si el administrador ya tiene un PIN. Nunca el PIN, ni derivado ni de otra forma. */
public record EstadoPin(boolean configurado) {
}

package co.com.bspstore.sgip.logica;

/**
 * Quien tiene la sesión abierta quiere cambiar su contraseña, pero su cuenta
 * está bloqueada por intentos fallidos.
 *
 * El bloqueo no cierra las sesiones ya abiertas (ADR-0028), de modo que esta
 * puerta tiene que cerrarla ella misma. A diferencia del ingreso, aquí quien
 * pregunta ya está autenticado y no hay nada que ocultarle: se le dice la
 * causa y cuánto dura el bloqueo.
 */
public class CuentaBloqueadaException extends RuntimeException {

    private final int minutosDeBloqueo;

    public CuentaBloqueadaException(int minutosDeBloqueo) {
        super("La cuenta está bloqueada por intentos fallidos.");
        this.minutosDeBloqueo = minutosDeBloqueo;
    }

    public int getMinutosDeBloqueo() {
        return minutosDeBloqueo;
    }
}

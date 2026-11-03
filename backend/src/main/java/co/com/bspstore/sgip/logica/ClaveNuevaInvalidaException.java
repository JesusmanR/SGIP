package co.com.bspstore.sgip.logica;

/**
 * La contraseña nueva no sirve: es la misma que la actual o no cumple el
 * largo mínimo. 400 por la misma razón que
 * {@link ClaveActualIncorrectaException}: no es un problema de sesión.
 */
public class ClaveNuevaInvalidaException extends RuntimeException {

    public ClaveNuevaInvalidaException(String mensaje) {
        super(mensaje);
    }
}

package co.com.bspstore.sgip.logica;

/**
 * El PIN que el administrador quiere fijar no sirve, o falta el actual para
 * cambiarlo. 400: es un dato del cuerpo, no un problema de sesión ni de permiso.
 *
 * Los mensajes son fijos y nunca llevan el PIN, ni el nuevo ni el actual.
 */
public class PinNuevoInvalidoException extends RuntimeException {

    public PinNuevoInvalidoException(String mensaje) {
        super(mensaje);
    }
}

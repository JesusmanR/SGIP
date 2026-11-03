package co.com.bspstore.sgip.logica;

/**
 * La contraseña actual que escribió quien quiere cambiarla no es la que
 * tiene.
 *
 * Se responde 400 y no 401: el cliente trata todo 401 como «la sesión
 * expiró» y echaría al usuario justo cuando solo se equivocó al teclear.
 * La sesión es válida; lo que falla es un dato del cuerpo.
 */
public class ClaveActualIncorrectaException extends RuntimeException {

    public ClaveActualIncorrectaException() {
        super("La contraseña actual no es correcta.");
    }
}

package co.com.bspstore.sgip.logica;

/**
 * La operación necesita la autorización de un administrador y no la tiene: no
 * vino, o no cubre lo que se está por hacer. 403, porque se sabe quién pide y aun
 * así no puede.
 *
 * El texto es fijo: no dice qué se quería hacer ni qué datos llegaron.
 */
public class AutorizacionRequeridaException extends RuntimeException {

    public AutorizacionRequeridaException() {
        super("Esta operación necesita la autorización de un administrador.");
    }
}

package co.com.bspstore.sgip.logica;

/**
 * Se intentó crear algo cuyo nombre ya existe dentro de la misma empresa.
 * La comprobación se hace antes de guardar para dar un mensaje claro, no
 * en lugar de la restricción única del esquema: esa sigue siendo la que
 * garantiza el invariante si dos peticiones llegan a la vez.
 */
public class NombreRepetidoException extends RuntimeException {

    public NombreRepetidoException(String mensaje) {
        super(mensaje);
    }
}

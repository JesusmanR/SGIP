package co.com.bspstore.sgip.logica;

/**
 * Se intentó editar al «Consumidor final», que lo gestiona el sistema. Es un 409: la
 * petición es válida y es la naturaleza de ese cliente lo que impide atenderla.
 */
public class ClienteNoEditableException extends RuntimeException {

    public ClienteNoEditableException(String mensaje) {
        super(mensaje);
    }
}

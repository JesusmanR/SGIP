package co.com.bspstore.sgip.logica;

/**
 * Se intentó desactivar una categoría que todavía sostiene productos
 * activos. El manejador la traduce a 409: la petición es válida y el
 * recurso existe; lo que impide atenderla es el estado del catálogo.
 */
public class CategoriaEnUsoException extends RuntimeException {
    public CategoriaEnUsoException(String mensaje) {
        super(mensaje);
    }
}

package co.com.bspstore.sgip.logica;

/**
 * Desactivar el medio dejaría a la empresa sin ninguno activo, y sin medios la
 * caja no puede cobrar. Es un 409: la petición es válida y es el estado actual
 * lo que impide atenderla; con otro medio activo, la misma petición funciona.
 */
public class UltimoMedioActivoException extends RuntimeException {

    public UltimoMedioActivoException(String mensaje) {
        super(mensaje);
    }
}

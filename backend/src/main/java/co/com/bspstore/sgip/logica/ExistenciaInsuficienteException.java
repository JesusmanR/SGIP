package co.com.bspstore.sgip.logica;

/**
 * No hay existencia para lo que se vende y quien vende no pidió un encargo, o el encargo
 * dejaría la existencia por debajo del piso. Es un 409: la venta es válida y es el estado del
 * inventario, que el cliente no podía conocer al escribirla, lo que impide atenderla.
 */
public class ExistenciaInsuficienteException extends RuntimeException {

    public ExistenciaInsuficienteException(String mensaje) {
        super(mensaje);
    }
}

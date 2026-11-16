package co.com.bspstore.sgip.logica;

/**
 * La operación es válida pero el estado de lo que toca la impide: cobrar una venta que ya está
 * pagada o que no era contra entrega, entregar lo que ya se entregó. Es un 409. El texto es fijo y no
 * repite lo enviado.
 */
public class EstadoNoAdmiteLaOperacionException extends RuntimeException {

    public EstadoNoAdmiteLaOperacionException(String mensaje) {
        super(mensaje);
    }
}

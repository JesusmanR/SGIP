package co.com.bspstore.sgip.logica;

/**
 * Se intentó con un medio de pago algo que no se puede hacer a mano: crear,
 * activar o desactivar el medio de sistema «Saldo a favor». Es un 400: la
 * petición pide algo que ninguna empresa puede hacer, sea cual sea su estado.
 */
public class MedioDePagoInvalidoException extends RuntimeException {

    public MedioDePagoInvalidoException(String mensaje) {
        super(mensaje);
    }
}

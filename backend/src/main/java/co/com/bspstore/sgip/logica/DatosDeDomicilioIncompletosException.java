package co.com.bspstore.sgip.logica;

/**
 * El cliente no sirve para un domicilio: le falta el teléfono, la dirección o la
 * ciudad, o es el «Consumidor final», que no tiene ninguno. Es un 400: la venta que
 * lo pide está mal armada, sea cual sea el estado de la empresa.
 */
public class DatosDeDomicilioIncompletosException extends RuntimeException {

    public DatosDeDomicilioIncompletosException(String mensaje) {
        super(mensaje);
    }
}

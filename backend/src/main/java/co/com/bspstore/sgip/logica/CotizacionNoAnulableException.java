package co.com.bspstore.sgip.logica;

/**
 * La cotización ya no se puede anular porque está anulada o convertida en venta. Es un 409 con un texto fijo,
 * que no repite nada de lo enviado.
 */
public class CotizacionNoAnulableException extends RuntimeException {

    public static final String MENSAJE = "Solo se anula una cotización vigente o vencida.";

    public CotizacionNoAnulableException() {
        super(MENSAJE);
    }
}

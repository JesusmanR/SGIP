package co.com.bspstore.sgip.logica;

/**
 * Un descuento que el cálculo de la venta no admite (valor ausente o negativo, porcentaje mayor que
 * 100, o un monto mayor que lo que se descuenta). Es un 400. El mensaje es un texto fijo que
 * nombra la regla y no repite lo que se envió.
 */
public class DescuentoInvalidoException extends RuntimeException {

    public DescuentoInvalidoException(String mensaje) {
        super(mensaje);
    }
}

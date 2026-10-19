package co.com.bspstore.sgip.logica;

/**
 * Un movimiento de inventario viene bien formado y aun así no se puede
 * registrar (RF-48, RF-49).
 *
 * Son las reglas que la validación declarativa no alcanza porque
 * dependen del estado: el producto no es de esta empresa, o el saldo
 * resultante caería por debajo del piso que la V9 fijó. La base también
 * las rechazaría, pero como error de integridad, sin decir cuál de las
 * tres cifras es la que no cuadra.
 */
public class MovimientoInvalidoException extends RuntimeException {
    public MovimientoInvalidoException(String mensaje) {
        super(mensaje);
    }
}

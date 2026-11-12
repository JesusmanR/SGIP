package co.com.bspstore.sgip.logica;

/**
 * El cajero intentó una operación de caja (venta, cobro o devolución) sin tener un turno
 * abierto, o su turno se cerró mientras tanto. Es un 409: el cuerpo está bien y falta
 * el estado que la operación necesita.
 */
public class SinTurnoAbiertoException extends RuntimeException {

    public SinTurnoAbiertoException() {
        super("Abre un turno de caja antes de registrar esta operación.");
    }
}

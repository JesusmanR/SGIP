package co.com.bspstore.sgip.logica;

/**
 * Un dato del turno que el servicio no admite (hoy, la base de efectivo). Es un 400. El
 * mensaje es un texto fijo que nombra la regla y no repite lo que se envió.
 */
public class DatoDeTurnoInvalidoException extends RuntimeException {

    public DatoDeTurnoInvalidoException(String mensaje) {
        super(mensaje);
    }
}

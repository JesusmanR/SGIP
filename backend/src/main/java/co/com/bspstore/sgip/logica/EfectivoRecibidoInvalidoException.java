package co.com.bspstore.sgip.logica;

/**
 * El efectivo recibido no sirve para dar cambio (ausente, con centavos o menor que el total en
 * efectivo). Es un 400. El mensaje es un texto fijo y no repite lo que se envió.
 */
public class EfectivoRecibidoInvalidoException extends RuntimeException {

    public EfectivoRecibidoInvalidoException(String mensaje) {
        super(mensaje);
    }
}

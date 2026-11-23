package co.com.bspstore.sgip.logica;

/**
 * Un dato de la venta que el servicio no admite: una regla que una anotación no puede mirar
 * (pagos que no suman el total, un medio inactivo, un producto sin precio, un encargo sin fecha)
 * o algo que todavía no está disponible. Es un 400. El mensaje es un texto fijo que nombra la
 * regla y no repite lo que se envió.
 */
public class VentaInvalidaException extends RuntimeException {

    public VentaInvalidaException(String mensaje) {
        super(mensaje);
    }
}

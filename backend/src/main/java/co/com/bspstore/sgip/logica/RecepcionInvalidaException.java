package co.com.bspstore.sgip.logica;

/**
 * Una recepción no se puede registrar contra esta orden (RF-23).
 *
 * Cubre los casos que dependen del estado de la orden y no del cuerpo de
 * la petición: recibir más de lo pedido, recibir un producto que la orden
 * no pide, o recibir contra una orden ya cerrada o anulada.
 *
 * El primero lo rechazaría también `ck_oci_recibida`, que la V10 escribió
 * con el comentario correcto —«una recepción no puede superar lo pedido:
 * la regla vive aquí y no depende de que la aplicación se acuerde de
 * comprobarla»—. Esta excepción no la sustituye: la precede, para que el
 * usuario lea «pediste 100, ya recibiste 60 y ahora traes 50» en lugar de
 * un error de integridad.
 */
public class RecepcionInvalidaException extends RuntimeException {
    public RecepcionInvalidaException(String mensaje) {
        super(mensaje);
    }
}

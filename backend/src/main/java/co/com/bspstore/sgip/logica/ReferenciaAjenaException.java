package co.com.bspstore.sgip.logica;

/**
 * Se indicó una categoría, un proveedor o cualquier otra referencia que
 * no pertenece a la empresa de quien hace la petición.
 *
 * Se comprueba antes de guardar para dar un mensaje entendible. La
 * garantía real la dan las claves foráneas compuestas (empresa_id,
 * columna) que introdujo la migración V11: aunque esta comprobación
 * fallara, el motor no dejaría crear la fila.
 */
public class ReferenciaAjenaException extends RuntimeException {

    public ReferenciaAjenaException(String mensaje) {
        super(mensaje);
    }
}

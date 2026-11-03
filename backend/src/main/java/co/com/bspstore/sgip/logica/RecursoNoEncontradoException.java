package co.com.bspstore.sgip.logica;

/**
 * El identificador que viene en la ruta no corresponde a nada de la
 * empresa de quien pide: no existe, o es de otra.
 *
 * Es 404 y no 422 porque el recurso ES la dirección. Una referencia
 * dentro del cuerpo o de un parámetro, en cambio, sigue siendo
 * {@link ReferenciaAjenaException}: la petición está bien formada y lo
 * inválido es un dato que lleva dentro.
 *
 * Responder igual ante «no existe» y «es de otra empresa» es a
 * propósito: distinguirlos le diría a quien sondea qué identificadores
 * están en uso en otras empresas.
 */
public class RecursoNoEncontradoException extends RuntimeException {

    public RecursoNoEncontradoException(String mensaje) {
        super(mensaje);
    }
}

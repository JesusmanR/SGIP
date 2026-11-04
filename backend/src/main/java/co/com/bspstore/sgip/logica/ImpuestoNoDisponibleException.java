package co.com.bspstore.sgip.logica;

/**
 * Se pidió un tipo de impuesto que no existe en el catálogo nacional o que
 * la plataforma tiene desactivado.
 *
 * Es distinta de {@link ReferenciaAjenaException}: aquella dice «esto es de
 * otra empresa», y el catálogo de impuestos no es de ninguna empresa. Las
 * dos acaban en 422 —el cuerpo está bien formado y lo que no vale es la
 * referencia—, pero con título propio, para que el error no mienta sobre
 * dónde está el problema.
 */
public class ImpuestoNoDisponibleException extends RuntimeException {

    public ImpuestoNoDisponibleException(String mensaje) {
        super(mensaje);
    }
}

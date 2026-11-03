package co.com.bspstore.sgip.logica;

/**
 * La carga de precios existe y la petición es válida, pero el estado en
 * que está la carga no admite la operación pedida: aplicar una que no
 * está validada, rechazar una ya aplicada.
 *
 * Es un conflicto de estado (409): con la carga en otro estado, la misma
 * petición habría funcionado. Por eso no es {@link ReglaInvalidaException},
 * que es un 400 por un dato que el cliente puede corregir.
 */
public class EstadoDeCargaInvalidoException extends RuntimeException {

    public EstadoDeCargaInvalidoException(String mensaje) {
        super(mensaje);
    }
}

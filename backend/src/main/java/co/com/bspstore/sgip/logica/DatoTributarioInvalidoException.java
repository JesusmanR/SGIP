package co.com.bspstore.sgip.logica;

/**
 * Un dato tributario nacional viene bien formado y aun así no vale
 * (RF-05).
 *
 * Es el caso que la validación declarativa de Jakarta no puede cubrir
 * porque la regla depende de lo que hay en la base: el umbral en UVT es
 * obligatorio si el tipo excluye hasta un umbral, y sobra si no. Una
 * anotación no sabe qué tratamiento tiene el tipo que se está
 * cambiando.
 *
 * Se distingue de {@link FiltroInvalidoException}, que habla de valores
 * que llegan por la dirección en un filtro de consulta. Las dos acaban
 * en 400, y mezclarlas haría que el título del error mintiera sobre
 * dónde está el problema.
 */
public class DatoTributarioInvalidoException extends RuntimeException {
    public DatoTributarioInvalidoException(String mensaje) {
        super(mensaje);
    }
}

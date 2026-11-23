package co.com.bspstore.sgip.logica;

/**
 * Otra petición con la misma clave de idempotencia confirmó su venta justo antes de que esta
 * insertara la suya. Se lanza dentro de la transacción de la venta para descartarla entera; la
 * fachada la atrapa y responde con la venta que ya existe. Nunca llega a la API.
 */
public class VentaDuplicadaException extends RuntimeException {

    public VentaDuplicadaException(Throwable causa) {
        super("Ya existe una venta con esa clave de idempotencia.", causa);
    }
}

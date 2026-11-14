package co.com.bspstore.sgip.logica;

/**
 * La clave de idempotencia ya registró una venta con OTRA carga. Un reintento lleva la misma
 * carga; esto es una clave reutilizada para una venta distinta. Es un 409.
 */
public class ClaveDeIdempotenciaEnUsoException extends RuntimeException {

    public ClaveDeIdempotenciaEnUsoException() {
        super("Esa clave de idempotencia ya registró otra venta. Genera una clave nueva para una venta distinta.");
    }
}

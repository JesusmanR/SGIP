package co.com.bspstore.sgip.logica.informes;

/**
 * El período que pide un informe no se puede usar: una fecha mal escrita, invertida o de más de un año.
 *
 * Llega por la dirección y no por un cuerpo, de modo que la validación declarativa no lo ve. El manejador
 * de errores la traduce a 400 con el texto del mensaje, que ya está escrito para quien usa la pantalla.
 */
public class PeriodoInvalidoException extends RuntimeException {
    public PeriodoInvalidoException(String mensaje) {
        super(mensaje);
    }
}

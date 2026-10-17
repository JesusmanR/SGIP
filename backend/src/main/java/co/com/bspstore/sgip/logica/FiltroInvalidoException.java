package co.com.bspstore.sgip.logica;

/**
 * Un filtro de consulta trae un valor que no existe.
 *
 * Se distingue de los datos inválidos de un cuerpo porque llega por la
 * dirección y no por el cuerpo, de modo que la validación declarativa
 * de Jakarta no lo ve. El manejador la traduce a 400.
 */
public class FiltroInvalidoException extends RuntimeException {
    public FiltroInvalidoException(String mensaje) {
        super(mensaje);
    }
}

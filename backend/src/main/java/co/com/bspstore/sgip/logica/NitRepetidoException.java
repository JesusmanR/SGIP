package co.com.bspstore.sgip.logica;

/**
 * La identificación tributaria de una empresa ya está registrada.
 *
 * Es una {@link RegistroRepetidoException} con tipo propio: el formulario del
 * alta marca el campo del NIT sin tener que leer el texto del mensaje.
 */
public class NitRepetidoException extends RegistroRepetidoException {

    public NitRepetidoException(String mensaje) {
        super(mensaje);
    }
}

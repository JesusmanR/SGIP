package co.com.bspstore.sgip.logica;

/**
 * El correo de un usuario ya pertenece a otro usuario de la plataforma.
 *
 * Es una {@link RegistroRepetidoException} con tipo propio: el formulario del
 * alta de empresa marca el campo del correo sin tener que leer el texto.
 */
public class CorreoRepetidoException extends RegistroRepetidoException {

    public CorreoRepetidoException(String mensaje) {
        super(mensaje);
    }
}

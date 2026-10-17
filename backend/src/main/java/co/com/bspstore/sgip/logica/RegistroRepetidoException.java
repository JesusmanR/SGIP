package co.com.bspstore.sgip.logica;

/**
 * Un dato que debe ser único ya está en la base: la identificación
 * tributaria de una empresa, el correo de un usuario.
 *
 * Es distinta de {@link NombreRepetidoException}, que habla de nombres
 * repetidos dentro de una empresa. Aquí la unicidad es de toda la
 * plataforma, y el mensaje tiene que decir cuál de los dos datos choca:
 * quien da de alta una empresa escribe los dos en la misma pantalla y un
 * «ya existe» sin sujeto no le dice cuál corregir.
 */
public class RegistroRepetidoException extends RuntimeException {

    public RegistroRepetidoException(String mensaje) {
        super(mensaje);
    }
}

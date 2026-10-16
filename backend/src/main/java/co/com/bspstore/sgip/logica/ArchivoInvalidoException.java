package co.com.bspstore.sgip.logica;

/** Lo que llegó no es una imagen admisible. Se traduce en 400. */
public class ArchivoInvalidoException extends RuntimeException {

    public ArchivoInvalidoException(String mensaje) {
        super(mensaje);
    }
}

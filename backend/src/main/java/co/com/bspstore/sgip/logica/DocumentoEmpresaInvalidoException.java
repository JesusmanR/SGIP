package co.com.bspstore.sgip.logica;

/**
 * El documento de la empresa no tiene el formato: quedó algo que no es un dígito, o no son de 5 a 15
 * dígitos. Es un 400 con el tipo {@code urn:sgip:empresa:documento-invalido}.
 *
 * El mensaje es fijo y no repite lo que escribió el cliente.
 */
public class DocumentoEmpresaInvalidoException extends RuntimeException {

    public static final String MENSAJE =
            "El número de documento debe tener solo dígitos, entre 5 y 15, sin dígito de verificación. "
                    + "Se aceptan puntos y espacios como separadores.";

    /** Para el guion: casi siempre es el dígito de verificación, que nunca se pide. */
    public static final String MENSAJE_SIN_DIGITO = "Escribe solo el número, sin el dígito de verificación.";

    public DocumentoEmpresaInvalidoException() {
        super(MENSAJE);
    }

    private DocumentoEmpresaInvalidoException(String mensaje) {
        super(mensaje);
    }

    public static DocumentoEmpresaInvalidoException conDigitoDeVerificacion() {
        return new DocumentoEmpresaInvalidoException(MENSAJE_SIN_DIGITO);
    }
}

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
                    + "Se aceptan puntos, espacios y guiones como separadores.";

    public DocumentoEmpresaInvalidoException() {
        super(MENSAJE);
    }
}

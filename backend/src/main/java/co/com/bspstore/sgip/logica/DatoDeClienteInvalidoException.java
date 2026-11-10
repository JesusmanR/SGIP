package co.com.bspstore.sgip.logica;

/**
 * Un dato del cliente incumple una regla que depende de la empresa o del
 * consentimiento y que una anotación no puede mirar: falta la autorización para
 * tratar sus datos, o no hay ciudad ni en el cliente ni en la empresa. Es un 400.
 */
public class DatoDeClienteInvalidoException extends RuntimeException {

    public DatoDeClienteInvalidoException(String mensaje) {
        super(mensaje);
    }
}

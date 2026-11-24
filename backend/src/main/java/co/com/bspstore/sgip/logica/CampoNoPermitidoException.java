package co.com.bspstore.sgip.logica;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * El cuerpo trae un campo que esa operación no acepta. Es un 400 que nombra el campo.
 *
 * Existe porque Spring Boot no falla ante campos desconocidos al leer un JSON: sin esto, un
 * administrador que mandara {@code nit} o {@code razonSocial} a la edición de su empresa recibiría un
 * 200 y no se enteraría de que se ignoró. Los nombres que se citan son los que escribió el cliente, y
 * por eso se recortan y se limitan: el mensaje no debe poder crecer sin tope.
 */
public class CampoNoPermitidoException extends RuntimeException {

    private static final int MAXIMO_DE_NOMBRES = 5;
    private static final int LARGO_DE_NOMBRE = 40;

    public CampoNoPermitidoException(Collection<String> campos) {
        super(mensaje(campos));
    }

    private static String mensaje(Collection<String> campos) {
        String nombres = campos.stream()
                .sorted()
                .limit(MAXIMO_DE_NOMBRES)
                .map(c -> c.length() > LARGO_DE_NOMBRE ? c.substring(0, LARGO_DE_NOMBRE) + "…" : c)
                .map(c -> "«" + c + "»")
                .collect(Collectors.joining(", "));
        return (campos.size() == 1 ? "El campo " : "Los campos ") + nombres
                + (campos.size() > MAXIMO_DE_NOMBRES ? " y otros" : "")
                + (campos.size() == 1 ? " no se acepta en esta operación." : " no se aceptan en esta operación.");
    }
}

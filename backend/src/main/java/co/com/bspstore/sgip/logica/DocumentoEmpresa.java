package co.com.bspstore.sgip.logica;

/**
 * El documento con que se identifica una empresa, sea NIT o cédula: solo dígitos, de 5 a 15.
 *
 * Quita los puntos, los espacios y los guiones, y lo que queda tiene que ser un número. NO hay dígito de
 * verificación: por decisión del usuario nunca se pide ni se valida, de modo que {@code 900123456} y
 * {@code 900123456-1} son los dos válidos, y el segundo se guarda como {@code 9001234561}. Quien escribe un
 * dígito de más lo está escribiendo como parte del número, y lo que se guarda es lo que escribió: el
 * sistema no recalcula nada ni lo corrige.
 *
 * La misma norma sirve para las dos rutas que escriben el documento, el alta y el cambio de identidad, para
 * que no puedan discrepar.
 */
public final class DocumentoEmpresa {

    public static final int MINIMO = 5;
    public static final int MAXIMO = 15;

    private DocumentoEmpresa() {}

    /**
     * El número sin puntos, espacios ni guiones.
     *
     * @throws DocumentoEmpresaInvalidoException si queda algo que no sea un dígito o el largo sale de 5 a 15
     */
    public static String normalizar(String texto) {
        if (texto == null) {
            throw new DocumentoEmpresaInvalidoException();
        }
        StringBuilder numero = new StringBuilder(texto.length());
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (c == '.' || c == '-' || Character.isWhitespace(c)) {
                continue;
            }
            // Solo los dígitos ASCII: Character.isDigit aceptaría los de otros alfabetos.
            if (c < '0' || c > '9') {
                throw new DocumentoEmpresaInvalidoException();
            }
            numero.append(c);
        }
        if (numero.length() < MINIMO || numero.length() > MAXIMO) {
            throw new DocumentoEmpresaInvalidoException();
        }
        return numero.toString();
    }

    /**
     * Lo que ya está guardado, normalizado para compararlo con un número nuevo, o tal cual si no se puede
     * normalizar. Las semillas llevan guion ({@code 900000000-0}) y se quedan como están; que una fila
     * vieja no tenga el formato nuevo no puede hacer fallar la comparación.
     */
    public static String normalizadoOTalCual(String guardado) {
        try {
            return normalizar(guardado);
        } catch (DocumentoEmpresaInvalidoException e) {
            return guardado;
        }
    }
}

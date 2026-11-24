package co.com.bspstore.sgip.logica;

/**
 * El documento con que se identifica una empresa, sea NIT o cédula: solo dígitos, de 5 a 15.
 *
 * Acepta solo dígitos, puntos y espacios; quita los puntos y los espacios, y lo que queda tiene que ser un
 * número. NO hay dígito de verificación: por decisión del usuario nunca se pide ni se valida, y por eso el
 * guion se rechaza. {@code 900123456-1} no se acepta ni se «arregla» quitando el guion, porque
 * {@code 9001234561} es OTRO número: se pide escribir {@code 900123456}.
 *
 * La misma norma sirve para las dos rutas que escriben el documento, el alta y el cambio de identidad, para
 * que no puedan discrepar.
 */
public final class DocumentoEmpresa {

    public static final int MINIMO = 5;
    public static final int MAXIMO = 15;

    private DocumentoEmpresa() {}

    /**
     * El número sin puntos ni espacios.
     *
     * @throws DocumentoEmpresaInvalidoException con un mensaje que pide escribir el número sin el dígito de
     *         verificación si trae un guion; con el mensaje general si trae otro carácter que no sea un
     *         dígito, un punto o un espacio, o si el largo sale de 5 a 15
     */
    public static String normalizar(String texto) {
        if (texto == null) {
            throw new DocumentoEmpresaInvalidoException();
        }
        StringBuilder numero = new StringBuilder(texto.length());
        boolean conGuion = false;
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (c == '.' || Character.isWhitespace(c)) {
                continue;
            }
            if (c == '-') {
                conGuion = true;
                continue;
            }
            // Solo los dígitos ASCII: Character.isDigit aceptaría los de otros alfabetos.
            if (c < '0' || c > '9') {
                throw new DocumentoEmpresaInvalidoException();
            }
            numero.append(c);
        }
        if (conGuion) {
            throw DocumentoEmpresaInvalidoException.conDigitoDeVerificacion();
        }
        if (numero.length() < MINIMO || numero.length() > MAXIMO) {
            throw new DocumentoEmpresaInvalidoException();
        }
        return numero.toString();
    }

    /**
     * Lo que ya está guardado, normalizado para compararlo con un número nuevo, o tal cual si no se puede
     * normalizar. Las semillas anteriores a la V48 llevan el dígito de verificación ({@code NNNNNNNNN-D},
     * como {@code 900000000-0}): para comparar se descarta ese sufijo, de modo que la semilla
     * {@code 900123456-1} y el número nuevo {@code 900123456} son el mismo. Es la misma regla que aplica
     * {@code RepositorioPlataforma#existeNit} en SQL. Las filas viejas se quedan como están: que no tengan
     * el formato nuevo no puede hacer fallar la comparación.
     */
    public static String normalizadoOTalCual(String guardado) {
        try {
            int guion = guardado == null ? -1 : guardado.indexOf('-');
            return normalizar(guion < 0 ? guardado : guardado.substring(0, guion));
        } catch (DocumentoEmpresaInvalidoException e) {
            return guardado;
        }
    }
}

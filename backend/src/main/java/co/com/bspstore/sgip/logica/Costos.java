package co.com.bspstore.sgip.logica;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Interpreta el costo que venía escrito en el archivo del proveedor.
 *
 * Esto decide dinero. Un costo mal leído no da error en ninguna parte:
 * entra, se aplica, y el precio de venta sale mal durante semanas. Lo
 * que no se parezca a un número se rechaza; lo que sea ambiguo se
 * resuelve con la convención del país, y abajo queda dicho cuál es y
 * qué caso no cubre.
 *
 * ## El problema del punto y la coma
 *
 * Un mismo número se escribe de dos maneras según quién exportó la hoja:
 *
 *   1.850,00   español — punto de millares, coma decimal
 *   1,850.00   inglés  — coma de millares, punto decimal
 *
 * Con los dos caracteres presentes no hay ambigüedad: manda el último,
 * que es el decimal. El caso difícil es cuando hay uno solo:
 *
 *   «1.850»   ¿mil ochocientos cincuenta, o uno con ochenta y cinco?
 *
 * La regla es la que usa cualquier hoja de cálculo: un separador seguido
 * de exactamente tres dígitos hasta el final es de millares. Con
 * cualquier otra cantidad de dígitos es decimal. «1.850» es 1850 y
 * «1.85» es 1,85.
 *
 * Queda un caso que la regla no puede resolver: «1.850» escrito por
 * alguien que quería decir 1,850 exactos en inglés. No se puede
 * distinguir, y la convención de millares es la correcta en Colombia,
 * que es donde opera esto. El costo en pesos rara vez lleva decimales.
 */
public final class Costos {

    private Costos() {}

    /**
     * El costo, o vacío si el texto no es un número que se pueda cobrar.
     *
     * Vacío significa «no interpretable», no «cero»: el RF-26 separa el
     * costo no numérico del costo menor o igual a cero porque son dos
     * errores distintos del proveedor y se corrigen distinto.
     */
    public static Optional<BigDecimal> interpretar(String crudo) {
        if (crudo == null || crudo.isBlank()) {
            return Optional.empty();
        }
        String t = crudo.trim()
                // El símbolo de moneda y los espacios de agrupación son
                // decoración que cualquier hoja añade al formatear.
                .replace("$", "")
                .replace(" ", "")
                .replace(" ", "");
        if (t.isEmpty()) {
            return Optional.empty();
        }

        boolean negativo = t.startsWith("-");
        if (negativo || t.startsWith("+")) {
            t = t.substring(1);
        }
        // Un negativo entre paréntesis es como una hoja escribe los
        // números rojos. Se interpreta y después se rechaza por ser
        // menor o igual a cero, que es la causa que el RF-26 nombra.
        if (t.startsWith("(") && t.endsWith(")")) {
            negativo = true;
            t = t.substring(1, t.length() - 1);
        }

        int ultimoPunto = t.lastIndexOf('.');
        int ultimaComa = t.lastIndexOf(',');
        String normalizado;

        if (ultimoPunto >= 0 && ultimaComa >= 0) {
            // Los dos presentes: manda el último, el otro es de millares.
            char decimal = ultimoPunto > ultimaComa ? '.' : ',';
            char millares = decimal == '.' ? ',' : '.';
            int corte = t.lastIndexOf(decimal);
            String entero = t.substring(0, corte);
            String decimales = t.substring(corte + 1);
            if (entero.indexOf(millares) >= 0) {
                if (!agrupacionValida(entero, millares)) {
                    return Optional.empty();
                }
                entero = entero.replace(String.valueOf(millares), "");
            }
            normalizado = entero + "." + decimales;
        } else if (ultimoPunto >= 0 || ultimaComa >= 0) {
            char separador = ultimoPunto >= 0 ? '.' : ',';
            int posicion = Math.max(ultimoPunto, ultimaComa);
            int digitosDespues = t.length() - posicion - 1;
            boolean repetido = t.indexOf(separador) != posicion;

            if (repetido) {
                // Más de un separador solo puede ser agrupación de
                // millares, y entonces tiene que tener su forma exacta.
                // Sin esta comprobación «1..850» se leía como 1850: se
                // quitaban los dos puntos y el resultado parecía un
                // número perfectamente válido.
                if (!agrupacionValida(t, separador)) {
                    return Optional.empty();
                }
                normalizado = t.replace(String.valueOf(separador), "");
            } else if (digitosDespues == 3 && agrupacionValida(t, separador)) {
                normalizado = t.replace(String.valueOf(separador), "");
            } else {
                // Incluye «1234.567», que como agrupación estaría mal
                // formado —cuatro dígitos antes del primer separador— y
                // como decimal se entiende sin ambigüedad.
                normalizado = t.replace(separador, '.');
            }
        } else {
            normalizado = t;
        }

        if (!normalizado.matches("\\d+(\\.\\d+)?")) {
            return Optional.empty();
        }
        try {
            BigDecimal valor = new BigDecimal(normalizado);
            return Optional.of(negativo ? valor.negate() : valor);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Si el texto tiene la forma exacta de un número con separador de
     * millares: uno a tres dígitos y después grupos de tres.
     *
     * Quitar el separador a secas y mirar si lo que queda parece un
     * número no basta. «1..850» pierde los dos puntos y se convierte en
     * 1850, que es un número perfectamente válido y no es lo que decía
     * el archivo.
     */
    private static boolean agrupacionValida(String texto, char millares) {
        String sep = java.util.regex.Pattern.quote(String.valueOf(millares));
        return texto.matches("\\d{1,3}(" + sep + "\\d{3})+");
    }
}

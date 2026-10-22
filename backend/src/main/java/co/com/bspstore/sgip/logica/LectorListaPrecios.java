package co.com.bspstore.sgip.logica;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * El contrato de lectura de una lista de precios (ADR-0007).
 *
 * Cada proveedor remite sus precios de una forma distinta: hojas de
 * cálculo con estructuras que no coinciden, portales y, en algún caso,
 * servicios web. La decisión fue que el importador no sepa de dónde sale
 * la lista, y esta interfaz es esa frontera.
 *
 * Lo que devuelve son filas sin interpretar. Validar es trabajo del
 * importador, no del lector: el RF-26 exige una sola lista de rechazos
 * con sus causas, y si cada lector validara a su manera habría tantas
 * listas como orígenes.
 */
public interface LectorListaPrecios {

    /** Si este lector sabe leer un archivo con ese nombre. */
    boolean entiende(String nombreArchivo);

    /**
     * Las filas del archivo, en su orden, sin el encabezado.
     *
     * @param maximoFilas tope de filas de datos. Al superarlo se lanza
     *                    {@link ArchivoInvalidoException}: el RF-24 pide
     *                    rechazar el exceso indicando la causa, no
     *                    recortar en silencio, que dejaría al proveedor
     *                    creyendo que su lista entró entera.
     */
    List<FilaLeida> leer(InputStream entrada, String nombreArchivo, int maximoFilas)
            throws IOException;
}

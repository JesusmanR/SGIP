package co.com.bspstore.sgip.logica;

/**
 * Lo que llegó no es un archivo admisible, y el motivo es del archivo.
 *
 * La usan los dos caminos por los que entran archivos:
 *
 *   · las imágenes de producto y el logotipo de la empresa, cuando lo
 *     subido no es una imagen que se pueda normalizar;
 *   · las listas de precios (RF-24), cuando el formato no se admite, el
 *     tamaño o el número de filas pasan del tope, o el encabezado no
 *     permite encontrar las columnas mínimas.
 *
 * Lo común a las dos es que quien subió el archivo puede corregirlo, de
 * modo que `ManejadorErrores` la traduce en un 400 con su texto y no en
 * un 500. Un fallo de lectura de verdad —el disco, la red— no es esto y
 * debe subir como lo que es.
 */
public class ArchivoInvalidoException extends RuntimeException {

    public ArchivoInvalidoException(String mensaje) {
        super(mensaje);
    }
}

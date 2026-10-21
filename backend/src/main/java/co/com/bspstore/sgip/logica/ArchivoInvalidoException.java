package co.com.bspstore.sgip.logica;

/**
 * El archivo no se puede leer, y el motivo es del archivo.
 *
 * Se distingue de un fallo de lectura porque el RF-24 pide que el
 * rechazo «indique la causa»: un formato que no se admite, un tamaño o
 * un número de filas por encima del tope, o un encabezado en el que no
 * se encuentran las columnas mínimas. Todas son cosas que quien sube el
 * archivo puede corregir, de modo que llegan como 400 con su texto y no
 * como un 500.
 */
public class ArchivoInvalidoException extends RuntimeException {
    public ArchivoInvalidoException(String mensaje) {
        super(mensaje);
    }
}

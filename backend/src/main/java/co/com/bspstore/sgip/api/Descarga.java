package co.com.bspstore.sgip.api;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;

/**
 * La salida de la respuesta para descargar un .xlsx: fija las cabeceras de adjunto con el primer
 * {@code write} o {@code flush}, y no antes. No cierra la respuesta: eso es del contenedor.
 *
 * Que las cabeceras vayan con el primer byte y no al crearla es lo que deja que una lectura de la base
 * que falla antes de escribir nada reciba el error normal del manejador y no un «adjunto» vacío. Tampoco
 * se usa {@code reset()} para deshacerlas: borraría también las cabeceras de seguridad.
 *
 * Se escribe en el hilo de la petición, no con un cuerpo de streaming que corre en otro: la empresa sale
 * del contexto de seguridad, que otro hilo no tiene.
 *
 * Es de {@code ImportacionControlador} (la plantilla de precios) y de {@code InformesControlador}.
 */
final class Descarga extends OutputStream {

    /** El tipo de contenido de un .xlsx. */
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final HttpServletResponse respuesta;
    private final String nombre;
    private OutputStream destino;

    /**
     * @param nombre el nombre con que se guarda; va entre comillas en la cabecera, así que tiene que ser
     *               ASCII sin comillas ni saltos de línea (ver {@code LibroDeInforme.nombreDeArchivo})
     */
    Descarga(HttpServletResponse respuesta, String nombre) {
        this.respuesta = respuesta;
        this.nombre = nombre;
    }

    void aplicarCabeceras() throws IOException {
        if (destino == null) {
            respuesta.setContentType(XLSX);
            respuesta.setHeader("Content-Disposition", "attachment; filename=\"" + nombre + "\"");
            respuesta.setHeader("Cache-Control", "no-store");
            destino = respuesta.getOutputStream();
        }
    }

    @Override
    public void write(int b) throws IOException {
        aplicarCabeceras();
        destino.write(b);
    }

    @Override
    public void write(byte[] b, int desde, int largo) throws IOException {
        aplicarCabeceras();
        destino.write(b, desde, largo);
    }

    @Override
    public void flush() throws IOException {
        aplicarCabeceras();
        destino.flush();
    }
}

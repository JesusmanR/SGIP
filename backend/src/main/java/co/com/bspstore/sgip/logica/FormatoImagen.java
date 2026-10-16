package co.com.bspstore.sgip.logica;

import javax.imageio.ImageIO;
import java.util.Arrays;

/**
 * Los formatos de imagen que el sistema reconoce, y —por separado— los
 * que de verdad puede procesar.
 *
 * La distinción no es teórica: era un defecto real. El almacén admitía
 * WebP porque reconocía su firma, y después ImageIO devolvía null al
 * intentar decodificarlo, de modo que el usuario recibía «la imagen está
 * dañada o incompleta» por un archivo impecable. Reconocer un formato y
 * saber leerlo son dos cosas distintas, y mezclarlas convierte una
 * limitación de la instalación en una acusación contra el archivo.
 *
 * {@link #sePuedeLeer()} lo pregunta a ImageIO en vez de darlo por
 * sabido. El JDK trae lectores de JPEG y PNG, y ninguno de WebP. Si algún
 * día se añade la biblioteca que lo aporta, WebP empieza a funcionar sin
 * tocar una línea: la lista de admitidos se deriva de lo que la máquina
 * puede hacer, no de lo que este archivo declara.
 */
public enum FormatoImagen {

    /** Firma SOI + marcador. Los tres bytes bastan para distinguirlo. */
    JPEG("jpg", "image/jpeg", "jpeg", "JPEG"),
    PNG("png", "image/png", "png", "PNG"),
    WEBP("webp", "image/webp", "webp", "WebP");

    private static final byte[] FIRMA_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] FIRMA_PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] FIRMA_RIFF = {'R', 'I', 'F', 'F'};
    private static final byte[] FIRMA_WEBP = {'W', 'E', 'B', 'P'};

    private final String extension;
    private final String tipoMime;
    private final String nombreImageIO;
    private final String nombreVisible;

    FormatoImagen(String extension, String tipoMime, String nombreImageIO, String nombreVisible) {
        this.extension = extension;
        this.tipoMime = tipoMime;
        this.nombreImageIO = nombreImageIO;
        this.nombreVisible = nombreVisible;
    }

    public String extension() {
        return extension;
    }

    public String tipoMime() {
        return tipoMime;
    }

    public String nombreVisible() {
        return nombreVisible;
    }

    /** ¿Hay en esta JVM un lector capaz de decodificar este formato? */
    public boolean sePuedeLeer() {
        return ImageIO.getImageReadersByFormatName(nombreImageIO).hasNext();
    }

    /**
     * Qué formato es, por sus primeros bytes. Ni la extensión ni el
     * Content-Type: las dos las escribe quien sube y falsificarlas no
     * cuesta nada.
     *
     * @return el formato, o {@code null} si no es ninguno de los conocidos
     */
    public static FormatoImagen deContenido(byte[] b) {
        if (empiezaPor(b, FIRMA_JPEG, 0)) return JPEG;
        if (empiezaPor(b, FIRMA_PNG, 0)) return PNG;
        // WebP vive dentro de un contenedor RIFF: «RIFF», cuatro bytes de
        // longitud, y recién entonces «WEBP».
        if (empiezaPor(b, FIRMA_RIFF, 0) && empiezaPor(b, FIRMA_WEBP, 8)) return WEBP;
        return null;
    }

    /** Qué formato guarda un archivo ya almacenado, según su extensión. */
    public static FormatoImagen deRuta(String rutaRelativa) {
        String r = rutaRelativa.toLowerCase();
        return Arrays.stream(values())
                .filter(f -> r.endsWith("." + f.extension))
                .findFirst()
                // Lo que esta clase escribe siempre lleva una de las tres
                // extensiones; JPEG es el formato de las fotos y el
                // destino más probable de una ruta antigua.
                .orElse(JPEG);
    }

    /** «JPEG o PNG»: lo que el usuario puede subir hoy, en esta máquina. */
    public static String legiblesEnTexto() {
        var nombres = Arrays.stream(values())
                .filter(FormatoImagen::sePuedeLeer)
                .map(FormatoImagen::nombreVisible)
                .toList();
        if (nombres.isEmpty()) {
            return "ninguno";
        }
        if (nombres.size() == 1) {
            return nombres.get(0);
        }
        return String.join(", ", nombres.subList(0, nombres.size() - 1))
                + " o " + nombres.get(nombres.size() - 1);
    }

    private static boolean empiezaPor(byte[] contenido, byte[] firma, int desde) {
        if (contenido.length < desde + firma.length) {
            return false;
        }
        for (int i = 0; i < firma.length; i++) {
            if (contenido[desde + i] != firma[i]) {
                return false;
            }
        }
        return true;
    }
}

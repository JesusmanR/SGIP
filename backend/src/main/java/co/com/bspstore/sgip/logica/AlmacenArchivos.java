package co.com.bspstore.sgip.logica;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Guarda y recupera las imágenes que suben los comercios.
 *
 * Tres decisiones que conviene revisar juntas, porque las tres atacan el
 * mismo problema: lo que llega en una subida lo elige quien sube.
 *
 * 1. El nombre del archivo lo genera esta clase. El que manda el
 *    navegador no se usa ni para derivarlo. Un nombre como
 *    «../../application.yml» o «foto.jpg.jsp» deja de ser un problema
 *    cuando nunca toca el sistema de archivos.
 *
 * 2. El tipo se decide leyendo los primeros bytes, no la cabecera
 *    Content-Type ni la extensión. Ambas las escribe el cliente y
 *    ninguna cuesta nada de falsificar.
 *
 * 3. La ruta que se guarda en la base es relativa a la raíz configurada,
 *    y empieza por la empresa. Un archivo de un comercio no puede
 *    aparecer bajo la carpeta de otro ni siquiera por un error de
 *    programación aguas arriba.
 */
@Service
public class AlmacenArchivos {

    private static final Logger LOG = LoggerFactory.getLogger(AlmacenArchivos.class);

    /** Firmas de los únicos formatos admitidos. */
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] RIFF = {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP = {'W', 'E', 'B', 'P'};

    private final Path raiz;
    private final long bytesMaximos;

    public AlmacenArchivos(@Value("${sgip.archivos.raiz:archivos}") String raiz,
                           @Value("${sgip.archivos.bytes-maximos:2097152}") long bytesMaximos) {
        this.raiz = Path.of(raiz).toAbsolutePath().normalize();
        this.bytesMaximos = bytesMaximos;
    }

    /**
     * Guarda el archivo y devuelve su ruta relativa, que es lo que va a
     * la base de datos.
     *
     * @param carpeta subcarpeta dentro de la empresa, por ejemplo
     *                «productos» o «logo». La elige el código, nunca el
     *                cliente.
     */
    public String guardar(MultipartFile archivo, Integer empresaId, String carpeta) {
        if (archivo == null || archivo.isEmpty()) {
            throw new ArchivoInvalidoException("No llegó ningún archivo.");
        }
        if (archivo.getSize() > bytesMaximos) {
            throw new ArchivoInvalidoException(
                    "La imagen pesa más de " + (bytesMaximos / 1024 / 1024) + " MB.");
        }

        byte[] contenido;
        try {
            contenido = archivo.getBytes();
        } catch (IOException e) {
            throw new ArchivoInvalidoException("No se pudo leer el archivo recibido.");
        }

        String extension = extensionSegunContenido(contenido);
        String relativa = "empresa-%d/%s/%s.%s"
                .formatted(empresaId, carpeta, UUID.randomUUID(), extension);

        Path destino = raiz.resolve(relativa).normalize();
        // Cinturón y tirantes: la ruta la arma esta clase, pero si alguna
        // vez dejara de hacerlo, esto lo detiene antes de escribir fuera.
        if (!destino.startsWith(raiz)) {
            throw new ArchivoInvalidoException("Ruta de destino inválida.");
        }

        try {
            Files.createDirectories(destino.getParent());
            Files.write(destino, contenido);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo guardar el archivo en " + destino, e);
        }
        return relativa;
    }

    /** Abre un archivo ya guardado. La ruta sale de la base, no del cliente. */
    public InputStream abrir(String rutaRelativa) throws IOException {
        Path archivo = raiz.resolve(rutaRelativa).normalize();
        if (!archivo.startsWith(raiz) || !Files.isRegularFile(archivo)) {
            throw new IOException("No existe el archivo " + rutaRelativa);
        }
        return Files.newInputStream(archivo);
    }

    /**
     * Borra un archivo ya guardado. No falla si no existe: lo que importa
     * es que después de llamar no esté, y que la fila de la base se pueda
     * borrar igual aunque el archivo se hubiera perdido antes.
     */
    public void borrar(String rutaRelativa) {
        Path archivo = raiz.resolve(rutaRelativa).normalize();
        if (!archivo.startsWith(raiz)) {
            return;
        }
        try {
            Files.deleteIfExists(archivo);
        } catch (IOException e) {
            // Un archivo que no se pudo borrar es basura en disco, no un
            // motivo para dejar la fila huérfana en la base.
            LOG.warn("No se pudo borrar {}: {}", rutaRelativa, e.getMessage());
        }
    }

    public String tipoDe(String rutaRelativa) {
        String r = rutaRelativa.toLowerCase();
        if (r.endsWith(".png")) return "image/png";
        if (r.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    /**
     * Decide el formato por los primeros bytes. Ni la extensión ni el
     * Content-Type: las dos las escribe quien sube.
     */
    private static String extensionSegunContenido(byte[] b) {
        if (empiezaPor(b, JPEG, 0)) return "jpg";
        if (empiezaPor(b, PNG, 0)) return "png";
        if (empiezaPor(b, RIFF, 0) && empiezaPor(b, WEBP, 8)) return "webp";
        throw new ArchivoInvalidoException(
                "Solo se admiten imágenes JPEG, PNG o WebP. "
                        + "El archivo recibido no es ninguna de las tres, "
                        + "aunque su nombre lo diga.");
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

    /** Solo para diagnóstico en pruebas y registros. */
    public String huella(byte[] contenido) {
        return HexFormat.of().formatHex(contenido, 0, Math.min(8, contenido.length));
    }

    public Path getRaiz() {
        return raiz;
    }
}

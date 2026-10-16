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

    /**
     * Tope de lo que se admite recibir. Es generoso a propósito: lo que
     * se guarda no mide esto, porque la imagen se normaliza antes de
     * tocar el disco —800×800 en JPEG ronda los 100 KB—. El tope solo
     * limita lo que viaja por la red, y una foto de teléfono pesa entre
     * tres y ocho megas.
     *
     * Estaba en el application.yml con 2 MB, que es menos que cualquier
     * foto recién tomada, y además había que mantenerlo de acuerdo con
     * los dos límites de multipart. Tres números que debían coincidir a
     * mano, y el síntoma de que no coincidieran era el peor posible: la
     * petición moría antes de llegar al controlador. Ahora es uno solo y
     * vive en el código, que es lo que sí viaja con un «git pull».
     */
    public static final long BYTES_MAXIMOS = 10L * 1024 * 1024;

    private final Path raiz;

    public AlmacenArchivos(@Value("${sgip.archivos.raiz:archivos}") String raiz) {
        this.raiz = Path.of(raiz).toAbsolutePath().normalize();
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
        return guardar(leerValidado(archivo), empresaId, carpeta);
    }

    /**
     * Comprueba lo que llegó y devuelve sus bytes. Separado de guardar
     * para que la normalización pueda ocurrir en medio: se valida lo que
     * sube el cliente, se transforma, y se guarda el resultado.
     */
    public byte[] leerValidado(MultipartFile archivo) {
        if (archivo == null || archivo.isEmpty()) {
            throw new ArchivoInvalidoException("No llegó ningún archivo.");
        }
        if (archivo.getSize() > BYTES_MAXIMOS) {
            throw new ArchivoInvalidoException(
                    "La imagen pesa más de " + (BYTES_MAXIMOS / 1024 / 1024) + " MB.");
        }
        try {
            byte[] contenido = archivo.getBytes();
            extensionSegunContenido(contenido);   // rechaza lo que no sea imagen
            return contenido;
        } catch (IOException e) {
            throw new ArchivoInvalidoException("No se pudo leer el archivo recibido.");
        }
    }

    public String guardar(byte[] contenido, Integer empresaId, String carpeta) {
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
        return Files.newInputStream(ubicar(rutaRelativa));
    }

    /** Lee un archivo entero. Para lo que haya que transformar en memoria. */
    public byte[] leer(String rutaRelativa) throws IOException {
        return Files.readAllBytes(ubicar(rutaRelativa));
    }

    private Path ubicar(String rutaRelativa) throws IOException {
        Path archivo = raiz.resolve(rutaRelativa).normalize();
        if (!archivo.startsWith(raiz) || !Files.isRegularFile(archivo)) {
            throw new IOException("No existe el archivo " + rutaRelativa);
        }
        return archivo;
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
        return FormatoImagen.deRuta(rutaRelativa).tipoMime();
    }

    /**
     * Decide el formato por los primeros bytes y comprueba que además se
     * pueda procesar.
     *
     * Las dos preguntas son distintas y antes solo se hacía la primera:
     * un WebP pasaba el reconocimiento y moría más adelante, en el
     * normalizador, con un mensaje que culpaba al archivo de estar
     * dañado. Que esta instalación no tenga lector de WebP es una
     * limitación nuestra, y así hay que decirlo.
     */
    private static String extensionSegunContenido(byte[] b) {
        FormatoImagen formato = FormatoImagen.deContenido(b);
        if (formato == null) {
            throw new ArchivoInvalidoException(
                    "El archivo recibido no es una imagen " + FormatoImagen.legiblesEnTexto()
                            + ", aunque su nombre lo diga.");
        }
        if (!formato.sePuedeLeer()) {
            throw new ArchivoInvalidoException(
                    "El archivo es " + formato.nombreVisible() + " y esta instalación no sabe "
                            + "leer ese formato. Guárdalo como " + FormatoImagen.legiblesEnTexto()
                            + " y vuelve a subirlo.");
        }
        return formato.extension();
    }

    /** Solo para diagnóstico en pruebas y registros. */
    public String huella(byte[] contenido) {
        return HexFormat.of().formatHex(contenido, 0, Math.min(8, contenido.length));
    }

    public Path getRaiz() {
        return raiz;
    }
}

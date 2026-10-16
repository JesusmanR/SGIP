package co.com.bspstore.sgip.logica;

import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Deja todas las imágenes del mismo tamaño antes de guardarlas.
 *
 * Normalizar al subir y no al mostrar es lo que mantiene el catálogo
 * cuadrado en todas partes: la pantalla web, el documento exportable y
 * cualquier cliente que venga después ven el mismo archivo. Ajustarlo
 * solo con CSS arregla una pantalla y deja las demás a su suerte, y
 * además obliga al navegador a descargar los diez megapíxeles que salen
 * de un teléfono para pintarlos en 44 puntos.
 *
 * Las dos reglas son opuestas a propósito:
 *
 * Una FOTO DE PRODUCTO se recorta al centro hasta quedar cuadrada. Que
 * todas midan igual importa más que conservar los bordes, porque una
 * cuadrícula con fotos de proporciones distintas se ve rota.
 *
 * Un LOGO nunca se recorta: se encoge hasta caber entero. Recortar un
 * logo le corta el nombre a media empresa, y muchos son alargados.
 */
@Service
public class NormalizadorImagenes {

    /** Calidad JPEG. Por encima de 0,85 el archivo crece sin verse mejor. */
    private static final float CALIDAD = 0.82f;

    /**
     * Foto de producto: cuadrada, recortando el centro.
     * Sale en JPEG, que para fotografías pesa mucho menos que PNG.
     */
    public byte[] cuadrada(byte[] original, int lado) {
        BufferedImage fuente = leer(original);
        int corte = Math.min(fuente.getWidth(), fuente.getHeight());
        BufferedImage recorte = fuente.getSubimage(
                (fuente.getWidth() - corte) / 2,
                (fuente.getHeight() - corte) / 2,
                corte, corte);
        return aJpeg(redimensionar(recorte, lado, lado));
    }

    /**
     * Logo: entero dentro de un cuadro, sin recortar y sin deformar.
     * Sale en PNG para conservar la transparencia, que es justo lo que
     * permite ponerlo sobre cualquier fondo.
     */
    public byte[] ajustada(byte[] original, int maxLado) {
        BufferedImage fuente = leer(original);
        double escala = Math.min(
                (double) maxLado / fuente.getWidth(),
                (double) maxLado / fuente.getHeight());
        // Un logo más pequeño que el cuadro se deja como está: ampliarlo
        // solo lo volvería borroso.
        if (escala >= 1) {
            return aPng(fuente);
        }
        return aPng(redimensionar(fuente,
                Math.max(1, (int) Math.round(fuente.getWidth() * escala)),
                Math.max(1, (int) Math.round(fuente.getHeight() * escala))));
    }

    // ── Lectura ──────────────────────────────────────────────────────
    private BufferedImage leer(byte[] contenido) {
        BufferedImage imagen;
        try {
            imagen = ImageIO.read(new ByteArrayInputStream(contenido));
        } catch (IOException e) {
            throw new ArchivoInvalidoException("No se pudo leer la imagen.");
        }
        if (imagen == null) {
            // Las firmas coincidían pero el contenido no se puede decodificar:
            // un archivo truncado o corrupto.
            throw new ArchivoInvalidoException("La imagen está dañada o incompleta.");
        }
        return enderezar(imagen, orientacionExif(contenido));
    }

    // ── Transformaciones ─────────────────────────────────────────────
    private static BufferedImage redimensionar(BufferedImage fuente, int ancho, int alto) {
        BufferedImage destino = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = destino.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(fuente, 0, 0, ancho, alto, null);
        g.dispose();
        return destino;
    }

    /**
     * Aplica la orientación que la cámara dejó anotada.
     *
     * Hace falta porque al reescribir la imagen se pierden sus metadatos.
     * Un teléfono casi nunca gira los píxeles: guarda la foto como salió
     * del sensor y anota «esto va girado». El navegador respeta esa nota,
     * de modo que si la borramos sin aplicarla, fotos que antes se veían
     * derechas aparecerían de lado. Arreglar el tamaño rompiendo la
     * orientación no sería un arreglo.
     */
    private static BufferedImage enderezar(BufferedImage imagen, int orientacion) {
        if (orientacion <= 1 || orientacion > 8) {
            return imagen;
        }
        int a = imagen.getWidth();
        int h = imagen.getHeight();
        boolean gira = orientacion >= 5;

        BufferedImage destino = new BufferedImage(gira ? h : a, gira ? a : h,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = destino.createGraphics();
        AffineTransform t = switch (orientacion) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, a, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, a, h);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, h, a);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, a);
            default -> new AffineTransform();
        };
        g.drawImage(imagen, t, null);
        g.dispose();
        return destino;
    }

    /**
     * Busca la orientación en los metadatos EXIF del JPEG. Devuelve 1
     * —sin giro— si no hay EXIF o si algo no cuadra: ante la duda, no
     * tocar la imagen.
     */
    static int orientacionExif(byte[] b) {
        // Todo lo que sigue recorre bytes que mandó el cliente. Un EXIF
        // mal formado no puede tumbar una subida: ante cualquier duda,
        // ante cualquier indice raro, se devuelve 1 y no se gira nada.
        try {
            return buscarOrientacion(b);
        } catch (RuntimeException e) {
            return 1;
        }
    }

    private static int buscarOrientacion(byte[] b) {
        // Recorre los segmentos del JPEG hasta encontrar APP1 con «Exif».
        int i = 2;
        while (i + 4 < b.length && (b[i] & 0xFF) == 0xFF) {
            int marca = b[i + 1] & 0xFF;
            int largo = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if (marca == 0xE1 && i + 10 < b.length
                    && b[i + 4] == 'E' && b[i + 5] == 'x' && b[i + 6] == 'i' && b[i + 7] == 'f') {
                return enBloqueTiff(b, i + 10, largo - 8);
            }
            if (marca == 0xDA || largo < 2) {
                break;   // datos de imagen, o longitud imposible
            }
            i += 2 + largo;
        }
        return 1;
    }

    private static int enBloqueTiff(byte[] b, int inicio, int largo) {
        if (inicio + 8 > b.length) {
            return 1;
        }
        // El bloque TIFF declara su orden de bytes: «II» poco extremo,
        // «MM» gran extremo. Cualquier otra cosa no es un bloque TIFF.
        boolean granExtremo = b[inicio] == 'M' && b[inicio + 1] == 'M';
        if (!granExtremo && !(b[inicio] == 'I' && b[inicio + 1] == 'I')) {
            return 1;
        }

        int desplazamiento = leer32(b, inicio + 4, granExtremo);
        // El desplazamiento lo escribe el archivo. Uno negativo o enorme
        // mandaria la lectura fuera del arreglo, que es justo lo que no
        // puede pasar con datos que vienen de fuera.
        if (desplazamiento < 8 || desplazamiento > largo) {
            return 1;
        }
        int ifd = inicio + desplazamiento;
        if (ifd < inicio || ifd + 2 > b.length) {
            return 1;
        }
        int campos = leer16(b, ifd, granExtremo);
        for (int k = 0; k < campos; k++) {
            int campo = ifd + 2 + k * 12;
            if (campo < 0 || campo + 12 > b.length || campo + 12 > inicio + largo) {
                return 1;
            }
            if (leer16(b, campo, granExtremo) == 0x0112) {
                return leer16(b, campo + 8, granExtremo);
            }
        }
        return 1;
    }

    private static int leer16(byte[] b, int i, boolean granExtremo) {
        return granExtremo
                ? ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF)
                : ((b[i + 1] & 0xFF) << 8) | (b[i] & 0xFF);
    }

    private static int leer32(byte[] b, int i, boolean granExtremo) {
        return granExtremo
                ? ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16)
                        | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF)
                : ((b[i + 3] & 0xFF) << 24) | ((b[i + 2] & 0xFF) << 16)
                        | ((b[i + 1] & 0xFF) << 8) | (b[i] & 0xFF);
    }

    // ── Escritura ────────────────────────────────────────────────────
    private static byte[] aJpeg(BufferedImage imagen) {
        // JPEG no tiene transparencia: sin este paso, lo transparente se
        // vuelve negro. Se compone sobre blanco, que es el fondo de las
        // fichas y del documento exportable.
        BufferedImage opaca = new BufferedImage(
                imagen.getWidth(), imagen.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = opaca.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, imagen.getWidth(), imagen.getHeight());
        g.drawImage(imagen, 0, 0, null);
        g.dispose();

        Iterator<ImageWriter> escritores = ImageIO.getImageWritersByFormatName("jpeg");
        if (!escritores.hasNext()) {
            throw new IllegalStateException("Esta JVM no sabe escribir JPEG.");
        }
        ImageWriter escritor = escritores.next();
        ImageWriteParam param = escritor.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(CALIDAD);

        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        try (ImageOutputStream flujo = ImageIO.createImageOutputStream(salida)) {
            escritor.setOutput(flujo);
            escritor.write(null, new IIOImage(opaca, null, null), param);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo codificar la imagen en JPEG", e);
        } finally {
            escritor.dispose();
        }
        return salida.toByteArray();
    }

    private static byte[] aPng(BufferedImage imagen) {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        try {
            ImageIO.write(imagen, "png", salida);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo codificar la imagen en PNG", e);
        }
        return salida.toByteArray();
    }
}

package co.com.bspstore.sgip.logica;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NormalizadorImagenesTest {

    private final NormalizadorImagenes normalizador = new NormalizadorImagenes();

    /** Una imagen con mitad izquierda roja y derecha azul, para ver el recorte. */
    private static byte[] imagen(int ancho, int alto, String formato) throws IOException {
        BufferedImage i = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = i.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, ancho / 2, alto);
        g.setColor(Color.BLUE);
        g.fillRect(ancho / 2, 0, ancho - ancho / 2, alto);
        g.dispose();

        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        ImageIO.write(i, formato, salida);
        return salida.toByteArray();
    }

    private static BufferedImage decodificar(byte[] b) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(b));
    }

    // ── Foto de producto ─────────────────────────────────────────────
    @Test
    void unaFotoApaisadaSaleCuadrada() throws IOException {
        BufferedImage r = decodificar(normalizador.cuadrada(imagen(1600, 900, "jpeg"), 800));

        assertEquals(800, r.getWidth());
        assertEquals(800, r.getHeight());
    }

    @Test
    void unaFotoVerticalTambienSaleCuadrada() throws IOException {
        BufferedImage r = decodificar(normalizador.cuadrada(imagen(600, 1800, "png"), 800));

        assertEquals(800, r.getWidth());
        assertEquals(800, r.getHeight());
    }

    @Test
    void todasSalenDelMismoTamanoVenganComoVengan() throws IOException {
        // Es lo que pedía el problema: que el catálogo no se desconfigure.
        for (int[] medida : new int[][]{{320, 240}, {4000, 3000}, {900, 900}, {100, 2000}}) {
            BufferedImage r = decodificar(
                    normalizador.cuadrada(imagen(medida[0], medida[1], "jpeg"), 800));
            assertEquals(800, r.getWidth(), "ancho de " + medida[0] + "x" + medida[1]);
            assertEquals(800, r.getHeight(), "alto de " + medida[0] + "x" + medida[1]);
        }
    }

    @Test
    void laFotoNormalizadaPesaMuchoMenosQueLaOriginal() throws IOException {
        byte[] original = imagen(4000, 3000, "png");
        byte[] normalizada = normalizador.cuadrada(original, 800);

        assertTrue(normalizada.length < original.length / 2,
                "original " + original.length + ", normalizada " + normalizada.length);
    }

    @Test
    void laFotoSaleEnJpeg() throws IOException {
        byte[] r = normalizador.cuadrada(imagen(1000, 1000, "png"), 400);

        assertEquals((byte) 0xFF, r[0]);
        assertEquals((byte) 0xD8, r[1]);
    }

    // ── Logo ─────────────────────────────────────────────────────────
    @Test
    void unLogoAlargadoNoSeRecortaNiSeDeforma() throws IOException {
        // Recortar un logo le corta el nombre a media empresa.
        BufferedImage r = decodificar(normalizador.ajustada(imagen(1200, 300, "png"), 400));

        assertEquals(400, r.getWidth());
        assertEquals(100, r.getHeight());   // conserva la proporción 4:1
    }

    @Test
    void unLogoAltoCabeDentroDelCuadro() throws IOException {
        BufferedImage r = decodificar(normalizador.ajustada(imagen(200, 1000, "png"), 400));

        assertEquals(80, r.getWidth());
        assertEquals(400, r.getHeight());
    }

    @Test
    void unLogoPequenoNoSeAmplia() throws IOException {
        // Ampliarlo solo lo volvería borroso.
        BufferedImage r = decodificar(normalizador.ajustada(imagen(120, 60, "png"), 400));

        assertEquals(120, r.getWidth());
        assertEquals(60, r.getHeight());
    }

    @Test
    void elLogoSaleEnPngParaConservarLaTransparencia() throws IOException {
        byte[] r = normalizador.ajustada(imagen(800, 800, "jpeg"), 400);

        assertEquals((byte) 0x89, r[0]);
        assertEquals((byte) 'P', r[1]);
    }

    // ── Entradas malas ───────────────────────────────────────────────
    @Test
    void unArchivoQueNoSePuedeDecodificarSeRechaza() {
        // Las firmas podrían coincidir y el contenido estar truncado.
        byte[] trozo = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0};

        assertThrows(ArchivoInvalidoException.class, () -> normalizador.cuadrada(trozo, 800));
    }

    // ── Orientación EXIF ─────────────────────────────────────────────
    @Test
    void sinExifNoHayGiro() throws IOException {
        assertEquals(1, NormalizadorImagenes.orientacionExif(imagen(10, 10, "jpeg")));
    }

    @Test
    void unJpegConExifDeclarandoGiroSeLee() {
        // APP1 con un bloque TIFF mínimo: un solo campo, 0x0112 = 6.
        byte[] b = new byte[]{
            (byte) 0xFF, (byte) 0xD8,                       // SOI
            (byte) 0xFF, (byte) 0xE1, 0, 26,                // APP1, largo 26
            'E', 'x', 'i', 'f', 0, 0,
            'M', 'M', 0, 42, 0, 0, 0, 8,                    // TIFF gran extremo, IFD en 8
            0, 1,                                           // un campo
            1, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0,          // orientación = 6
        };
        assertEquals(6, NormalizadorImagenes.orientacionExif(b));
    }

    @Test
    void unExifIlegibleNoRompeNiGira() {
        byte[] b = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0, 8, 'E', 'x', 'i', 'f'};

        assertEquals(1, NormalizadorImagenes.orientacionExif(b));
    }
}

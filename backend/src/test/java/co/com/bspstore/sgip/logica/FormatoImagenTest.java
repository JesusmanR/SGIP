package co.com.bspstore.sgip.logica;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormatoImagenTest {

    private static byte[] jpeg() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0};
    }

    private static byte[] png() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
    }

    private static byte[] webp() {
        return new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 0, 0};
    }

    @Test
    void reconoceLosTresPorSuFirma() {
        assertEquals(FormatoImagen.JPEG, FormatoImagen.deContenido(jpeg()));
        assertEquals(FormatoImagen.PNG, FormatoImagen.deContenido(png()));
        assertEquals(FormatoImagen.WEBP, FormatoImagen.deContenido(webp()));
    }

    @Test
    void loQueNoEsImagenNoEsNingunFormato() {
        assertNull(FormatoImagen.deContenido("<?php ?>".getBytes()));
        assertNull(FormatoImagen.deContenido(new byte[0]));
        assertNull(FormatoImagen.deContenido(new byte[]{1, 2}));
    }

    @Test
    void unRiffQueNoEsWebpNoPasaPorWebp() {
        // RIFF es también el contenedor de WAV y de AVI. La firma está en
        // el byte 8, no en el 0, y por eso hacen falta las dos partes.
        byte[] wav = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E', 0, 0};

        assertNull(FormatoImagen.deContenido(wav));
    }

    @Test
    void reconocerUnFormatoNoEsSaberLeerlo() {
        // Toda la razón de existir de esta clase. El almacén admitía WebP
        // porque reconocía su firma, y después ImageIO no sabía
        // decodificarlo; el usuario recibía «la imagen está dañada o
        // incompleta» por un archivo impecable.
        assertTrue(FormatoImagen.JPEG.sePuedeLeer());
        assertTrue(FormatoImagen.PNG.sePuedeLeer());
        // WebP depende de la instalación, así que no se afirma nada de
        // él: lo que importa es que la pregunta se haga.
    }

    @Test
    void elTextoDeAdmitidosSoloNombraLoQueSePuedeLeer() {
        String texto = FormatoImagen.legiblesEnTexto();

        assertTrue(texto.contains("JPEG"), texto);
        assertTrue(texto.contains("PNG"), texto);
        assertEquals(FormatoImagen.WEBP.sePuedeLeer(), texto.contains("WebP"), texto);
    }

    @Test
    void elTipoServidoSaleDeLaExtensionGuardada() {
        assertEquals("image/png", FormatoImagen.deRuta("empresa-1/logo/a.png").tipoMime());
        assertEquals("image/webp", FormatoImagen.deRuta("empresa-1/logo/a.webp").tipoMime());
        assertEquals("image/jpeg", FormatoImagen.deRuta("empresa-1/logo/a.jpg").tipoMime());
        // Un WebP guardado antes de este cambio sigue sirviéndose bien:
        // el navegador sí sabe mostrarlo, aunque el servidor no sepa
        // decodificarlo para normalizarlo.
    }
}

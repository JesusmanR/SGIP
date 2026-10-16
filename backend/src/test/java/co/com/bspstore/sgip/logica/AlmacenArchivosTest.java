package co.com.bspstore.sgip.logica;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlmacenArchivosTest {

    @TempDir Path raiz;

    private AlmacenArchivos almacen() {
        return new AlmacenArchivos(raiz.toString(), 2 * 1024 * 1024);
    }

    private static byte[] jpegDe(int bytes) {
        byte[] b = new byte[bytes];
        b[0] = (byte) 0xFF; b[1] = (byte) 0xD8; b[2] = (byte) 0xFF;
        return b;
    }

    private static byte[] png() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
    }

    private static byte[] webp() {
        return new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 0, 0};
    }

    @Test
    void guardaLaImagenYDevuelveUnaRutaRelativaDeLaEmpresa() {
        String ruta = almacen().guardar(
                new MockMultipartFile("archivo", "foto.jpg", "image/jpeg", jpegDe(64)), 7, "productos");

        assertTrue(ruta.startsWith("empresa-7/productos/"), ruta);
        assertTrue(ruta.endsWith(".jpg"), ruta);
        assertTrue(Files.isRegularFile(raiz.resolve(ruta)));
    }

    @Test
    void elNombreQueMandaElClienteNoSeUsa() {
        // Si el nombre entrara al sistema de archivos, este bajaría dos
        // carpetas y se saldría de la raíz.
        String ruta = almacen().guardar(
                new MockMultipartFile("archivo", "../../application.yml", "image/jpeg", jpegDe(32)),
                7, "productos");

        assertFalse(ruta.contains(".."), ruta);
        assertFalse(ruta.contains("application"), ruta);
        assertTrue(raiz.resolve(ruta).normalize().startsWith(raiz));
    }

    @Test
    void elTipoSeDecidePorElContenidoNoPorLaCabecera() {
        // Se presenta como JPEG y no lo es.
        var impostor = new MockMultipartFile("archivo", "foto.jpg", "image/jpeg",
                "<?php system($_GET['c']); ?>".getBytes(StandardCharsets.UTF_8));

        assertThrows(ArchivoInvalidoException.class,
                () -> almacen().guardar(impostor, 7, "productos"));
    }

    @Test
    void laExtensionSaleDelContenidoYNoDelNombre() {
        // El archivo es PNG aunque se llame .jpg.
        String ruta = almacen().guardar(
                new MockMultipartFile("archivo", "foto.jpg", "image/jpeg", png()), 7, "productos");

        assertTrue(ruta.endsWith(".png"), ruta);
    }

    @Test
    void admiteLosTresFormatos() {
        AlmacenArchivos a = almacen();
        assertTrue(a.guardar(new MockMultipartFile("archivo", "a", null, jpegDe(16)), 1, "x").endsWith(".jpg"));
        assertTrue(a.guardar(new MockMultipartFile("archivo", "b", null, png()), 1, "x").endsWith(".png"));
        assertTrue(a.guardar(new MockMultipartFile("archivo", "c", null, webp()), 1, "x").endsWith(".webp"));
    }

    @Test
    void rechazaUnArchivoDemasiadoGrande() {
        AlmacenArchivos pequeno = new AlmacenArchivos(raiz.toString(), 100);

        assertThrows(ArchivoInvalidoException.class,
                () -> pequeno.guardar(
                        new MockMultipartFile("archivo", "a.jpg", "image/jpeg", jpegDe(200)), 1, "x"));
    }

    @Test
    void rechazaUnArchivoVacio() {
        assertThrows(ArchivoInvalidoException.class,
                () -> almacen().guardar(
                        new MockMultipartFile("archivo", "a.jpg", "image/jpeg", new byte[0]), 1, "x"));
    }

    @Test
    void dosSubidasDelMismoArchivoNoSePisan() {
        AlmacenArchivos a = almacen();
        var archivo = new MockMultipartFile("archivo", "foto.jpg", "image/jpeg", jpegDe(32));

        assertFalse(a.guardar(archivo, 1, "productos")
                .equals(a.guardar(archivo, 1, "productos")));
    }

    @Test
    void cadaEmpresaEscribeEnSuPropiaCarpeta() {
        AlmacenArchivos a = almacen();
        var archivo = new MockMultipartFile("archivo", "foto.jpg", "image/jpeg", jpegDe(32));

        assertTrue(a.guardar(archivo, 3, "productos").startsWith("empresa-3/"));
        assertTrue(a.guardar(archivo, 8, "productos").startsWith("empresa-8/"));
    }

    @Test
    void abrirDevuelveLoQueSeGuardo() throws IOException {
        AlmacenArchivos a = almacen();
        byte[] contenido = jpegDe(48);
        String ruta = a.guardar(new MockMultipartFile("archivo", "f.jpg", null, contenido), 1, "x");

        try (var entrada = a.abrir(ruta)) {
            assertEquals(contenido.length, entrada.readAllBytes().length);
        }
    }

    @Test
    void abrirNoSaleDeLaRaizAunqueLaRutaLoIntente() {
        assertThrows(IOException.class, () -> almacen().abrir("../../../etc/passwd"));
    }

    @Test
    void elTipoQueSeSirveSaleDeLaExtensionGuardada() {
        AlmacenArchivos a = almacen();
        assertEquals("image/png", a.tipoDe("empresa-1/x/abc.png"));
        assertEquals("image/webp", a.tipoDe("empresa-1/x/abc.webp"));
        assertEquals("image/jpeg", a.tipoDe("empresa-1/x/abc.jpg"));
    }
}

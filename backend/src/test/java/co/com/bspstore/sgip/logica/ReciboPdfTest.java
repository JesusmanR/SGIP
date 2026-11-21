package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.Recibo;
import co.com.bspstore.sgip.api.dto.Recibo.LineaDeRecibo;
import co.com.bspstore.sgip.api.dto.Recibo.PagoDeRecibo;
import co.com.bspstore.sgip.modelo.TipoMedio;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El recibo en PDF (RF-70): una sola página de 80 mm de ancho cuyo alto sale del contenido, con los acentos del
 * español, sin que un carácter raro de un nombre de producto lo rompa, y rápido. No hay base: el PDF sale del
 * {@link Recibo}.
 */
class ReciboPdfTest {

    /** 80 mm en puntos tipográficos: 80 / 25,4 * 72. */
    private static final float OCHENTA_MM = 226.77f;

    private final ReciboPdf pdf = new ReciboPdf();

    private static BigDecimal d(String valor) {
        return new BigDecimal(valor);
    }

    private static Recibo recibo(List<LineaDeRecibo> lineas, BigDecimal iva, List<String> marcas) {
        return new Recibo("Black Steel Parts S.A.S.", "900123456-7", "V-00000012",
                OffsetDateTime.of(2026, 11, 21, 15, 30, 5, 0, ZoneOffset.ofHours(-5)), "Ana Gómez", "Carlos Ruiz",
                "CC 79123456", lineas, d("20000.00"), d("1000.00"), iva, d("22610.00"),
                List.of(new PagoDeRecibo("Tarjeta", TipoMedio.TARJETA, d("12610.00"), null, "AUT-889"),
                        new PagoDeRecibo("Efectivo", TipoMedio.EFECTIVO, d("10000.00"), d("15000.00"), null)),
                d("5000.00"), marcas);
    }

    private static LineaDeRecibo linea(String nombre) {
        return new LineaDeRecibo(nombre, 2, d("10000.00"), d("1000.00"), d("3610.00"), d("22610.00"));
    }

    private static List<LineaDeRecibo> lineas(int cuantas) {
        List<LineaDeRecibo> l = new ArrayList<>();
        for (int i = 1; i <= cuantas; i++) {
            l.add(linea("Producto de prueba número " + i));
        }
        return l;
    }

    private static PDDocument abrir(byte[] bytes) throws IOException {
        return Loader.loadPDF(bytes);
    }

    private static String texto(byte[] bytes) throws IOException {
        try (PDDocument documento = abrir(bytes)) {
            return new PDFTextStripper().getText(documento);
        }
    }

    // ------------------------------------------------------- el formato

    @Test
    void elPdfMideOchentaMilimetrosDeAncho() throws IOException {
        byte[] bytes = pdf.generar(recibo(lineas(3), d("3610.00"), List.of()));

        try (PDDocument documento = abrir(bytes)) {
            assertEquals(1, documento.getNumberOfPages(), "el recibo es una sola tirilla");
            assertEquals(OCHENTA_MM, documento.getPage(0).getMediaBox().getWidth(), 0.01f);
        }
    }

    @Test
    void elAltoSaleDelContenidoYSigueSiendoUnaPagina() throws IOException {
        byte[] corto = pdf.generar(recibo(lineas(1), d("3610.00"), List.of()));
        byte[] largo = pdf.generar(recibo(lineas(60), d("3610.00"), List.of()));

        try (PDDocument a = abrir(corto); PDDocument b = abrir(largo)) {
            assertEquals(1, b.getNumberOfPages());
            assertEquals(OCHENTA_MM, b.getPage(0).getMediaBox().getWidth(), 0.01f);
            assertTrue(b.getPage(0).getMediaBox().getHeight() > a.getPage(0).getMediaBox().getHeight() * 2,
                    "con sesenta líneas la tirilla es bastante más larga que con una");
        }
    }

    @Test
    void elPdfSeGeneraEnMenosDeTresSegundosCon20Lineas() {
        Recibo recibo = recibo(lineas(20), d("3610.00"), List.of("Domicilio"));

        long inicio = System.nanoTime();
        byte[] bytes = pdf.generar(recibo);
        Duration tardo = Duration.ofNanos(System.nanoTime() - inicio);

        assertTrue(bytes.length > 0);
        assertTrue(tardo.compareTo(Duration.ofSeconds(3)) < 0, "el PDF tardó " + tardo.toMillis() + " ms");
    }

    // ------------------------------------------------------- el contenido

    @Test
    void traeLosDatosDelReciboEnFechaDeDocumentoYTerminaConLaFraseDeLaFactura() throws IOException {
        String t = texto(pdf.generar(recibo(lineas(2), d("3610.00"), List.of("Domicilio"))));

        assertTrue(t.contains("Black Steel Parts S.A.S."), t);
        assertTrue(t.contains("900123456-7"), t);
        assertTrue(t.contains("V-00000012"), t);
        assertTrue(t.contains("21/11/2026"), "las fechas de un documento van en dd/mm/aaaa: " + t);
        assertTrue(t.contains("Ana Gómez"), t);
        assertTrue(t.contains("Carlos Ruiz"), t);
        assertTrue(t.contains("Producto de prueba número 2"), t);
        assertTrue(t.contains("Domicilio"), t);
        assertTrue(t.contains("AUT-889"), t);
        assertTrue(t.stripTrailing().endsWith("Este recibo no es una factura electrónica."), t);
    }

    @Test
    void elImporteVaEnLaMismaLineaQueSuRotulo() throws IOException {
        byte[] bytes = pdf.generar(recibo(lineas(1), d("3610.00"), List.of()));

        try (PDDocument documento = abrir(bytes)) {
            PDFTextStripper porPosicion = new PDFTextStripper();
            porPosicion.setSortByPosition(true);
            String t = porPosicion.getText(documento);
            assertTrue(t.lines().anyMatch(l -> l.matches("Subtotal\\s+\\$20\\.000")), t);
            assertTrue(t.lines().anyMatch(l -> l.matches("Total\\s+\\$22\\.610")), t);
            assertTrue(t.lines().anyMatch(l -> l.matches("Cambio\\s+\\$5\\.000")), t);
        }
    }

    @Test
    void sinIvaElPdfNoTraeLaFilaDeIva() throws IOException {
        String con = texto(pdf.generar(recibo(lineas(1), d("3610.00"), List.of())));
        String sin = texto(pdf.generar(recibo(List.of(new LineaDeRecibo("Libro", 1, d("100.00"), d("0.00"), null,
                d("100.00"))), null, List.of())));

        assertTrue(con.contains("IVA"), con);
        assertFalse(sin.contains("IVA"), sin);
    }

    @Test
    void losImportesSeEscribenConPuntoDeMiles() throws IOException {
        String t = texto(pdf.generar(recibo(lineas(1), d("3610.00"), List.of())));

        assertTrue(t.contains("$22.610"), t);
    }

    // ------------------------------------------------------- los caracteres

    @Test
    void celularEconomicoConAcentoSeEscribeSinExcepcion() throws IOException {
        byte[] bytes = assertDoesNotThrow(() -> pdf.generar(
                recibo(List.of(linea("Celular económico")), d("3610.00"), List.of("¿Ñandú? ¡Sí! Año"))));

        String t = texto(bytes);
        assertTrue(t.contains("Celular económico"), t);
        assertTrue(t.contains("¿Ñandú? ¡Sí! Año"), t);
    }

    @Test
    void unNombreConEmojiOCaracteresFueraDelAlfabetoLatinoNoRompeElPdf() {
        List<LineaDeRecibo> raras = List.of(linea("Funda 📱 con 日本語 y ‑guion duro"),
                linea("Cable USB con espacios raros\t y tabulación"), linea(""));

        assertDoesNotThrow(() -> pdf.generar(recibo(raras, d("3610.00"), List.of("Entrega: Calle 1 😀"))));
    }

    @Test
    void unNombreLargoSinEspaciosSeParteYNoSaleDeLaTirilla() throws IOException {
        String largo = "X".repeat(300);
        byte[] bytes = pdf.generar(recibo(List.of(linea(largo)), d("3610.00"), List.of()));

        try (PDDocument documento = abrir(bytes)) {
            assertEquals(OCHENTA_MM, documento.getPage(0).getMediaBox().getWidth(), 0.01f);
        }
        // el importe de la línea va a la derecha del primer trozo: se cuentan las letras, no la cadena entera
        assertEquals(largo.length(), texto(bytes).chars().filter(c -> c == 'X').count());
    }

    // ------------------------------------------------------- varios recibos a la vez

    /**
     * Las fuentes de PDFBox guardan mapas que se llenan al usarlas: compartirlas entre hilos las corrompe. Cada
     * hilo pide recibos con su propio consecutivo y cada PDF tiene que traer el suyo. La carrera no es
     * determinista: la prueba protege, no demuestra.
     */
    @Test
    void variosRecibosALaVezNoSeMezclanNiFallan() throws Exception {
        int hilos = 8;
        int porHilo = 25;
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        try {
            List<Future<Integer>> futuros = new ArrayList<>();
            for (int h = 0; h < hilos; h++) {
                int hilo = h;
                futuros.add(pool.submit(() -> {
                    int bien = 0;
                    for (int i = 0; i < porHilo; i++) {
                        String consecutivo = "V-H" + hilo + "N" + i;
                        Recibo base = recibo(List.of(linea("Celular económico " + consecutivo)), d("3610.00"),
                                List.of("Domicilio"));
                        Recibo suyo = new Recibo(base.empresa(), base.nitEmpresa(), consecutivo, base.fecha(),
                                base.cajero(), base.cliente(), base.identificacionCliente(), base.lineas(),
                                base.subtotal(), base.descuento(), base.iva(), base.total(), base.pagos(),
                                base.cambio(), base.marcas());
                        String t = texto(pdf.generar(suyo));
                        if (t.contains("Venta " + consecutivo) && t.contains("Celular económico " + consecutivo)) {
                            bien++;
                        }
                    }
                    return bien;
                }));
            }
            for (Future<Integer> futuro : futuros) {
                assertEquals(porHilo, futuro.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}

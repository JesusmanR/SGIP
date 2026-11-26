package co.com.bspstore.sgip.logica.informes;

import co.com.bspstore.sgip.logica.informes.LibroDeInforme.Formato;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** El libro .xlsx de un informe: filtros, totales, encabezados y filas, sin que un texto sea fórmula. */
class LibroDeInformeTest {

    private static XSSFWorkbook abrir(ByteArrayOutputStream bytes) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes.toByteArray()));
    }

    /** Los valores de la columna de la hoja, en orden, como texto. */
    private static List<String> columna(Sheet hoja, int columna) {
        List<String> valores = new ArrayList<>();
        for (Row fila : hoja) {
            Cell c = fila.getCell(columna);
            valores.add(c == null ? null : c.getCellType() == CellType.NUMERIC
                    ? String.valueOf(c.getNumericCellValue()) : c.getStringCellValue());
        }
        return valores;
    }

    private static Cell celdaCon(Sheet hoja, int columna, String texto) {
        for (Row fila : hoja) {
            Cell c = fila.getCell(columna);
            if (c != null && c.getCellType() == CellType.STRING && texto.equals(c.getStringCellValue())) {
                return c;
            }
        }
        throw new AssertionError("No hay una celda con «" + texto + "» en la columna " + columna);
    }

    @Test
    void traeElTituloElFiltroElTotalYLasFilas() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (LibroDeInforme libro = new LibroDeInforme(bytes, "Inventario valorizado")) {
            libro.filtro("Categoría", "Frenos");
            libro.total("Valor en bodega", new BigDecimal("1234567"), Formato.PESOS);
            libro.total("Unidades", new BigDecimal("42"), Formato.UNIDADES);
            libro.encabezados("Referencia", "Descripción", "Existencia");
            libro.fila("FR-1", "Pastilla delantera", new BigDecimal("5"));
            libro.fila("FR-2", "Disco", new BigDecimal("7"));
        }

        try (XSSFWorkbook wb = abrir(bytes)) {
            Sheet hoja = wb.getSheetAt(0);
            List<String> primera = columna(hoja, 0);
            assertEquals("Inventario valorizado", primera.get(0));
            assertTrue(primera.contains("Categoría"));
            assertTrue(primera.contains("Valor en bodega"));
            assertTrue(primera.contains("Referencia"));
            assertTrue(primera.contains("FR-1"));
            assertTrue(primera.contains("FR-2"));

            Cell valorDelFiltro = celdaCon(hoja, 0, "Categoría").getRow().getCell(1);
            assertEquals("Frenos", valorDelFiltro.getStringCellValue());
            Cell total = celdaCon(hoja, 0, "Valor en bodega").getRow().getCell(1);
            assertEquals(1234567d, total.getNumericCellValue());

            Row fila = celdaCon(hoja, 0, "FR-1").getRow();
            assertEquals("Pastilla delantera", fila.getCell(1).getStringCellValue());
            assertEquals(5d, fila.getCell(2).getNumericCellValue());

            // Los filtros y los totales van antes que los encabezados, y estos antes que las filas.
            int encabezados = celdaCon(hoja, 0, "Referencia").getRowIndex();
            assertTrue(celdaCon(hoja, 0, "Categoría").getRowIndex() < encabezados);
            assertTrue(celdaCon(hoja, 0, "Valor en bodega").getRowIndex() < encabezados);
            assertTrue(encabezados < fila.getRowNum());
        }
    }

    @Test
    void unTextoQueEmpiezaPorIgualEsTextoYNoFormula() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (LibroDeInforme libro = new LibroDeInforme(bytes, "=HYPERLINK(\"x\")")) {
            libro.filtro("Búsqueda", "=2+2");
            libro.encabezados("=Referencia", "@Descripción");
            libro.fila("=1+1", "+cmd|'/C calc'!A0");
            libro.fila("@SUM(1)", "-5");
        }

        try (XSSFWorkbook wb = abrir(bytes)) {
            Sheet hoja = wb.getSheetAt(0);
            for (String texto : List.of("=1+1", "@SUM(1)")) {
                Cell c = celdaCon(hoja, 0, texto);
                assertEquals(CellType.STRING, c.getCellType(), texto);
                assertTrue(c.getCellStyle().getQuotePrefixed(), texto);
            }
            Cell otra = celdaCon(hoja, 1, "+cmd|'/C calc'!A0");
            assertEquals(CellType.STRING, otra.getCellType());
            assertTrue(otra.getCellStyle().getQuotePrefixed());
            Cell filtro = celdaCon(hoja, 1, "=2+2");
            assertEquals(CellType.STRING, filtro.getCellType());
            assertTrue(filtro.getCellStyle().getQuotePrefixed());
            // El título y los encabezados también son texto.
            for (String texto : List.of("=HYPERLINK(\"x\")", "=Referencia")) {
                Cell c = celdaCon(hoja, 0, texto);
                assertEquals(CellType.STRING, c.getCellType(), texto);
                assertTrue(c.getCellStyle().getQuotePrefixed(), texto);
            }
            Cell encabezado = celdaCon(hoja, 1, "@Descripción");
            assertEquals(CellType.STRING, encabezado.getCellType());
            assertTrue(encabezado.getCellStyle().getQuotePrefixed());
            // Ninguna celda del libro es una fórmula.
            for (Row fila : hoja) {
                for (Cell c : fila) {
                    assertFalse(c.getCellType() == CellType.FORMULA, "fórmula en " + c.getAddress());
                }
            }
        }
    }

    @Test
    void doscientasCincuentaFilasSeEscriben() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (LibroDeInforme libro = new LibroDeInforme(bytes, "Informe")) {
            libro.encabezados("Referencia", "Existencia");
            for (int i = 0; i < 250; i++) {
                libro.fila("REF-" + i, new BigDecimal(i));
            }
        }

        try (XSSFWorkbook wb = abrir(bytes)) {
            Sheet hoja = wb.getSheetAt(0);
            Row encabezado = celdaCon(hoja, 0, "Referencia").getRow();
            assertEquals(250, hoja.getLastRowNum() - encabezado.getRowNum());
            assertEquals("REF-249", hoja.getRow(hoja.getLastRowNum()).getCell(0).getStringCellValue());
            assertEquals(249d, hoja.getRow(hoja.getLastRowNum()).getCell(1).getNumericCellValue());
        }
    }

    @Test
    void losFormatosDePesosUnidadesYFecha() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (LibroDeInforme libro = new LibroDeInforme(bytes, "Informe")) {
            libro.total("Valor", new BigDecimal("1500"), Formato.PESOS);
            libro.total("Unidades", new BigDecimal("12"), Formato.UNIDADES);
            libro.encabezados("Pesos", "Unidades", "Fecha", "Nada", "Texto");
            libro.fila(LibroDeInforme.celda(new BigDecimal("2500.50"), Formato.PESOS),
                    new BigDecimal("3"), LocalDate.of(2026, 11, 26), null, "x");
        }

        try (XSSFWorkbook wb = abrir(bytes)) {
            Sheet hoja = wb.getSheetAt(0);
            assertEquals("\"$\"#,##0",
                    celdaCon(hoja, 0, "Valor").getRow().getCell(1).getCellStyle().getDataFormatString());
            assertEquals("#,##0",
                    celdaCon(hoja, 0, "Unidades").getRow().getCell(1).getCellStyle().getDataFormatString());

            Row encabezado = celdaCon(hoja, 0, "Pesos").getRow();
            Row datos = hoja.getRow(encabezado.getRowNum() + 1);
            assertEquals("\"$\"#,##0", datos.getCell(0).getCellStyle().getDataFormatString());
            assertEquals(2500.5d, datos.getCell(0).getNumericCellValue());
            assertEquals("#,##0", datos.getCell(1).getCellStyle().getDataFormatString());
            assertTrue(DateUtil.isCellDateFormatted(datos.getCell(2)));
            assertEquals(LocalDate.of(2026, 11, 26),
                    datos.getCell(2).getLocalDateTimeCellValue().toLocalDate());
            Cell vacia = datos.getCell(3);
            assertTrue(vacia == null || vacia.getCellType() == CellType.BLANK);
            assertEquals("x", datos.getCell(4).getStringCellValue());
        }
    }

    @Test
    void unTituloLargoOConCaracteresProhibidosNoRompeElLibro() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (LibroDeInforme libro = new LibroDeInforme(bytes, "Ventas: netas / [período] ?* y un título muy largo")) {
            libro.encabezados("A");
        }

        try (XSSFWorkbook wb = abrir(bytes)) {
            String nombre = wb.getSheetName(0);
            assertNotNull(nombre);
            assertTrue(nombre.length() <= 31, nombre);
            assertFalse(nombre.matches(".*[\\[\\]:*?/\\\\].*"), nombre);
        }
    }

    @Test
    void escribeEnLaSalidaAlCerrarYNoLaCierra() throws Exception {
        boolean[] cerrada = {false};
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream salida = new OutputStream() {
            @Override
            public void write(int b) {
                bytes.write(b);
            }

            @Override
            public void write(byte[] b, int desde, int largo) {
                bytes.write(b, desde, largo);
            }

            @Override
            public void close() {
                cerrada[0] = true;
            }
        };

        try (LibroDeInforme libro = new LibroDeInforme(salida, "Informe")) {
            libro.encabezados("A");
        }

        assertTrue(bytes.size() > 0);
        assertFalse(cerrada[0], "la salida es de quien la abrió: el libro no la cierra");
    }

    @Test
    void nombreDeArchivoSinPeriodoLlevaHoy() {
        assertEquals("informe-inventario-valorizado-2026-11-26.xlsx",
                LibroDeInforme.nombreDeArchivo("inventario-valorizado", null, LocalDate.of(2026, 11, 26)));
    }

    @Test
    void nombreDeArchivoConPeriodoLlevaLasDosFechas() {
        Periodo p = new Periodo(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 26));

        assertEquals("informe-ventas-netas-2026-11-01-2026-11-26.xlsx",
                LibroDeInforme.nombreDeArchivo("ventas-netas", p, LocalDate.of(2026, 11, 26)));
    }

    @Test
    void nombreDeArchivoEsSoloAscii() {
        String nombre = LibroDeInforme.nombreDeArchivo("Ñandú \"raro\"\r\nX", null, LocalDate.of(2026, 11, 26));

        assertEquals("informe-nandu-raro-x-2026-11-26.xlsx", nombre);
        assertTrue(nombre.matches("[a-z0-9.-]+"), nombre);
    }

    // ------------------------------------------------------------- escribir: el punto único de uso seguro

    /** Una salida que cuenta lo que le escriben. */
    private static final class SalidaContada extends OutputStream {
        int bytes;
        int escrituras;

        @Override
        public void write(int b) {
            bytes++;
            escrituras++;
        }

        @Override
        public void write(byte[] b, int desde, int largo) {
            bytes += largo;
            escrituras++;
        }
    }

    @Test
    void escribirDejaElLibroEnLaSalida() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        LibroDeInforme.escribir(bytes, "Informe", libro -> {
            libro.encabezados("A");
            libro.fila("uno");
        });

        try (XSSFWorkbook wb = abrir(bytes)) {
            assertEquals("uno", celdaCon(wb.getSheetAt(0), 0, "uno").getStringCellValue());
        }
    }

    @Test
    void siElLlenadorLanzaAMitadLaSalidaQuedaVaciaYLaExcepcionSePropaga() {
        SalidaContada salida = new SalidaContada();
        IllegalStateException causa = new IllegalStateException("se cayó la base");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> LibroDeInforme.escribir(salida, "Informe", libro -> {
                    libro.encabezados("A");
                    for (int i = 0; i < 150; i++) {
                        libro.fila("x" + i);
                    }
                    throw causa;
                }));

        assertSame(causa, e);
        assertEquals(0, salida.bytes);
    }

    @Test
    void siElLlenadorLanzaUnaIOExceptionLaSalidaQuedaVaciaYSePropaga() {
        SalidaContada salida = new SalidaContada();

        IOException e = assertThrows(IOException.class,
                () -> LibroDeInforme.escribir(salida, "Informe", libro -> {
                    libro.fila("x");
                    throw new IOException("disco");
                }));

        assertEquals("disco", e.getMessage());
        assertEquals(0, salida.bytes);
    }

    @Test
    void descartarYCerrarNoEscribeNada() throws Exception {
        SalidaContada salida = new SalidaContada();

        try (LibroDeInforme libro = new LibroDeInforme(salida, "Informe")) {
            libro.encabezados("A");
            libro.descartar();
        }

        assertEquals(0, salida.bytes);
    }

    @Test
    void cerrarDosVecesNoEscribeDosVeces() throws Exception {
        SalidaContada salida = new SalidaContada();
        LibroDeInforme libro = new LibroDeInforme(salida, "Informe");
        libro.encabezados("A");

        libro.close();
        int tras = salida.bytes;
        int escrituras = salida.escrituras;
        libro.close();

        assertTrue(tras > 0);
        assertEquals(tras, salida.bytes);
        assertEquals(escrituras, salida.escrituras);
    }

    // -------------------------------------------------------------------- tipos de celda y formatos

    @Test
    void unBigDecimalConFormatoTextoSaleSinNotacionCientifica() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (LibroDeInforme libro = new LibroDeInforme(bytes, "Informe")) {
            libro.encabezados("N");
            libro.fila(LibroDeInforme.celda(new BigDecimal("1E+10"), Formato.TEXTO));
            libro.fila(LibroDeInforme.celda(new BigDecimal("0.0000001"), Formato.TEXTO));
        }

        try (XSSFWorkbook wb = abrir(bytes)) {
            Sheet hoja = wb.getSheetAt(0);
            assertEquals(CellType.STRING, celdaCon(hoja, 0, "10000000000").getCellType());
            celdaCon(hoja, 0, "0.0000001");
        }
    }

    @Test
    void unBooleanSaleSiONoEnCualquierFormato() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (LibroDeInforme libro = new LibroDeInforme(bytes, "Informe")) {
            libro.encabezados("A", "B", "C", "D");
            libro.fila(true, false,
                    LibroDeInforme.celda(true, Formato.PESOS), LibroDeInforme.celda(false, Formato.UNIDADES));
        }

        try (XSSFWorkbook wb = abrir(bytes)) {
            Sheet hoja = wb.getSheetAt(0);
            Row datos = hoja.getRow(celdaCon(hoja, 0, "A").getRowIndex() + 1);
            assertEquals("Sí", datos.getCell(0).getStringCellValue());
            assertEquals("No", datos.getCell(1).getStringCellValue());
            assertEquals("Sí", datos.getCell(2).getStringCellValue());
            assertEquals("No", datos.getCell(3).getStringCellValue());
        }
    }

    @Test
    void unaFechaConHoraConFormatoFechaEsUnaFechaSinHora() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (LibroDeInforme libro = new LibroDeInforme(bytes, "Informe")) {
            libro.encabezados("F", "G");
            libro.fila(LibroDeInforme.celda(LocalDateTime.of(2026, 11, 26, 15, 30), Formato.FECHA),
                    LocalDateTime.of(2026, 11, 26, 15, 30));
        }

        try (XSSFWorkbook wb = abrir(bytes)) {
            Sheet hoja = wb.getSheetAt(0);
            Row datos = hoja.getRow(celdaCon(hoja, 0, "F").getRowIndex() + 1);
            Cell soloFecha = datos.getCell(0);
            assertEquals(CellType.NUMERIC, soloFecha.getCellType());
            assertTrue(DateUtil.isCellDateFormatted(soloFecha));
            assertEquals("yyyy-mm-dd", soloFecha.getCellStyle().getDataFormatString());
            assertEquals(LocalDateTime.of(2026, 11, 26, 0, 0), soloFecha.getLocalDateTimeCellValue());
            // Sin formato explícito conserva la hora.
            Cell conHora = datos.getCell(1);
            assertTrue(DateUtil.isCellDateFormatted(conHora));
            assertEquals(LocalDateTime.of(2026, 11, 26, 15, 30), conHora.getLocalDateTimeCellValue());
        }
    }

    @Test
    void losFiltrosYTotalesNoSePidenDespuesDeLaTabla() throws Exception {
        // Van arriba: pedirlos después de la tabla dejaría el libro desordenado.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (LibroDeInforme libro = new LibroDeInforme(bytes, "Informe")) {
            libro.encabezados("A");
            libro.fila("x");
            assertThrows(IllegalStateException.class, () -> libro.filtro("F", "v"));
            assertThrows(IllegalStateException.class, () -> libro.total("T", BigDecimal.ONE, Formato.UNIDADES));
            assertThrows(IllegalStateException.class, () -> libro.encabezados("B"));
        }
    }
}

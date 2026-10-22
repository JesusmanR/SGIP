package co.com.bspstore.sgip.logica;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El lector de listas de precios (RF-24).
 *
 * Lo que se prueba aquí es lo que decide si una lista entra bien o entra
 * torcida, y casi todo son cosas que un archivo real trae y uno de
 * ejemplo no: el logotipo del proveedor encima de la tabla, el indicador
 * de orden de bytes que pega Excel, una descripción con coma dentro y
 * una celda de costo con una letra O donde iba un cero.
 */
class LectorHojaDeCalculoTest {

    private final LectorHojaDeCalculo lector = new LectorHojaDeCalculo();

    private List<FilaLeida> leerCsv(String contenido, int tope) throws IOException {
        return lector.leer(new ByteArrayInputStream(contenido.getBytes(StandardCharsets.UTF_8)),
                "lista.csv", tope);
    }

    private List<FilaLeida> leerCsv(String contenido) throws IOException {
        return leerCsv(contenido, 20000);
    }


    // ------------------------------------------------------------ formatos

    @Test
    void reconoceLosDosFormatosDelRequisito() {
        assertTrue(lector.entiende("lista.xlsx"));
        assertTrue(lector.entiende("LISTA.CSV"));
        assertFalse(lector.entiende("lista.xls"));
        assertFalse(lector.entiende("lista.pdf"));
        assertFalse(lector.entiende(null));
    }

    @Test
    void unFormatoQueNoSeAdmiteDiceCual() {
        ArchivoInvalidoException e = assertThrows(ArchivoInvalidoException.class,
                () -> lector.leer(new ByteArrayInputStream(new byte[0]), "precios.pdf", 100));
        assertTrue(e.getMessage().contains("precios.pdf"), e.getMessage());
    }


    // ------------------------------------------------------------ encabezado

    @Test
    void leeUnCsvCorriente() throws IOException {
        List<FilaLeida> filas = leerCsv("""
                codigo,descripcion,costo
                PC-001,Portátil Lenovo,1850000
                CEL-002,Celular Samsung,620000
                """);

        assertEquals(2, filas.size());
        assertEquals("PC-001", filas.get(0).referencia());
        assertEquals("Portátil Lenovo", filas.get(0).descripcion());
        assertEquals("1850000", filas.get(0).costoCrudo());
    }

    @Test
    void encuentraElEncabezadoDebajoDelMembrete() throws IOException {
        // Lo que manda un proveedor de verdad: dos líneas de texto suyo
        // antes de la tabla.
        List<FilaLeida> filas = leerCsv("""
                DISTRIBUIDORA LA ESPERANZA S.A.S.
                Lista de precios vigente desde octubre
                referencia,producto,precio unitario
                PC-001,Portátil,1850000
                """);

        assertEquals(1, filas.size());
        assertEquals("PC-001", filas.get(0).referencia());
        // La fila que se informa es la que la persona ve en su hoja.
        assertEquals(4, filas.get(0).numeroFila());
    }

    @Test
    void aceptaLosNombresDeColumnaConTildeYMayuscula() throws IOException {
        List<FilaLeida> filas = leerCsv("""
                CÓDIGO,Descripción,COSTO
                PC-001,Portátil,1850000
                """);
        assertEquals(1, filas.size());
        assertEquals("1850000", filas.get(0).costoCrudo());
    }

    @Test
    void sinColumnaDeCostoSeDetieneYLoDice() {
        ArchivoInvalidoException e = assertThrows(ArchivoInvalidoException.class,
                () -> leerCsv("codigo,descripcion\nPC-001,Portátil\n"));
        assertTrue(e.getMessage().contains("costo"), e.getMessage());
    }

    @Test
    void unArchivoSinEncabezadoNoSeLeeAMedias() {
        // Diez líneas de datos sin encabezado: la primera NO se toma como
        // encabezado y el resto como filas, que sería leer la columna
        // equivocada en silencio.
        String sinEncabezado = "A,1\nB,2\nC,3\nD,4\nE,5\nF,6\nG,7\nH,8\nI,9\nJ,10\nK,11\n";
        assertThrows(ArchivoInvalidoException.class, () -> leerCsv(sinEncabezado));
    }

    @Test
    void elIndicadorDeOrdenDeBytesNoRompeElEncabezado() throws IOException {
        // Excel lo escribe al guardar como CSV y se pega al primer
        // encabezado, que entonces no coincide con ningún nombre.
        List<FilaLeida> filas = leerCsv("﻿codigo,costo\nPC-001,1850000\n");
        assertEquals(1, filas.size());
    }


    // ------------------------------------------------------------ contenido

    @Test
    void unaDescripcionConComaNoCorreLasColumnas() throws IOException {
        List<FilaLeida> filas = leerCsv("""
                codigo,descripcion,costo
                TOR-01,"Tornillo 1/4"", galvanizado",2500
                """);

        assertEquals(1, filas.size());
        assertEquals("Tornillo 1/4\", galvanizado", filas.get(0).descripcion());
        // Lo que importa: el costo no se leyó de la columna corrida.
        assertEquals("2500", filas.get(0).costoCrudo());
    }

    @Test
    void unArchivoConPuntoYComaNoParteElCostoEnDos() throws IOException {
        // Un Excel en español guarda el CSV con punto y coma, porque la
        // coma es el separador decimal. Partir por los dos caracteres a
        // la vez rompía «1.850,00» en dos celdas y corría el resto, de
        // modo que el costo se leía de donde no era.
        List<FilaLeida> filas = leerCsv("""
                codigo;descripcion;costo
                PC-001;Portátil Lenovo;1.850,00
                """);

        assertEquals(1, filas.size());
        assertEquals("PC-001", filas.get(0).referencia());
        assertEquals("Portátil Lenovo", filas.get(0).descripcion());
        assertEquals("1.850,00", filas.get(0).costoCrudo());
    }

    @Test
    void yUnoConComasSigueLeyendoseComoSiempre() throws IOException {
        // El contrario del anterior: que la detección no se pase de lista
        // y empiece a ver puntos y comas donde no los hay.
        List<FilaLeida> filas = leerCsv("""
                codigo,descripcion,costo
                PC-001,Portátil Lenovo,1850000
                """);
        assertEquals("1850000", filas.get(0).costoCrudo());
    }

    @Test
    void elCostoLlegaSinInterpretarParaQuePuedaExplicarse() throws IOException {
        // Una O donde iba un cero: lo que produce una hoja retocada a
        // mano. El RF-26 pide informar el rechazo «con su causa», y la
        // causa es el texto que había.
        List<FilaLeida> filas = leerCsv("codigo,costo\nPC-001,1.2OO\n");
        assertEquals("1.2OO", filas.get(0).costoCrudo());
    }

    @Test
    void lasFilasDelTodoVaciasNoSonRechazos() throws IOException {
        List<FilaLeida> filas = leerCsv("""
                codigo,costo
                PC-001,1850000
                ,
                CEL-002,620000
                """);
        assertEquals(2, filas.size());
    }

    @Test
    void unaDescripcionAusenteEsNulaYNoCadenaVacia() throws IOException {
        List<FilaLeida> filas = leerCsv("codigo,costo\nPC-001,1850000\n");
        assertNull(filas.get(0).descripcion());
    }


    // ------------------------------------------------------------ límites

    @Test
    void elExcesoDeFilasSeRechazaEnVezDeRecortarse() {
        StringBuilder csv = new StringBuilder("codigo,costo\n");
        for (int i = 0; i < 6; i++) {
            csv.append("REF-").append(i).append(",1000\n");
        }
        ArchivoInvalidoException e = assertThrows(ArchivoInvalidoException.class,
                () -> leerCsv(csv.toString(), 5));
        // Recortar en silencio dejaría al proveedor creyendo que su lista
        // entró entera, que es peor que no aceptarla.
        assertTrue(e.getMessage().contains("5"), e.getMessage());
    }


    // ------------------------------------------------------------ .xlsx

    @Test
    void leeUnLibroDeVerdad() throws IOException {
        byte[] libro = libroDePrueba();
        List<FilaLeida> filas = lector.leer(new ByteArrayInputStream(libro), "lista.xlsx", 20000);

        assertEquals(2, filas.size());
        assertEquals("PC-001", filas.get(0).referencia());
        assertEquals("1850000", filas.get(0).costoCrudo());
        assertEquals("CEL-002", filas.get(1).referencia());
        assertEquals(3, filas.get(1).numeroFila());
    }

    @Test
    void unXlsxQueNoLoEsSeRechazaConUnMensajeUtil() {
        ArchivoInvalidoException e = assertThrows(ArchivoInvalidoException.class,
                () -> lector.leer(new ByteArrayInputStream("esto no es un libro".getBytes(
                        StandardCharsets.UTF_8)), "lista.xlsx", 100));
        assertTrue(e.getMessage().contains("xlsx"), e.getMessage());
    }

    private static byte[] libroDePrueba() throws IOException {
        try (XSSFWorkbook libro = new XSSFWorkbook();
             ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
            Sheet hoja = libro.createSheet("Precios");
            Row encabezado = hoja.createRow(0);
            encabezado.createCell(0).setCellValue("codigo");
            encabezado.createCell(1).setCellValue("descripcion");
            encabezado.createCell(2).setCellValue("costo");

            Row una = hoja.createRow(1);
            una.createCell(0).setCellValue("PC-001");
            una.createCell(1).setCellValue("Portátil Lenovo");
            una.createCell(2).setCellValue(1850000);

            Row otra = hoja.createRow(2);
            otra.createCell(0).setCellValue("CEL-002");
            otra.createCell(1).setCellValue("Celular Samsung");
            otra.createCell(2).setCellValue(620000);

            libro.write(salida);
            return salida.toByteArray();
        }
    }
}

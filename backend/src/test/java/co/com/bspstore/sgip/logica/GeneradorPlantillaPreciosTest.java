package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.FilaDeCarga;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioImportacion;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La plantilla de precios de un proveedor: un .xlsx con sus productos activos y su
 * costo actual, que el administrador llena y vuelve a subir.
 *
 * Lo central es la ida y vuelta: lo que sale de aquí entra por el lector real y por el
 * juicio real de {@link ServicioImportacion} sin un solo rechazo y sin cambiar un costo.
 * Se prueba con el lector y el servicio de verdad; solo el repositorio es simulado.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GeneradorPlantillaPreciosTest {

    private static final int EMPRESA = 1;
    private static final int USUARIO = 7;
    private static final int PROVEEDOR = 3;
    private static final long CARGA = 55L;
    private static final Clock RELOJ = Clock.fixed(
            LocalDate.of(2026, 11, 7).atTime(12, 0).atZone(ZoneId.of("America/Bogota")).toInstant(),
            ZoneId.of("UTC"));

    @Mock RepositorioImportacion importacion;
    @Captor ArgumentCaptor<List<FilaDeCarga>> detalles;

    private GeneradorPlantillaPrecios generador;
    private final LectorHojaDeCalculo lector = new LectorHojaDeCalculo();

    @BeforeEach
    void preparar() {
        generador = new GeneradorPlantillaPrecios(importacion);
        when(importacion.proveedorParaPlantilla(EMPRESA, PROVEEDOR))
                .thenReturn(Optional.of(new ProveedorDePlantilla("Ferretería Ñandú S.A.S.", "900123456")));

        var u = new UsuarioAutenticado(USUARIO, "ana@tienda.co", "ADMINISTRADOR", EMPRESA);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private void productos(ProductoDePlantilla... filas) {
        doAnswer(llamada -> {
            Consumer<ProductoDePlantilla> alVer = llamada.getArgument(2);
            for (ProductoDePlantilla p : filas) {
                alVer.accept(p);
            }
            return null;
        }).when(importacion).recorrerProductosActivosDelProveedor(eq(EMPRESA), eq(PROVEEDOR), any());
    }

    private static ProductoDePlantilla p(String codigo, String nombre, String costo) {
        return new ProductoDePlantilla(codigo, nombre, costo == null ? null : new BigDecimal(costo));
    }

    private byte[] generar() throws IOException {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        generador.escribir(EMPRESA, PROVEEDOR, salida);
        return salida.toByteArray();
    }

    private Workbook abrir(byte[] xlsx) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(xlsx));
    }

    // ------------------------------------------------------------ el contenido

    @Test
    void hojaCeroEsPrecios() throws IOException {
        productos(p("PC-001", "Portátil", "1000.00"));

        try (Workbook libro = abrir(generar())) {
            assertEquals("Precios", libro.getSheetName(0));
            assertEquals("Instrucciones", libro.getSheetName(1));
            Row encabezado = libro.getSheetAt(0).getRow(0);
            assertEquals("Referencia", encabezado.getCell(0).getStringCellValue());
            assertEquals("Descripción", encabezado.getCell(1).getStringCellValue());
            assertEquals("Costo", encabezado.getCell(2).getStringCellValue());
            assertTrue(libro.getSheetAt(1).getLastRowNum() >= 3, "las instrucciones traen su texto");
        }
    }

    @Test
    void plantillaTraeLosProductosDelProveedorConSuCosto() throws IOException {
        productos(p("PC-001", "Portátil 14", "2100000.00"), p("MS-002", "Ratón", "35000.50"));

        try (Workbook libro = abrir(generar())) {
            Sheet hoja = libro.getSheetAt(0);
            assertEquals(2, hoja.getLastRowNum(), "el encabezado y dos productos");
            assertEquals("PC-001", hoja.getRow(1).getCell(0).getStringCellValue());
            assertEquals("Portátil 14", hoja.getRow(1).getCell(1).getStringCellValue());
            assertEquals(CellType.NUMERIC, hoja.getRow(1).getCell(2).getCellType());
            assertEquals(0, new BigDecimal("2100000.00")
                    .compareTo(BigDecimal.valueOf(hoja.getRow(1).getCell(2).getNumericCellValue())));
            assertEquals("MS-002", hoja.getRow(2).getCell(0).getStringCellValue());
            assertEquals(35000.5, hoja.getRow(2).getCell(2).getNumericCellValue(), 0.0001);
        }
    }

    @Test
    void costoNuloVaVacioYAlSubirloSinLlenarSeRechaza() throws IOException {
        productos(p("PC-001", "Con costo", "1000.00"), p("PC-002", "Sin costo", null),
                p("PC-003", "Costo cero", "0.00"));

        byte[] xlsx = generar();
        try (Workbook libro = abrir(xlsx)) {
            Sheet hoja = libro.getSheetAt(0);
            for (int fila : new int[] {2, 3}) {
                Cell costo = hoja.getRow(fila).getCell(2);
                assertTrue(costo == null || costo.getCellType() == CellType.BLANK,
                        "el costo nulo o de cero queda en celda vacía, y la fila " + fila + " trae "
                                + (costo == null ? null : costo.getCellType()));
            }
        }

        // Subida tal cual, la fila que nadie llenó se rechaza como costo no numérico.
        catalogoDe(p("PC-001", "Con costo", "1000.00"), p("PC-002", "Sin costo", "500.00"),
                p("PC-003", "Costo cero", "700.00"));
        List<FilaDeCarga> juzgadas = subir(xlsx);
        assertTrue(juzgadas.get(0).aceptado());
        assertEquals("COSTO_NO_NUMERICO", juzgadas.get(1).motivoCodigo());
        assertEquals("COSTO_NO_NUMERICO", juzgadas.get(2).motivoCodigo());
    }

    @Test
    void proveedorSinProductosDaSoloEncabezados() throws IOException {
        productos();

        byte[] xlsx = generar();
        try (Workbook libro = abrir(xlsx)) {
            Sheet hoja = libro.getSheetAt(0);
            assertEquals(0, hoja.getLastRowNum(), "solo el encabezado");
            assertEquals("Costo", hoja.getRow(0).getCell(2).getStringCellValue());
            assertEquals(2, libro.getNumberOfSheets());
        }
    }

    @Test
    void referenciaQueEmpiezaConIgualSeReimportaIgual() throws IOException {
        // Las que una hoja de cálculo tomaría por una fórmula (o por un signo) viajan como texto.
        List<String> peligrosas = List.of("=1+1", "+573001112233", "-A1", "@SUMA(1;1)",
                "=CMD|' /C calc'!A0");
        ProductoDePlantilla[] filas = new ProductoDePlantilla[peligrosas.size()];
        for (int i = 0; i < filas.length; i++) {
            filas[i] = p(peligrosas.get(i), peligrosas.get(i), "1000.00");
        }
        productos(filas);

        byte[] xlsx = generar();
        try (Workbook libro = abrir(xlsx)) {
            Sheet hoja = libro.getSheetAt(0);
            for (int i = 0; i < peligrosas.size(); i++) {
                for (int columna : new int[] {0, 1}) {
                    Cell celda = hoja.getRow(i + 1).getCell(columna);
                    assertEquals(CellType.STRING, celda.getCellType(), "texto, no fórmula: " + peligrosas.get(i));
                    assertEquals(peligrosas.get(i), celda.getStringCellValue(),
                            "sin un apóstrofo escrito delante");
                    assertTrue(celda.getCellStyle().getQuotePrefixed(), "con el prefijo de comilla");
                }
            }
        }

        List<FilaLeida> leidas = lector.leer(new ByteArrayInputStream(xlsx), "plantilla.xlsx", 20_000);
        for (int i = 0; i < peligrosas.size(); i++) {
            assertEquals(peligrosas.get(i), leidas.get(i).referencia(), "se relee igual");
        }
    }

    @Test
    void idaYVueltaSinCambiosNoRechazaNiCambiaCostos() throws IOException {
        // Todos los costos del fixture son positivos; hay centavos, miles, cifras grandes y pequeñas.
        ProductoDePlantilla[] catalogo = {
                p("PC-001", "Portátil", "2100000.00"),
                p("MS-002", "Ratón óptico", "35000.50"),
                p("TC-003", "Teclado \"español\", negro", "1850.00"),
                p("CB-004", "Cable 1,5 m", "1.50"),
                p("CB-005", "Cable 0,5 m", "0.50"),
                p("SV-006", "Servidor", "98765432109.87"),
                p("TN-007", "Tornillo", "120.00"),
                p("TN-008", "Tuerca", "1234.00"),
                p("TN-009", "Arandela", "1.23"),
        };
        productos(catalogo);
        catalogoDe(catalogo);

        List<FilaDeCarga> juzgadas = subir(generar());

        assertEquals(catalogo.length, juzgadas.size());
        for (int i = 0; i < catalogo.length; i++) {
            FilaDeCarga f = juzgadas.get(i);
            assertTrue(f.aceptado(), catalogo[i].codigo() + " se rechazó: " + f.motivoRechazo());
            assertEquals(0, catalogo[i].costo().compareTo(f.costo()),
                    catalogo[i].codigo() + ": el costo cambió de " + catalogo[i].costo() + " a " + f.costo());
            assertEquals(0, catalogo[i].costo().compareTo(f.costoAnterior()));
        }
    }

    // ------------------------------------------------------------ las guardas

    @Test
    void proveedorAjenoEs404() {
        when(importacion.proveedorParaPlantilla(EMPRESA, 77)).thenReturn(Optional.empty());
        ByteArrayOutputStream salida = new ByteArrayOutputStream();

        assertThrows(RecursoNoEncontradoException.class, () -> generador.nombreDeArchivo(EMPRESA, 77));
        assertThrows(RecursoNoEncontradoException.class, () -> generador.escribir(EMPRESA, 77, salida));

        assertEquals(0, salida.size(), "no se escribió ni un byte");
        verify(importacion, never()).recorrerProductosActivosDelProveedor(anyInt(), anyInt(), any());
    }

    @Test
    void proveedorReservadoEs404() {
        when(importacion.proveedorParaPlantilla(EMPRESA, 8))
                .thenReturn(Optional.of(new ProveedorDePlantilla("Sin proveedor", "SIN-PROVEEDOR")));
        ByteArrayOutputStream salida = new ByteArrayOutputStream();

        assertThrows(RecursoNoEncontradoException.class, () -> generador.nombreDeArchivo(EMPRESA, 8));
        assertThrows(RecursoNoEncontradoException.class, () -> generador.escribir(EMPRESA, 8, salida));

        assertEquals(0, salida.size());
        verify(importacion, never()).recorrerProductosActivosDelProveedor(anyInt(), anyInt(), any());
    }

    @Test
    void nombreDeArchivoConTildesYEspacios() {
        assertEquals("plantilla-precios-ferreteria-nandu-s-a-s.xlsx",
                generador.nombreDeArchivo(EMPRESA, PROVEEDOR));

        when(importacion.proveedorParaPlantilla(EMPRESA, 4)).thenReturn(Optional.of(
                new ProveedorDePlantilla("  Distribuciones  Pérez & Cía.  \"Ltda\"/Norte ", "800")));
        assertEquals("plantilla-precios-distribuciones-perez-cia-ltda-norte.xlsx",
                generador.nombreDeArchivo(EMPRESA, 4));

        when(importacion.proveedorParaPlantilla(EMPRESA, 5)).thenReturn(Optional.of(
                new ProveedorDePlantilla("§§ ¿? §§", "801")));
        assertEquals("plantilla-precios-proveedor.xlsx", generador.nombreDeArchivo(EMPRESA, 5),
                "un nombre sin letras ni dígitos no deja el nombre del archivo vacío");
    }

    @Test
    void elNombreDelArchivoNoLlevaNadaQueRompaLaCabecera() {
        when(importacion.proveedorParaPlantilla(EMPRESA, 6)).thenReturn(Optional.of(
                new ProveedorDePlantilla("Evil\r\nSet-Cookie: x=1\";\\ ñ", "802")));

        String nombre = generador.nombreDeArchivo(EMPRESA, 6);

        assertTrue(nombre.matches("[a-z0-9.-]+"), nombre);
        assertFalse(nombre.contains(".."));
    }

    // ------------------------------------------------------------ apoyo de la ida y vuelta

    /** El catálogo de la empresa tal como lo ve el juicio: todos del proveedor de la plantilla. */
    private void catalogoDe(ProductoDePlantilla... productos) {
        Map<String, ProductoDelCatalogo> indice = new HashMap<>();
        int id = 100;
        for (ProductoDePlantilla pr : productos) {
            indice.put(RepositorioImportacion.normalizar(pr.codigo()),
                    new ProductoDelCatalogo(id++, pr.codigo(), pr.codigo(), pr.costo(), PROVEEDOR));
        }
        when(importacion.catalogoPorReferencia(EMPRESA)).thenReturn(indice);
    }

    /** Sube el .xlsx por el servicio real y devuelve lo que juzgó. */
    private List<FilaDeCarga> subir(byte[] xlsx) throws IOException {
        ServicioImportacion servicio = new ServicioImportacion(importacion, List.of(lector), RELOJ);
        when(importacion.proveedorPropio(EMPRESA, PROVEEDOR)).thenReturn(true);
        when(importacion.insertarCarga(anyInt(), anyInt(), anyInt(), anyString(),
                anyInt(), anyInt(), anyInt())).thenReturn(CARGA);
        when(importacion.buscarCarga(anyInt(), anyLong())).thenReturn(Optional.of(new CabeceraCarga(
                CARGA, PROVEEDOR, "Proveedor", "plantilla.xlsx", "VALIDADA", null, null, 0, 0, 0)));
        when(importacion.detallesDe(anyInt(), anyLong(), anyInt())).thenReturn(List.of());

        servicio.cargar(PROVEEDOR, "plantilla.xlsx", xlsx.length, new ByteArrayInputStream(xlsx));

        verify(importacion).insertarDetalles(anyInt(), anyLong(), detalles.capture());
        assertNotNull(detalles.getValue());
        return detalles.getValue();
    }
}

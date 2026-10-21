package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.FilaDeCarga;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioImportacion;
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
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La validación de una carga de lista de precios (RF-26).
 *
 * La prueba central es, literalmente, el criterio de aceptación del
 * requisito: cien filas con doce defectuosas aplican ochenta y ocho y
 * listan las doce con su causa. Está escrita desde esa frase y no desde
 * el código, que es la lección que dejó el periodo de prueba del RF-04.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServicioImportacionTest {

    private static final int EMPRESA = 1;
    private static final int USUARIO = 7;
    private static final int PROVEEDOR = 3;
    private static final long CARGA = 55L;

    @Mock RepositorioImportacion importacion;
    @Captor ArgumentCaptor<List<FilaDeCarga>> detalles;

    private ServicioImportacion servicio;
    private final LectorFalso lector = new LectorFalso();

    @BeforeEach
    void preparar() {
        servicio = new ServicioImportacion(importacion, List.of(lector));
        var u = new UsuarioAutenticado(USUARIO, "ana@tienda.co", "ADMINISTRADOR", EMPRESA);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);

        when(importacion.proveedorPropio(EMPRESA, PROVEEDOR)).thenReturn(true);
        when(importacion.insertarCarga(anyInt(), anyInt(), anyInt(), anyString(),
                anyInt(), anyInt(), anyInt())).thenReturn(CARGA);
        when(importacion.buscarCarga(anyInt(), anyLong()))
                .thenReturn(Optional.of(new CabeceraCarga(CARGA, PROVEEDOR, "Proveedor",
                        "lista.csv", "VALIDADA", null, null, 0, 0, 0)));
        when(importacion.detallesDe(anyInt(), anyLong(), anyInt())).thenReturn(List.of());
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }


    /** Un lector que devuelve lo que la prueba le ponga. */
    private static final class LectorFalso implements LectorListaPrecios {
        private List<FilaLeida> filas = List.of();

        @Override public boolean entiende(String nombreArchivo) {
            return nombreArchivo != null && nombreArchivo.endsWith(".csv");
        }

        @Override public List<FilaLeida> leer(InputStream entrada, String nombre, int tope) {
            return filas;
        }
    }

    private void catalogo(Map<String, ProductoDelCatalogo> indice) {
        when(importacion.catalogoPorReferencia(EMPRESA)).thenReturn(indice);
    }

    private List<FilaDeCarga> cargar() throws IOException {
        servicio.cargar(PROVEEDOR, "lista.csv", 1024,
                new ByteArrayInputStream(new byte[0]));
        verify(importacion).insertarDetalles(anyInt(), anyLong(), detalles.capture());
        return detalles.getValue();
    }


    // ------------------------------------------------ el criterio del RF-26

    @Test
    void cienFilasConDoceDefectuosasAplicanOchentaYOcho() throws IOException {
        Map<String, ProductoDelCatalogo> indice = new HashMap<>();
        List<FilaLeida> archivo = new ArrayList<>();

        // 88 referencias buenas, que existen y traen un costo correcto.
        for (int i = 1; i <= 88; i++) {
            String ref = "REF-" + i;
            indice.put(ref, new ProductoDelCatalogo(1000 + i, ref, ref,
                    new BigDecimal("1000")));
            archivo.add(new FilaLeida(i + 1, ref, "Producto " + i, "1500"));
        }
        // Y doce defectuosas, tres de cada causa que el requisito nombra.
        int fila = 90;
        for (int i = 1; i <= 3; i++) {                      // costo no numérico
            String ref = "MAL-N" + i;
            indice.put(ref, new ProductoDelCatalogo(2000 + i, ref, ref, new BigDecimal("1000")));
            archivo.add(new FilaLeida(fila++, ref, null, "consultar"));
        }
        for (int i = 1; i <= 3; i++) {                      // costo <= 0
            String ref = "MAL-C" + i;
            indice.put(ref, new ProductoDelCatalogo(3000 + i, ref, ref, new BigDecimal("1000")));
            archivo.add(new FilaLeida(fila++, ref, null, i == 1 ? "0" : "-250"));
        }
        for (int i = 1; i <= 3; i++) {                      // referencia inexistente
            archivo.add(new FilaLeida(fila++, "NO-EXISTE-" + i, null, "1500"));
        }
        for (int i = 1; i <= 3; i++) {                      // repetida en el archivo
            archivo.add(new FilaLeida(fila++, "REF-" + i, "Producto " + i, "1700"));
        }

        catalogo(indice);
        lector.filas = archivo;
        List<FilaDeCarga> juzgadas = cargar();

        assertEquals(100, juzgadas.size(), "se leyeron cien filas");
        assertEquals(88, juzgadas.stream().filter(FilaDeCarga::aceptado).count());
        assertEquals(12, juzgadas.stream().filter(f -> !f.aceptado()).count());

        // «Ningún registro defectuoso se aplica»: ninguna rechazada lleva
        // costo, de modo que no hay nada que aplicar de ellas.
        for (FilaDeCarga f : juzgadas) {
            if (!f.aceptado()) {
                assertNull(f.costo(), "una fila rechazada no lleva costo");
                assertNotNull(f.motivoRechazo(), "una fila rechazada lleva su causa");
                assertFalse(f.motivoRechazo().isBlank());
            }
        }
        // Y la cabecera registra las mismas cifras (RF-27).
        verify(importacion).insertarCarga(EMPRESA, PROVEEDOR, USUARIO, "lista.csv",
                100, 88, 12);
    }

    @Test
    void cadaCausaSeNombraPorSuNombre() throws IOException {
        catalogo(Map.of("PC-001",
                new ProductoDelCatalogo(1, "PC-001", "PC-001", new BigDecimal("1000"))));
        lector.filas = List.of(
                new FilaLeida(2, "PC-001", null, "consultar"),
                new FilaLeida(3, "FANTASMA", null, "1500"),
                new FilaLeida(4, "", null, "1500"));

        List<FilaDeCarga> juzgadas = cargar();

        assertTrue(juzgadas.get(0).motivoRechazo().contains("consultar"),
                "el motivo repite el texto que había: " + juzgadas.get(0).motivoRechazo());
        assertTrue(juzgadas.get(1).motivoRechazo().contains("FANTASMA"));
        assertTrue(juzgadas.get(2).motivoRechazo().toLowerCase().contains("referencia"));
    }

    @Test
    void laFilaRepetidaSenalaDondeEstabaLaPrimera() throws IOException {
        catalogo(Map.of("PC-001",
                new ProductoDelCatalogo(1, "PC-001", "PC-001", new BigDecimal("1000"))));
        lector.filas = List.of(
                new FilaLeida(2, "PC-001", null, "1500"),
                new FilaLeida(47, "PC-001", null, "1600"));

        List<FilaDeCarga> juzgadas = cargar();

        assertTrue(juzgadas.get(0).aceptado(), "la primera entra");
        assertFalse(juzgadas.get(1).aceptado());
        // Sin el número de fila, corregir un archivo de veinte mil líneas
        // es buscar a ojo. Por eso la V29 añadió la columna.
        assertTrue(juzgadas.get(1).motivoRechazo().contains("2"),
                juzgadas.get(1).motivoRechazo());
        assertEquals(Integer.valueOf(47), juzgadas.get(1).numeroFila());
    }

    @Test
    void unaFilaAceptadaLlevaElCostoQueRegiaParaPoderCompararse() throws IOException {
        catalogo(Map.of("PC-001",
                new ProductoDelCatalogo(1, "PC-001", "PC-001", new BigDecimal("1000"))));
        lector.filas = List.of(new FilaLeida(2, "PC-001", "Portátil", "1500"));

        FilaDeCarga f = cargar().get(0);

        assertTrue(f.aceptado());
        assertEquals(0, f.costo().compareTo(new BigDecimal("1500")));
        // Un costo que se multiplica por diez salta a la vista al lado
        // del que regía, y no al lado de nada.
        assertEquals(0, f.costoAnterior().compareTo(new BigDecimal("1000")));
        assertEquals(Integer.valueOf(1), f.productoId());
    }

    @Test
    void elSkuTambienSirveParaEncontrarElProducto() throws IOException {
        // El proveedor nombra sus productos como quiere.
        catalogo(Map.of("SKU-9",
                new ProductoDelCatalogo(1, "PC-001", "SKU-9", new BigDecimal("1000"))));
        lector.filas = List.of(new FilaLeida(2, " sku-9 ", null, "1500"));

        assertTrue(cargar().get(0).aceptado(), "la referencia se coteja sin espacios ni caja");
    }


    // ------------------------------------------------ los topes del RF-24

    @Test
    void unArchivoDemasiadoGrandeSeRechazaAntesDeAbrirlo() {
        catalogo(Map.of());
        ArchivoInvalidoException e = assertThrows(ArchivoInvalidoException.class,
                () -> servicio.cargar(PROVEEDOR, "lista.csv",
                        ServicioImportacion.MAXIMO_BYTES + 1,
                        new ByteArrayInputStream(new byte[0])));
        assertTrue(e.getMessage().contains("10"), e.getMessage());
    }

    @Test
    void unFormatoSinLectorSeRechazaConSuCausa() {
        catalogo(Map.of());
        ArchivoInvalidoException e = assertThrows(ArchivoInvalidoException.class,
                () -> servicio.cargar(PROVEEDOR, "lista.pdf", 10,
                        new ByteArrayInputStream(new byte[0])));
        assertTrue(e.getMessage().contains("lista.pdf"), e.getMessage());
    }

    @Test
    void unProveedorDeOtraEmpresaNoPuedeCargarAqui() {
        when(importacion.proveedorPropio(EMPRESA, 999)).thenReturn(false);
        assertThrows(ReferenciaAjenaException.class,
                () -> servicio.cargar(999, "lista.csv", 10,
                        new ByteArrayInputStream(new byte[0])));
    }

    // ------------------------------------------------ aplicar (RF-27)

    private void cargaEn(String estado, int aceptados) {
        when(importacion.buscarCarga(EMPRESA, CARGA))
                .thenReturn(Optional.of(new CabeceraCarga(CARGA, PROVEEDOR, "Proveedor",
                        "lista.csv", estado, null, null, aceptados, aceptados, 0)));
    }

    @Test
    void aplicarMueveElCostoYDejaConstanciaDelAnterior() {
        cargaEn("VALIDADA", 5);
        when(importacion.aplicarCostos(EMPRESA, CARGA)).thenReturn(5);

        servicio.aplicar(CARGA);

        // El orden importa: cerrar lo que regía, retirar lo que ya se
        // hubiera abierto hoy, abrir lo nuevo y recién entonces escribir
        // el catálogo. Al revés, el costo anterior ya no se podría leer.
        var orden = org.mockito.Mockito.inOrder(importacion);
        orden.verify(importacion).cerrarCostoVigente(EMPRESA, CARGA);
        orden.verify(importacion).retirarCostoDeHoy(EMPRESA, CARGA);
        orden.verify(importacion).abrirCostoDeHoy(EMPRESA, CARGA);
        orden.verify(importacion).aplicarCostos(EMPRESA, CARGA);
        orden.verify(importacion).marcarAplicada(EMPRESA, CARGA);
    }

    @Test
    void unaCargaYaAplicadaNoSeAplicaDosVeces() {
        cargaEn("APLICADA", 5);

        ReglaInvalidaException e = assertThrows(ReglaInvalidaException.class,
                () -> servicio.aplicar(CARGA));

        assertTrue(e.getMessage().contains("APLICADA"), e.getMessage());
        verify(importacion, org.mockito.Mockito.never()).aplicarCostos(anyInt(), anyLong());
    }

    @Test
    void siElCatalogoCambioDesdeLaValidacionNoSeAplicaNada() {
        cargaEn("VALIDADA", 5);
        // Alguien desactivó una referencia entre validar y aplicar.
        when(importacion.aplicarCostos(EMPRESA, CARGA)).thenReturn(4);

        ReglaInvalidaException e = assertThrows(ReglaInvalidaException.class,
                () -> servicio.aplicar(CARGA));

        // «Si falla a mitad, ningún costo queda modificado»: la
        // excepción deshace la transacción, y la carga NO queda marcada
        // como aplicada, que es lo que permite reintentarla.
        assertTrue(e.getMessage().contains("5") && e.getMessage().contains("4"),
                e.getMessage());
        verify(importacion, org.mockito.Mockito.never()).marcarAplicada(anyInt(), anyLong());
    }

    @Test
    void unaCargaDeOtraEmpresaNoSeEncuentra() {
        when(importacion.buscarCarga(EMPRESA, 999L)).thenReturn(Optional.empty());
        assertTrue(servicio.aplicar(999L).isEmpty());
    }

    @Test
    void validarNoTocaNingunCosto() throws IOException {
        catalogo(Map.of("PC-001",
                new ProductoDelCatalogo(1, "PC-001", "PC-001", new BigDecimal("1000"))));
        lector.filas = List.of(new FilaLeida(2, "PC-001", null, "1500"));

        cargar();

        // Aplicar es un paso aparte que la persona da después de mirar
        // la vista previa. Esta prueba es lo que impide que alguien
        // «simplifique» juntando los dos.
        verify(importacion, org.mockito.Mockito.never())
                .costoActual(anyInt(), anyInt());
    }
}

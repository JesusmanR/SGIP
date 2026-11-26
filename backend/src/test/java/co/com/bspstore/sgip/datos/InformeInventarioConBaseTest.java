package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.InformeInventario;
import co.com.bspstore.sgip.api.dto.SolicitudPrecio;
import co.com.bspstore.sgip.api.dto.SolicitudProducto;
import co.com.bspstore.sgip.logica.ServicioProducto;
import co.com.bspstore.sgip.logica.informes.ServicioInformeInventario;
import co.com.bspstore.sgip.modelo.Categoria;
import co.com.bspstore.sgip.util.Hoy;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El inventario valorizado (RF-57) contra el esquema real: qué suma en bodega y qué va aparte, cómo se
 * marcan los inactivos y los que no tienen precio, el orden de la tabla, el tope de la página, el
 * aislamiento por empresa, cuántas consultas cuesta y que el Excel trae las mismas cifras.
 *
 * Los precios salen de {@code v_precios_producto}: un producto creado con {@code ServicioProducto.crear} lleva
 * una regla propia al 20 % (costo 1000, precio de contado 1200); uno guardado por el repositorio no tiene
 * regla. Crea sus empresas y las borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class InformeInventarioConBaseTest {

    @Autowired ServicioInformeInventario informe;
    @Autowired ServicioProducto productos;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired Clock reloj;
    @MockitoSpyBean JdbcClient jdbc;

    private final List<EmpresaDePrueba> empresasCreadas = new ArrayList<>();
    private EmpresaDePrueba empresa;
    private int siguiente;

    @BeforeEach
    void prepararDatosPropios() {
        empresa = nuevaEmpresa("Inventario");
        empresa.entrar();
    }

    @AfterEach
    void borrarLoQueCreo() {
        for (EmpresaDePrueba creada : empresasCreadas) {
            creada.borrar(jdbc);
        }
    }

    private EmpresaDePrueba nuevaEmpresa(String nombre) {
        EmpresaDePrueba creada = EmpresaDePrueba.crear(nombre, plataforma, jdbc, categorias, proveedores);
        empresasCreadas.add(creada);
        return creada;
    }

    /** Un producto con regla de precio al 20 %: precio de contado = costo × 1,2 (costos redondos). */
    private Integer conPrecio(EmpresaDePrueba de, String nombre, int costo, int existencia) {
        de.entrar();
        String codigo = "C%03d-%s".formatted(siguiente++, de.marca);
        Integer id = productos.crear(new SolicitudProducto(de.categoriaId, de.proveedorId, codigo, null, null,
                nombre, null, null, new BigDecimal(costo), existencia, 5,
                new SolicitudPrecio(new BigDecimal("20"), null), null)).getId();
        empresa.entrar();
        return id;
    }

    private Integer conPrecio(String nombre, int costo, int existencia) {
        return conPrecio(empresa, nombre, costo, existencia);
    }

    /** Un producto sin regla de precio, insertado directo: el alta por la API exige precio. */
    private Integer sinPrecio(EmpresaDePrueba de, Integer categoriaId, String nombre, int costo, int existencia) {
        String codigo = "S%03d-%s".formatted(siguiente++, de.marca);
        jdbc.sql("""
                INSERT INTO productos (empresa_id, categoria_id, proveedor_id, codigo, sku, nombre, costo, existencia)
                VALUES (:e, :c, :p, :codigo, :codigo, :nombre, :costo, :existencia)
                """)
                .param("e", de.empresaId).param("c", categoriaId).param("p", de.proveedorId)
                .param("codigo", codigo).param("nombre", nombre)
                .param("costo", costo).param("existencia", existencia).update();
        return jdbc.sql("SELECT producto_id FROM productos WHERE empresa_id = :e AND codigo = :codigo")
                .param("e", de.empresaId).param("codigo", codigo).query(Integer.class).single();
    }

    private Integer sinPrecio(String nombre, int costo, int existencia) {
        return sinPrecio(empresa, empresa.categoriaId, nombre, costo, existencia);
    }

    private void desactivar(Integer productoId) {
        jdbc.sql("UPDATE productos SET activo = 0 WHERE empresa_id = :e AND producto_id = :id")
                .param("e", empresa.empresaId).param("id", productoId).update();
    }

    private void fijarExistencia(Integer productoId, int existencia) {
        jdbc.sql("UPDATE productos SET existencia = :x WHERE empresa_id = :e AND producto_id = :id")
                .param("e", empresa.empresaId).param("id", productoId).param("x", existencia).update();
    }

    private InformeInventario todo() {
        return informe.pagina(null, null, 0, 200);
    }

    private static void igual(String esperado, BigDecimal real, String que) {
        assertTrue(new BigDecimal(esperado).compareTo(real) == 0, que + ": esperaba " + esperado + " y fue " + real);
    }

    // ------------------------------------------------------------------ los totales en bodega

    @Test
    void totalesSoloExistenciaPositiva() {
        conPrecio("Arroz", 1000, 10);
        conPrecio("Frijol", 500, 4);
        sinPrecio("Deuda", 1000, 0);
        Integer vendidoSinExistencia = conPrecio("Lenteja", 1000, 5);
        fijarExistencia(vendidoSinExistencia, -3);

        InformeInventario.Totales t = todo().totales();

        assertEquals(2, t.referencias(), "solo las de existencia mayor que cero");
        assertEquals(14, t.unidades());
        igual("12000", t.valorAlCosto(), "10 x 1000 + 4 x 500");
        igual("14400", t.valorAPrecio(), "10 x 1200 + 4 x 600");
        assertEquals(0, t.sinPrecio());
    }

    @Test
    void seccionNegativaAparte() {
        conPrecio("Arroz", 1000, 10);
        fijarExistencia(conPrecio("Lenteja", 1000, 5), -3);
        fijarExistencia(conPrecio("Garbanzo", 500, 5), -2);

        InformeInventario informeCompleto = todo();
        InformeInventario.Totales t = informeCompleto.totales();

        assertEquals(2, t.referenciasNegativas());
        assertEquals(5, t.unidadesBajoCero(), "3 + 2");
        igual("4000", t.valorPendienteAlCosto(), "3 x 1000 + 2 x 500");
        assertEquals(1, t.referencias(), "lo negativo no se mezcla con lo de bodega");
        igual("10000", t.valorAlCosto(), "lo negativo no resta de lo de bodega");
        assertEquals(3, informeCompleto.totalFilas(), "la tabla trae las tres: existencia distinta de cero");
        informeCompleto.filas().stream().filter(f -> f.existencia() < 0).forEach(f ->
                igual("0", f.valorAlCosto(), "una referencia en negativo valoriza en cero (ADR-0019)"));
    }

    @Test
    void inactivoConExistenciaCuentaYSeMarca() {
        conPrecio("Activo", 1000, 10);
        Integer inactivo = conPrecio("Retirado", 1000, 5);
        desactivar(inactivo);

        InformeInventario resultado = todo();

        assertEquals(2, resultado.totales().referencias(), "sigue siendo plata en bodega");
        assertEquals(15, resultado.totales().unidades());
        igual("15000", resultado.totales().valorAlCosto(), "que el producto esté inactivo no lo saca del valor");
        InformeInventario.Fila fila = resultado.filas().stream()
                .filter(f -> f.productoId().equals(inactivo)).findFirst().orElseThrow();
        assertFalse(fila.activo(), "lleva la marca de inactivo");
        assertNull(fila.precio(), "un inactivo no tiene precio");
        assertNull(fila.valorAPrecio());
        igual("5000", fila.valorAlCosto(), "valor del inactivo");
        igual("12000", resultado.totales().valorAPrecio(), "solo lo que tiene precio: el activo");
        assertEquals(1, resultado.totales().sinPrecio(), "el inactivo no entró en el valor a precio");
    }

    @Test
    void sinReglaDePrecioSumaAlCostoYCuentaSinPrecio() {
        Integer conRegla = conPrecio("Con regla", 1000, 2);
        Integer sinRegla = sinPrecio("Sin regla", 1000, 3);

        InformeInventario resultado = todo();

        assertEquals(2, resultado.totales().referencias());
        igual("5000", resultado.totales().valorAlCosto(), "suma al costo también la que no tiene precio");
        igual("2400", resultado.totales().valorAPrecio(), "a precio solo la que tiene precio: 2 x 1200");
        assertEquals(1, resultado.totales().sinPrecio(), "«1 sin precio»");
        InformeInventario.Fila fila = resultado.filas().stream()
                .filter(f -> f.productoId().equals(sinRegla)).findFirst().orElseThrow();
        assertNull(fila.precio(), "el precio queda vacío, no cero ni error");
        assertNull(fila.valorAPrecio());
        igual("3000", fila.valorAlCosto(), "valor al costo de la fila sin regla");
        InformeInventario.Fila otra = resultado.filas().stream()
                .filter(f -> f.productoId().equals(conRegla)).findFirst().orElseThrow();
        igual("1200", otra.precio(), "precio de contado");
        igual("2400", otra.valorAPrecio(), "2 x 1200");
    }

    @Test
    void categoriaVaciaDaCeros() {
        conPrecio("Arroz", 1000, 10);
        Integer vacia = categorias.save(new Categoria(empresa.empresaId, "Vacía", null)).getId();

        InformeInventario resultado = informe.pagina(vacia, null, 0, 50);

        InformeInventario.Totales t = resultado.totales();
        assertEquals(0, t.referencias());
        assertEquals(0, t.unidades());
        igual("0", t.valorAlCosto(), "valor al costo");
        igual("0", t.valorAPrecio(), "valor a precio");
        assertEquals(0, t.sinPrecio());
        assertEquals(0, t.referenciasNegativas());
        assertEquals(0, t.unidadesBajoCero());
        igual("0", t.valorPendienteAlCosto(), "valor pendiente");
        assertTrue(resultado.filas().isEmpty());
        assertEquals(0, resultado.totalFilas());
    }

    // ------------------------------------------------------------------------------ la tabla

    @Test
    void ordenPorValorAlCostoYDesempatePorId() {
        Integer pequeno = conPrecio("Pequeño", 1000, 1);     // 1000
        Integer primeroDelEmpate = conPrecio("Empate uno", 1000, 5);  // 5000
        Integer segundoDelEmpate = sinPrecio("Empate dos", 500, 10);  // 5000
        Integer grande = conPrecio("Grande", 1000, 20);     // 20000

        List<Integer> orden = todo().filas().stream().map(InformeInventario.Fila::productoId).toList();

        assertEquals(List.of(grande, primeroDelEmpate, segundoDelEmpate, pequeno), orden,
                "de mayor a menor valor al costo; a igual valor, el de menor id primero");
    }

    @Test
    void tamanoMaximo200() {
        for (int i = 0; i < 205; i++) {
            sinPrecio("Masivo " + i, 1000, 1);
        }

        InformeInventario resultado = informe.pagina(null, null, 0, 500);

        assertEquals(200, resultado.filas().size(), "con tamano=500 se usan 200");
        assertEquals(205, resultado.totalFilas());
        assertEquals(5, informe.pagina(null, null, 1, 500).filas().size(), "la segunda página trae el resto");
    }

    @Test
    void otraEmpresaNoAparece() {
        EmpresaDePrueba otra = nuevaEmpresa("Inventario ajeno");
        conPrecio(otra, "Producto ajeno", 1000, 50);
        sinPrecio(otra, otra.categoriaId, "Ajeno sin precio", 1000, -4);
        conPrecio("Producto propio", 1000, 2);

        InformeInventario resultado = todo();

        assertEquals(1, resultado.filas().size());
        assertEquals("Producto propio", resultado.filas().get(0).nombre());
        assertEquals(1, resultado.totales().referencias());
        assertEquals(2, resultado.totales().unidades());
        assertEquals(0, resultado.totales().referenciasNegativas(), "lo negativo ajeno tampoco");
        igual("2000", resultado.totales().valorAlCosto(), "valor al costo");
    }

    @Test
    void filtraPorTextoEnCodigoYNombre() {
        conPrecio("Arroz Diana", 1000, 1);
        conPrecio("Frijol", 1000, 1);
        sinPrecio("Atún 100%", 1000, 1);

        assertEquals(List.of("Arroz Diana"),
                informe.pagina(null, "arroz", 0, 50).filas().stream().map(InformeInventario.Fila::nombre).toList());
        assertEquals(1, informe.pagina(null, "100%", 0, 50).totalFilas(), "el % busca el símbolo, no es comodín");
        assertEquals(1, informe.pagina(null, "100%", 0, 50).totales().referencias(), "los totales respetan el filtro");
        assertEquals(3, informe.pagina(null, "-" + empresa.marca, 0, 50).totalFilas(), "también por código");
    }

    // ---------------------------------------------------------------------------- las consultas

    @Test
    void consultasConstantes() {
        for (int i = 0; i < 4; i++) {
            conPrecio("Pocos " + i, 1000, 1 + i);
        }
        sinPrecio("Pocos sin precio", 1000, 1);
        long conCinco = consultasDeLaPagina();

        for (int i = 0; i < 55; i++) {
            sinPrecio("Muchos " + i, 1000, 1);
        }
        long conSesenta = consultasDeLaPagina();

        System.out.println("CONSULTAS del inventario valorizado con 5 productos: " + conCinco + " ; con 60: " + conSesenta);
        assertEquals(conCinco, conSesenta, "el número de consultas no puede depender de cuántos productos haya");
        assertTrue(conSesenta <= 3, "totales, página y precios de la página: fueron " + conSesenta);
    }

    private long consultasDeLaPagina() {
        Mockito.clearInvocations(jdbc);
        InformeInventario resultado = informe.pagina(null, null, 0, 50);
        assertFalse(resultado.filas().isEmpty());
        return Mockito.mockingDetails(jdbc).getInvocations().stream()
                .filter(llamada -> llamada.getMethod().getName().equals("sql"))
                .count();
    }

    // -------------------------------------------------------------------------------- el Excel

    @Test
    void excelTraeLosMismosTotales() throws IOException {
        conPrecio("Arroz", 1000, 10);
        conPrecio("=SUMA(1+1)", 500, 4);
        sinPrecio("Sin regla", 1000, 3);
        fijarExistencia(conPrecio("Lenteja", 1000, 5), -3);
        InformeInventario pantalla = todo();

        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        informe.escribirExcel(null, null, salida);

        try (XSSFWorkbook libro = new XSSFWorkbook(new ByteArrayInputStream(salida.toByteArray()))) {
            Sheet hoja = libro.getSheetAt(0);
            Map<String, Row> porEtiqueta = new HashMap<>();
            List<Row> filasDeLaTabla = new ArrayList<>();
            boolean enTabla = false;
            for (Row fila : hoja) {
                String primera = texto(fila.getCell(0));
                if (enTabla) {
                    filasDeLaTabla.add(fila);
                } else if ("Código".equals(primera)) {
                    enTabla = true;
                } else if (primera != null) {
                    porEtiqueta.put(primera, fila);
                }
            }
            InformeInventario.Totales t = pantalla.totales();
            assertEquals(t.referencias(), numero(porEtiqueta, "Referencias en bodega"));
            assertEquals(t.unidades(), numero(porEtiqueta, "Unidades en bodega"));
            assertEquals(t.valorAlCosto().doubleValue(), numero(porEtiqueta, "Valor al costo"), 0.001);
            assertEquals(t.valorAPrecio().doubleValue(), numero(porEtiqueta, "Valor a precio de contado"), 0.001);
            assertEquals(t.sinPrecio(), numero(porEtiqueta, "Referencias sin precio"));
            assertEquals(t.referenciasNegativas(), numero(porEtiqueta, "Referencias vendidas sin existencia"));
            assertEquals(t.unidadesBajoCero(), numero(porEtiqueta, "Unidades bajo cero"));
            assertEquals(t.valorPendienteAlCosto().doubleValue(),
                    numero(porEtiqueta, "Valor pendiente al costo"), 0.001);
            assertEquals(pantalla.totalFilas(), filasDeLaTabla.size(), "la tabla completa, sin paginar");

            Row deLaFormula = filasDeLaTabla.stream()
                    .filter(f -> "=SUMA(1+1)".equals(texto(f.getCell(1)))).findFirst()
                    .orElseThrow(() -> new AssertionError("el nombre que empieza por = debe quedar como texto"));
            assertEquals(CellType.STRING, deLaFormula.getCell(1).getCellType());
            assertTrue(deLaFormula.getCell(1).getCellStyle().getQuotePrefixed());
        }
    }

    @Test
    void elExcelSeLlamaComoElInformeYLaFechaDeHoy() {
        assertEquals("informe-inventario-valorizado-" + Hoy.de(reloj) + ".xlsx", informe.nombreDeArchivo());
    }

    private static String texto(Cell celda) {
        return celda != null && celda.getCellType() == CellType.STRING ? celda.getStringCellValue() : null;
    }

    private static double numero(Map<String, Row> filas, String etiqueta) {
        Row fila = filas.get(etiqueta);
        assertTrue(fila != null, "falta la fila «" + etiqueta + "» en el Excel");
        return fila.getCell(1).getNumericCellValue();
    }
}

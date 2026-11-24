package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.FilaDeCarga;
import co.com.bspstore.sgip.api.dto.ResumenCarga;
import co.com.bspstore.sgip.logica.GeneradorPlantillaPrecios;
import co.com.bspstore.sgip.logica.RecursoNoEncontradoException;
import co.com.bspstore.sgip.logica.ServicioImportacion;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
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
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La plantilla de precios y el rechazo de filas de otro proveedor, contra el esquema real:
 * qué productos salen en la plantilla, cuántas consultas cuesta, cuánta memoria retiene con
 * veinte mil productos, y que la causa nueva cabe en la columna de la base (V49).
 *
 * Crea sus empresas y las borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class PlantillaDePreciosConBaseTest {

    /** El tope de memoria retenida con 20 000 productos: lo medido es de 1 a 2 MB, y 16 MB atrapa un recorrido que no sea en flujo. */
    private static final long TOPE_MEMORIA = 16L * 1024 * 1024;
    private static final int FILAS_DE_LA_MEDICION = 20_000;

    @Autowired GeneradorPlantillaPrecios generador;
    @Autowired ServicioImportacion importacion;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioPlataforma plataforma;
    @MockitoSpyBean JdbcClient jdbc;

    private final List<EmpresaDePrueba> empresasCreadas = new ArrayList<>();
    private EmpresaDePrueba empresa;
    private Integer otroProveedor;

    @BeforeEach
    void prepararDatosPropios() {
        empresa = nuevaEmpresa("Plantilla");
        empresa.entrar();
        otroProveedor = insertarProveedor(empresa, "Otro proveedor " + empresa.marca, "O" + empresa.marca);
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

    private Integer insertarProveedor(EmpresaDePrueba de, String razonSocial, String nit) {
        jdbc.sql("INSERT INTO proveedores (empresa_id, razon_social, nit) VALUES (:e, :r, :n)")
                .param("e", de.empresaId).param("r", razonSocial).param("n", nit).update();
        return jdbc.sql("SELECT proveedor_id FROM proveedores WHERE empresa_id = :e AND nit = :n")
                .param("e", de.empresaId).param("n", nit).query(Integer.class).single();
    }

    private void producto(EmpresaDePrueba de, Integer proveedor, String codigo, String nombre,
                          String costo, boolean activo) {
        jdbc.sql("""
                INSERT INTO productos (empresa_id, categoria_id, proveedor_id, codigo, sku, nombre, costo, activo)
                VALUES (:e, :c, :p, :codigo, :codigo, :nombre, :costo, :activo)
                """)
                .param("e", de.empresaId).param("c", de.categoriaId).param("p", proveedor)
                .param("codigo", codigo).param("nombre", nombre)
                .param("costo", new BigDecimal(costo)).param("activo", activo).update();
    }

    private static final String DIGITOS = "(SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3"
            + " UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7"
            + " UNION ALL SELECT 8 UNION ALL SELECT 9)";

    /** Siembra de golpe, con una sola sentencia, la cantidad de productos que se pida. */
    private void sembrar(EmpresaDePrueba de, Integer proveedor, String prefijo, int cantidad) {
        jdbc.sql("""
                INSERT INTO productos (empresa_id, categoria_id, proveedor_id, codigo, sku, nombre, costo)
                SELECT :e, :c, :p, CONCAT(:prefijo, LPAD(n.i, 6, '0')), CONCAT(:prefijo, LPAD(n.i, 6, '0')),
                       CONCAT('Producto de la plantilla número ', n.i), 1000 + MOD(n.i, 500) + 0.5
                  FROM (SELECT a.d + 10 * b.d + 100 * c.d + 1000 * d.d + 10000 * e.d + 1 AS i
                          FROM %1$s a CROSS JOIN %1$s b CROSS JOIN %1$s c CROSS JOIN %1$s d CROSS JOIN %1$s e) n
                 WHERE n.i <= :cantidad
                """.formatted(DIGITOS))
                .param("e", de.empresaId).param("c", de.categoriaId).param("p", proveedor)
                .param("prefijo", prefijo).param("cantidad", cantidad).update();
    }

    private byte[] plantilla(Integer proveedor) throws IOException {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        generador.escribir(empresa.empresaId, proveedor, salida);
        return salida.toByteArray();
    }

    private long sentencias() {
        return Mockito.mockingDetails(jdbc).getInvocations().stream()
                .filter(llamada -> llamada.getMethod().getName().equals("sql"))
                .count();
    }

    // ------------------------------------------------------- el contenido

    @Test
    void plantillaTraeLosProductosDelProveedorConSuCosto() throws IOException {
        EmpresaDePrueba ajena = nuevaEmpresa("Plantilla ajena");
        producto(empresa, empresa.proveedorId, "B-" + empresa.marca, "Bisagra", "1850.50", true);
        producto(empresa, empresa.proveedorId, "A-" + empresa.marca, "Alicate", "35000.00", true);
        producto(empresa, empresa.proveedorId, "I-" + empresa.marca, "Inactivo", "100.00", false);
        producto(empresa, otroProveedor, "O-" + empresa.marca, "De otro proveedor", "200.00", true);
        producto(ajena, ajena.proveedorId, "X-" + ajena.marca, "De otra empresa", "300.00", true);

        try (Workbook libro = new XSSFWorkbook(new ByteArrayInputStream(plantilla(empresa.proveedorId)))) {
            Sheet hoja = libro.getSheetAt(0);
            assertEquals("Precios", hoja.getSheetName());
            assertEquals(2, hoja.getLastRowNum(), "el encabezado y los dos activos del proveedor");
            Row primera = hoja.getRow(1);
            assertEquals("A-" + empresa.marca, primera.getCell(0).getStringCellValue(), "ordenado por código");
            assertEquals("Alicate", primera.getCell(1).getStringCellValue());
            assertEquals(35000.0, primera.getCell(2).getNumericCellValue(), 0.0001);
            assertEquals(1850.5, hoja.getRow(2).getCell(2).getNumericCellValue(), 0.0001);
        }
    }

    @Test
    void proveedorAjenoEs404() {
        EmpresaDePrueba ajena = nuevaEmpresa("Plantilla ajena");

        assertThrows(RecursoNoEncontradoException.class,
                () -> generador.nombreDeArchivo(empresa.empresaId, ajena.proveedorId));
        assertThrows(RecursoNoEncontradoException.class,
                () -> generador.escribir(empresa.empresaId, ajena.proveedorId, OutputStream.nullOutputStream()));
        assertThrows(RecursoNoEncontradoException.class,
                () -> generador.nombreDeArchivo(empresa.empresaId, 987_654_321));
    }

    @Test
    void proveedorReservadoEs404() {
        plataforma.sembrarSinProveedorYSinCategoria(empresa.empresaId);
        Integer reservado = jdbc.sql("SELECT proveedor_id FROM proveedores WHERE empresa_id = :e AND nit = :n")
                .param("e", empresa.empresaId).param("n", RepositorioPlataforma.NIT_SIN_PROVEEDOR)
                .query(Integer.class).single();

        assertThrows(RecursoNoEncontradoException.class,
                () -> generador.nombreDeArchivo(empresa.empresaId, reservado));
    }

    // ------------------------------------------------------- las consultas

    @Test
    void consultasConstantesCon1Y1000Productos() throws IOException {
        sembrar(empresa, empresa.proveedorId, "U", 1);
        long antes = sentencias();
        plantilla(empresa.proveedorId);
        long conUno = sentencias() - antes;

        sembrar(empresa, otroProveedor, "M", 1000);
        antes = sentencias();
        plantilla(otroProveedor);
        long conMil = sentencias() - antes;

        assertEquals(conUno, conMil, "el número de consultas no depende del volumen");
        assertEquals(2, conMil, "una para el proveedor y una para recorrer sus productos");
    }

    @Test
    void memoriaAcotadaCon20000Productos() throws Exception {
        sembrar(empresa, empresa.proveedorId, "G", FILAS_DE_LA_MEDICION);
        assertEquals(FILAS_DE_LA_MEDICION, jdbc.sql("SELECT COUNT(*) FROM productos WHERE empresa_id = :e")
                .param("e", empresa.empresaId).query(Integer.class).single());
        // Calienta las clases de POI y del controlador: la primera vez carga cientos de clases
        // y eso no es memoria que crezca con las filas.
        generador.escribir(empresa.empresaId, otroProveedor, OutputStream.nullOutputStream());

        // Lo que se mide es lo que se RETIENE, no la basura que pasa: un muestreador fuerza una
        // recolección cada pocos milisegundos mientras se escribe y apunta el mayor montón vivo.
        Runtime vm = Runtime.getRuntime();
        System.gc();
        long base = vm.totalMemory() - vm.freeMemory();
        AtomicLong mayorVivo = new AtomicLong(base);
        Thread muestreador = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    return;
                }
                System.gc();
                mayorVivo.accumulateAndGet(vm.totalMemory() - vm.freeMemory(), Math::max);
            }
        });
        muestreador.setDaemon(true);
        muestreador.start();
        long escritos;
        try {
            CuentaBytes salida = new CuentaBytes();
            generador.escribir(empresa.empresaId, empresa.proveedorId, salida);
            escritos = salida.bytes;
        } finally {
            muestreador.interrupt();
            muestreador.join();
        }

        long crecimiento = mayorVivo.get() - base;
        System.out.printf("[memoria] %d productos, %d bytes de xlsx, montón vivo: base %d KB, mayor %d KB, "
                        + "crecimiento %d KB (tope %d KB)%n", FILAS_DE_LA_MEDICION, escritos, base / 1024,
                mayorVivo.get() / 1024, crecimiento / 1024, TOPE_MEMORIA / 1024);
        assertTrue(escritos > 100_000, "la plantilla salió: " + escritos + " bytes");
        assertTrue(crecimiento < TOPE_MEMORIA,
                "el montón vivo creció " + crecimiento / 1024 / 1024 + " MB con " + FILAS_DE_LA_MEDICION
                        + " productos y el tope son 16 MB");
    }

    private static final class CuentaBytes extends OutputStream {
        long bytes;

        @Override public void write(int b) {
            bytes++;
        }

        @Override public void write(byte[] b, int desde, int largo) {
            bytes += largo;
        }
    }

    // ------------------------------------------------------- la ida y vuelta y la causa nueva

    @Test
    void idaYVueltaSinCambiosNoRechazaNiCambiaCostos() throws IOException {
        producto(empresa, empresa.proveedorId, "A-" + empresa.marca, "Alicate", "35000.00", true);
        producto(empresa, empresa.proveedorId, "B-" + empresa.marca, "Bisagra", "1850.50", true);
        producto(empresa, empresa.proveedorId, "C-" + empresa.marca, "Cinta \"métrica\", 5 m", "12.34", true);
        byte[] xlsx = plantilla(empresa.proveedorId);

        ResumenCarga resumen = importacion.cargar(empresa.proveedorId, "plantilla.xlsx", xlsx.length,
                new ByteArrayInputStream(xlsx));

        assertEquals(3, resumen.aceptados());
        assertEquals(0, resumen.rechazados());
        importacion.aplicar(resumen.cargaId()).orElseThrow();
        assertEquals(List.of(new BigDecimal("35000.00"), new BigDecimal("1850.50"), new BigDecimal("12.34")),
                jdbc.sql("SELECT costo FROM productos WHERE empresa_id = :e ORDER BY codigo")
                        .param("e", empresa.empresaId).query(BigDecimal.class).list());
    }

    @Test
    void filaDeOtroProveedorSeRechazaYElRestoSeAplica() throws IOException {
        producto(empresa, empresa.proveedorId, "P-" + empresa.marca, "Propio", "1000.00", true);
        producto(empresa, empresa.proveedorId, "Q-" + empresa.marca, "Propio dos", "2000.00", true);
        producto(empresa, otroProveedor, "J-" + empresa.marca, "Ajeno", "3000.00", true);
        String lista = "codigo,costo\n"
                + "P-" + empresa.marca + ",1100\n"
                + "J-" + empresa.marca + ",3300\n"
                + "Q-" + empresa.marca + ",2200\n";

        ResumenCarga resumen = importacion.cargar(empresa.proveedorId, "lista.csv", lista.length(),
                new ByteArrayInputStream(lista.getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, resumen.aceptados());
        assertEquals(1, resumen.rechazados());
        FilaDeCarga rechazada = resumen.muestra().stream().filter(f -> !f.aceptado()).findFirst().orElseThrow();
        assertEquals("PRODUCTO_DE_OTRO_PROVEEDOR", rechazada.motivoCodigo());
        // La base la guardó con su categoría: el ENUM de la V49 la admite.
        assertEquals("PRODUCTO_DE_OTRO_PROVEEDOR",
                jdbc.sql("SELECT motivo_codigo FROM detalle_carga_precios WHERE empresa_id = :e AND carga_id = :c "
                                + "AND aceptado = 0")
                        .param("e", empresa.empresaId).param("c", resumen.cargaId())
                        .query(String.class).single());

        importacion.aplicar(resumen.cargaId()).orElseThrow();
        assertEquals(List.of(new BigDecimal("3000.00"), new BigDecimal("1100.00"), new BigDecimal("2200.00")),
                jdbc.sql("SELECT costo FROM productos WHERE empresa_id = :e ORDER BY codigo")
                        .param("e", empresa.empresaId).query(BigDecimal.class).list(),
                "por código J, P, Q: el ajeno conserva su costo y los propios cambiaron");
    }
}

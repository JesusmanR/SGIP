package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.ProductoEnCatalogo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leer un producto o una página del catálogo cuesta lo que tiene la empresa, no
 * lo que tiene la base entera (revisión final, I3; ADR-0029).
 *
 * El lector de código de barras de la caja hace una lectura por cada producto
 * que pasa. Si `v_regla_margen` y `v_regla_iva` se calculan para todas las
 * empresas antes de quedarse con la que pregunta, una tienda de 200 productos
 * espera un segundo y medio por cada lectura en cuanto la plataforma tiene
 * 10.000 productos repartidos en otras tiendas.
 *
 * Carga primero la empresa que se consulta, 200 productos con sus dos reglas
 * propias y reglas generales y de impuesto, y mide; luego carga 10.000 productos
 * con la misma forma en otras cinco empresas, con los mismos códigos de barras, y
 * vuelve a medir. Comprueba tres cosas:
 *
 *   1. Las filas que produce el paso mayor del plan, con `EXPLAIN ANALYZE`, no
 *      crecen con las otras empresas. Es la comprobación que no depende de la
 *      máquina, como en `EscalaDeReglasPropiasTest`, y la que pone la prueba en
 *      rojo sin el arreglo: el paso mayor pasaba de unas mil filas a más de veinte
 *      mil, porque la vista materializaba las reglas de todas las empresas.
 *   2. Cada lectura es una sola sentencia, sea cual sea el volumen (manual §6).
 *   3. Un tiempo máximo, holgado para la CI (ver {@link #MS_PRODUCTO}).
 *
 * Crea sus propias empresas y las borra al terminar, también si algo falla.
 */
@Tag("conBase")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PreciosPorEmpresaConBaseTest {

    /** Lo que deja el paso de `(rows=… loops=…)` tras «actual». */
    private static final Pattern FILAS_REALES =
            Pattern.compile("actual time=[^ ]+ rows=([0-9.e+]+) loops=([0-9.e+]+)");

    private static final int PRODUCTOS_PROPIOS = 200;
    private static final int OTRAS_EMPRESAS = 5;
    private static final int PRODUCTOS_POR_OTRA = 2_000;

    /** Sin depender de las otras empresas el paso mayor no cambia; sin el arreglo crece unas veinte veces. */
    private static final double CRECIMIENTO_MAXIMO = 1.5;

    /**
     * Presupuesto de una lectura de un producto, por identificador o por código
     * de barras, en milisegundos y como mediana de cinco.
     *
     * Medido en un portátil contra MySQL 9.7 con este mismo volumen: antes del
     * arreglo, entre 1.300 y 1.900 ms; después, entre 2 y 6 ms. La cifra queda
     * unas cuarenta veces por encima de lo medido después, para que un ejecutor
     * de la CI lento o cargado no la cruce, y varias veces por debajo de lo de
     * antes, para que vuelva a fallar si la vista vuelve a calcular todas las
     * empresas. No es la promesa de tiempo de la caja: es el piso de lo que no
     * debe volver a pasar.
     */
    private static final long MS_PRODUCTO = 250;

    /**
     * Presupuesto de una página de 50 del catálogo, mediana de cinco. Medido:
     * antes, entre 1.400 y 2.500 ms; después, unos 100 ms. La página sí paga por
     * los 200 productos de su empresa (la vista se materializa antes de ordenar),
     * de ahí que la holgura sea menor que la del producto.
     */
    private static final long MS_PAGINA = 1_000;

    private static final String BARRAS = "770000000100";

    @Autowired RepositorioPlataforma plataforma;
    @Autowired ConsultaCatalogo catalogo;
    @Autowired DataSource fuente;
    @Autowired JdbcClient jdbc;

    private final List<Integer> empresas = new ArrayList<>();
    private Integer empresaId;
    private Integer productoId;

    private double productoAntes;
    private double barrasAntes;
    private double paginaAntes;

    @BeforeAll
    void cargarLasEmpresas() {
        empresaId = cargarEmpresa(PRODUCTOS_PROPIOS);
        productoId = jdbc.sql("SELECT producto_id FROM productos WHERE empresa_id = :e AND codigo_barras = :b")
                .param("e", empresaId).param("b", BARRAS)
                .query(Integer.class).single();
        analizar();
        productoAntes = filasDelPasoMayor(consultaProducto());
        barrasAntes = filasDelPasoMayor(consultaBarras());
        paginaAntes = filasDelPasoMayor(consultaPagina());

        for (int i = 0; i < OTRAS_EMPRESAS; i++) {
            cargarEmpresa(PRODUCTOS_POR_OTRA);
        }
        analizar();
    }

    @AfterAll
    void borrarLoQueCreo() {
        for (Integer empresa : empresas) {
            for (String tabla : List.of("reglas_iva", "reglas_margen", "productos", "proveedores",
                    "categorias", "empresa_config", "empresas")) {
                jdbc.sql("DELETE FROM " + tabla + " WHERE empresa_id = :empresa")
                        .param("empresa", empresa)
                        .update();
            }
        }
    }

    @Test
    void leerUnProductoNoPagaPorLosProductosDeLasOtrasEmpresas() {
        double productoDespues = filasDelPasoMayor(consultaProducto());
        double barrasDespues = filasDelPasoMayor(consultaBarras());

        assertTrue(productoDespues <= productoAntes * CRECIMIENTO_MAXIMO,
                "Con 10.000 productos en otras empresas, leer un producto por identificador pasó de "
                        + productoAntes + " a " + productoDespues + " filas en el paso mayor.");
        assertTrue(barrasDespues <= barrasAntes * CRECIMIENTO_MAXIMO,
                "Con 10.000 productos en otras empresas, leer un código de barras pasó de "
                        + barrasAntes + " a " + barrasDespues + " filas en el paso mayor.");
    }

    @Test
    void unaPaginaDelCatalogoNoPagaPorLosProductosDeLasOtrasEmpresas() {
        double paginaDespues = filasDelPasoMayor(consultaPagina());

        assertTrue(paginaDespues <= paginaAntes * CRECIMIENTO_MAXIMO,
                "Con 10.000 productos en otras empresas, una página pasó de "
                        + paginaAntes + " a " + paginaDespues + " filas en el paso mayor.");
    }

    @Test
    void cadaLecturaEsUnaSolaConsulta() {
        AtomicInteger sentencias = new AtomicInteger();
        ConsultaCatalogo contada = new ConsultaCatalogo(JdbcClient.create(contando(fuente, sentencias)));

        sentencias.set(0);
        Optional<ProductoEnCatalogo> porId = contada.buscar(empresaId, productoId);
        assertTrue(porId.isPresent());
        assertEquals(1, sentencias.get(), "leer un producto por identificador");

        sentencias.set(0);
        Optional<ProductoEnCatalogo> porBarras = contada.porCodigoBarras(empresaId, BARRAS);
        assertTrue(porBarras.isPresent());
        assertEquals(productoId, porBarras.get().productoId(),
                "el código de barras se repite en las otras empresas y debe salir el de esta");
        assertEquals(1, sentencias.get(), "leer un código de barras");

        sentencias.set(0);
        List<ProductoEnCatalogo> pagina = contada.listar(empresaId, null, null, null, null, 50);
        assertEquals(50, pagina.size());
        assertEquals(1, sentencias.get(), "leer una página");
    }

    @Test
    void leerUnProductoCabeEnSuPresupuesto() {
        long porId = medianaMs(() -> catalogo.buscar(empresaId, productoId));
        long porBarras = medianaMs(() -> catalogo.porCodigoBarras(empresaId, BARRAS));

        assertTrue(porId <= MS_PRODUCTO,
                "Leer un producto por identificador tardó " + porId + " ms; el presupuesto es "
                        + MS_PRODUCTO + " ms.");
        assertTrue(porBarras <= MS_PRODUCTO,
                "Leer un código de barras tardó " + porBarras + " ms; el presupuesto es "
                        + MS_PRODUCTO + " ms.");
    }

    @Test
    void unaPaginaCabeEnSuPresupuesto() {
        long pagina = medianaMs(() -> catalogo.listar(empresaId, null, null, null, null, 50));

        assertTrue(pagina <= MS_PAGINA,
                "Una página de 50 tardó " + pagina + " ms; el presupuesto es " + MS_PAGINA + " ms.");
    }

    // ---------------------------------------------------------------------

    private String consultaProducto() {
        return "SELECT * FROM v_precios_producto WHERE empresa_id = " + empresaId
                + " AND producto_id = " + productoId;
    }

    private String consultaBarras() {
        return "SELECT * FROM v_precios_producto WHERE empresa_id = " + empresaId
                + " AND codigo_barras = '" + BARRAS + "'";
    }

    private String consultaPagina() {
        return "SELECT * FROM v_precios_producto WHERE empresa_id = " + empresaId
                + " ORDER BY nombre LIMIT 50";
    }

    /**
     * Una empresa con `n` productos, en dos categorías y dos proveedores, con la
     * forma de una tienda real: reglas generales, de categoría, de proveedor y de
     * las dos, una vencida y una futura; dos reglas propias por producto, la mitad
     * con porcentaje y la mitad con precio escrito; e impuesto general, por
     * categoría y por producto. Las fechas salen del reloj del motor, el mismo con
     * el que la vista decide qué regla rige.
     */
    private Integer cargarEmpresa(int n) {
        String marca = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        Integer e = plataforma.insertarEmpresa("E" + marca, "Prueba de volumen " + marca,
                null, null, null);
        empresas.add(e);
        jdbc.sql("""
                INSERT INTO empresa_config (empresa_id, responsable_iva, aplica_exclusion_umbral)
                VALUES (:e, TRUE, TRUE)
                ON DUPLICATE KEY UPDATE responsable_iva = TRUE, aplica_exclusion_umbral = TRUE
                """).param("e", e).update();
        Integer catA = insertarCategoria(e, "Volumen A");
        Integer catB = insertarCategoria(e, "Volumen B");
        Integer provA = insertarProveedor(e, "Proveedor A", "A" + marca);
        Integer provB = insertarProveedor(e, "Proveedor B", "B" + marca);

        jdbc.sql("""
                INSERT INTO productos (empresa_id, categoria_id, proveedor_id, codigo, sku,
                                       codigo_barras, nombre, costo)
                WITH RECURSIVE n (i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < :n)
                SELECT /*+ SET_VAR(cte_max_recursion_depth = 100000) */
                       :e, IF(i % 2 = 0, :catA, :catB), IF(i % 3 = 0, :provA, :provB),
                       CONCAT('VOL-', i), CONCAT('VOL-', i), CONCAT('770', LPAD(i, 9, '0')),
                       CONCAT('Producto ', LPAD(i, 6, '0')), 1000 + (i % 500) * 7
                  FROM n
                """)
                .param("n", n).param("e", e)
                .param("catA", catA).param("catB", catB)
                .param("provA", provA).param("provB", provB)
                .update();

        jdbc.sql("""
                INSERT INTO reglas_margen (empresa_id, categoria_id, proveedor_id, forma_pago,
                                           forma_calculo, factor, vigente_desde, vigente_hasta)
                VALUES (:e, NULL,  NULL,   'CONTADO', 'SOBRE_COSTO',  1.2000, CURDATE() - INTERVAL 10 MONTH, NULL),
                       (:e, NULL,  NULL,   'CREDITO', 'SOBRE_COSTO',  1.2500, CURDATE() - INTERVAL 10 MONTH, NULL),
                       (:e, :catA, NULL,   'CONTADO', 'SOBRE_PRECIO', 1.3000, CURDATE() - INTERVAL 10 MONTH, NULL),
                       (:e, NULL,  :provA, 'CREDITO', 'SOBRE_COSTO',  1.3500, CURDATE() - INTERVAL 9 MONTH,  NULL),
                       (:e, :catB, :provB, 'CONTADO', 'SOBRE_COSTO',  1.4000, CURDATE() - INTERVAL 8 MONTH,  NULL),
                       (:e, :catB, NULL,   'CREDITO', 'SOBRE_COSTO',  1.4500, CURDATE() - INTERVAL 10 MONTH,
                                                                              CURDATE() - INTERVAL 9 MONTH),
                       (:e, NULL,  NULL,   'CONTADO', 'SOBRE_COSTO',  1.9000, CURDATE() + INTERVAL 3 YEAR,   NULL)
                """)
                .param("e", e)
                .param("catA", catA).param("catB", catB)
                .param("provA", provA).param("provB", provB)
                .update();
        jdbc.sql("""
                INSERT INTO reglas_margen (empresa_id, producto_id, forma_pago, forma_calculo,
                                           factor, precio_fijo, vigente_desde)
                SELECT p.empresa_id, p.producto_id, f.forma, 'SOBRE_COSTO',
                       IF(p.producto_id % 2 = 0, 1.1500, NULL),
                       IF(p.producto_id % 2 = 0, NULL, 5000 + p.producto_id % 300),
                       CURDATE() - INTERVAL 10 MONTH
                  FROM productos p
                  JOIN (SELECT 'CONTADO' AS forma UNION ALL SELECT 'CREDITO') f
                 WHERE p.empresa_id = :e
                """).param("e", e).update();

        jdbc.sql("""
                INSERT INTO reglas_iva (empresa_id, categoria_id, producto_id, tipo_impuesto_id,
                                        vigente_desde)
                SELECT :e, NULL, NULL, t.tipo_impuesto_id, CURDATE() - INTERVAL 10 MONTH
                  FROM tipos_impuesto t WHERE t.codigo = 'IVA_19'
                UNION ALL
                SELECT :e, :catA, NULL, t.tipo_impuesto_id, CURDATE() - INTERVAL 10 MONTH
                  FROM tipos_impuesto t WHERE t.codigo = 'IVA_5'
                """)
                .param("e", e).param("catA", catA)
                .update();
        jdbc.sql("""
                INSERT INTO reglas_iva (empresa_id, producto_id, tipo_impuesto_id, vigente_desde)
                SELECT p.empresa_id, p.producto_id, t.tipo_impuesto_id, CURDATE() - INTERVAL 10 MONTH
                  FROM productos p
                  JOIN tipos_impuesto t ON t.codigo = 'EXCLUIDO'
                 WHERE p.empresa_id = :e AND p.producto_id % 10 = 0
                """).param("e", e).update();
        return e;
    }

    private Integer insertarCategoria(Integer empresa, String nombre) {
        KeyHolder llave = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO categorias (empresa_id, nombre) VALUES (:e, :n)")
                .param("e", empresa).param("n", nombre).update(llave);
        return llave.getKeyAs(Number.class).intValue();
    }

    private Integer insertarProveedor(Integer empresa, String nombre, String nit) {
        KeyHolder llave = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO proveedores (empresa_id, razon_social, nit) VALUES (:e, :n, :nit)")
                .param("e", empresa).param("n", nombre).param("nit", nit).update(llave);
        return llave.getKeyAs(Number.class).intValue();
    }

    private void analizar() {
        jdbc.sql("ANALYZE TABLE productos, reglas_margen, reglas_iva").query().listOfRows();
    }

    /**
     * Las filas del paso que más produjo: filas por vueltas, porque un bucle anidado
     * las reparte. Sin `index_condition_pushdown`, por la misma razón que en
     * `EscalaDeReglasPropiasTest`: con él, lo que se descarta dentro de InnoDB no
     * aparece en el plan.
     */
    private double filasDelPasoMayor(String consulta) {
        String plan = String.join("\n", jdbc.sql(
                        "EXPLAIN ANALYZE " + consulta.replaceFirst("^SELECT ",
                                "SELECT /*+ SET_VAR(optimizer_switch='index_condition_pushdown=off') */ "))
                .query(String.class).list());
        Matcher m = FILAS_REALES.matcher(plan);
        double mayor = -1;
        while (m.find()) {
            mayor = Math.max(mayor, Double.parseDouble(m.group(1)) * Double.parseDouble(m.group(2)));
        }
        // Sin esto, un cambio de formato de MySQL dejaría la prueba pasando sin mirar nada.
        assertTrue(mayor > 0, "El plan no dice cuántas filas produjo: " + plan);
        return mayor;
    }

    /** Una vuelta para calentar y la mediana de cinco: lo que se mide es la consulta, no el arranque. */
    private static long medianaMs(Supplier<?> lectura) {
        assertFalse(vacio(lectura.get()), "la lectura no devolvió nada");
        long[] tiempos = new long[5];
        for (int i = 0; i < tiempos.length; i++) {
            long inicio = System.nanoTime();
            lectura.get();
            tiempos[i] = (System.nanoTime() - inicio) / 1_000_000;
        }
        Arrays.sort(tiempos);
        return tiempos[tiempos.length / 2];
    }

    private static boolean vacio(Object resultado) {
        return resultado instanceof Optional<?> o ? o.isEmpty()
                : resultado instanceof List<?> l && l.isEmpty();
    }

    /**
     * La misma fuente, contando cada sentencia que se prepara. Se envuelve aquí y
     * no en el contexto de Spring, que reconoce y desenvuelve su propio pool.
     */
    private static DataSource contando(DataSource fuente, AtomicInteger sentencias) {
        return new DelegatingDataSource(fuente) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection real = super.getConnection();
                return (Connection) Proxy.newProxyInstance(
                        Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                        (proxy, metodo, argumentos) -> {
                            String nombre = metodo.getName();
                            if (nombre.equals("prepareStatement") || nombre.equals("createStatement")
                                    || nombre.equals("prepareCall")) {
                                sentencias.incrementAndGet();
                            }
                            try {
                                return metodo.invoke(real, argumentos);
                            } catch (InvocationTargetException ex) {
                                throw ex.getCause();
                            }
                        });
            }
        };
    }
}

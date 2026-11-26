package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.InformeInventario;
import co.com.bspstore.sgip.api.dto.InformeInventario.Fila;
import co.com.bspstore.sgip.api.dto.InformeInventario.Totales;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Las lecturas del inventario valorizado (RF-57): los totales, una página de la tabla y el recorrido
 * completo con cursor para el Excel.
 *
 * ## Qué cuenta
 *
 * Los productos de la empresa, activos e inactivos: uno inactivo con existencia sigue siendo plata en bodega.
 * La existencia mayor que cero es lo que está en bodega y entra en los totales; la menor que cero es lo
 * vendido sin existencia y se declara aparte, sin restarse (ADR-0019, como la vista
 * {@code v_inventario_valorizado} del Panel). La tabla trae las dos, todo lo que no sea cero.
 *
 * ## De dónde sale el precio
 *
 * Es el precio de contado sin IVA de {@code v_precios_producto}, y nulo si el producto no tiene regla de
 * precio o está inactivo (la vista solo tiene activos). La página lo lee de la vista, con una segunda consulta
 * con {@code IN} limitada a los ids de la página: no depende de cuántos productos tenga la empresa.
 *
 * Los totales y el recorrido del Excel necesitan el precio de <i>todos</i> los productos, y ahí la vista no
 * sirve: por cada producto materializa tres tablas derivadas laterales (las reglas de margen de contado y de
 * crédito y la del IVA), y con 10 000 productos solo ese paso toma unos 4,5 s, casi todo el presupuesto de 5 s
 * del informe (el Panel paga lo mismo en su capital a precio). Por eso totales y cursor leen de
 * {@code v_regla_margen}, la misma regla que la vista elige, y aplican la misma fórmula ({@link #PRECIO}):
 * unos 125 ms para los mismos 10 000. Lo que no leen es el IVA ni el precio de crédito, que no necesitan.
 * {@code InformeInventarioConBaseTest.elValorAPrecioCoincideConLaVista} compara las dos rutas con reglas de
 * toda clase para que no puedan separarse sin que una prueba lo note.
 *
 * Toda sentencia filtra {@code empresa_id} en cada tabla que toca (RN-16).
 */
@Repository
public class RepositorioInformeInventario {

    /** Los filtros comunes, con el patrón «:x IS NULL OR …» para que sea una sola sentencia. */
    private static final String FILTROS = """
            AND (:categoria IS NULL OR p.categoria_id = :categoria)
            AND (:patron IS NULL OR p.nombre LIKE :patron OR p.codigo LIKE :patron)
            """;

    /** El valor al costo de una fila: una referencia en negativo valoriza en cero. */
    private static final String VALOR_AL_COSTO = "GREATEST(p.existencia, 0) * p.costo";

    /**
     * El precio de contado sin IVA, con la fórmula de {@code v_precios_producto} (V36): el precio fijo si la
     * regla lo trae; si no, costo / (2 − factor) para «sobre precio» o costo × factor para «sobre costo»,
     * redondeado a pesos. Sin regla el resultado es nulo, nunca cero; un inactivo no tiene precio.
     * Necesita el {@link #REGLA_DE_CONTADO}.
     */
    private static final String PRECIO = """
            CASE WHEN p.activo = TRUE
                 THEN CASE WHEN mc.precio_fijo IS NOT NULL THEN mc.precio_fijo
                           WHEN mc.forma_calculo = 'SOBRE_PRECIO' THEN ROUND(p.costo / (2 - mc.factor), 0)
                           ELSE ROUND(p.costo * mc.factor, 0) END
            END""";

    /** La regla de contado que rige hoy para cada producto, por empresa (la vista busca solo las suyas). */
    private static final String REGLA_DE_CONTADO = """
            LEFT JOIN v_regla_margen mc
                   ON mc.empresa_id = p.empresa_id AND mc.producto_id = p.producto_id
                  AND mc.forma_pago = 'CONTADO'""";

    private static final String ORDEN = "ORDER BY " + VALOR_AL_COSTO + " DESC, p.producto_id";

    private final JdbcClient jdbc;

    public RepositorioInformeInventario(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Los totales y la página pedida: tres sentencias, siempre las mismas, sin importar cuántos productos
     * haya (totales; página; precios de los ids de la página, que se omite si la página está vacía).
     *
     * Quien llama decide el tamaño de la página y la transacción: para que los totales y la tabla salgan de
     * la misma lectura, esto debe correr dentro de una sola.
     */
    public InformeInventario pagina(Integer empresa, Integer categoriaId, String q, int pagina, int tamano) {
        String patron = patron(q);
        TotalesYConteo cifras = leerTotales(empresa, categoriaId, patron);
        List<Fila> filas = leerPagina(empresa, categoriaId, patron, pagina, tamano);
        return new InformeInventario(cifras.totales(), filas, cifras.filas());
    }

    /** Solo los totales de lo que pasa el filtro (para el Excel, que recorre la tabla aparte). */
    public Totales totales(Integer empresa, Integer categoriaId, String q) {
        return leerTotales(empresa, categoriaId, patron(q)).totales();
    }

    /**
     * Cada fila de la tabla completa, en el orden de la pantalla, con un cursor: el conector de MySQL con
     * {@code fetchSize = Integer.MIN_VALUE} entrega fila a fila y no junta el resultado en memoria, de modo
     * que lo que se retiene no crece con el número de productos. Mientras el cursor está abierto no se puede
     * lanzar otra sentencia por la misma conexión: {@code fila} solo debe escribir.
     */
    public void recorrer(Integer empresa, Integer categoriaId, String q, Consumer<Fila> fila) {
        jdbc.sql("""
                        SELECT p.producto_id, p.codigo, p.nombre, c.nombre AS categoria, p.activo,
                               p.existencia, p.costo, %s AS precio_contado,
                               %s AS valor_al_costo
                          FROM productos p
                          JOIN categorias c ON c.empresa_id = p.empresa_id AND c.categoria_id = p.categoria_id
                          %s
                         WHERE p.empresa_id = :empresa AND c.empresa_id = :empresa
                           AND p.existencia <> 0
                           %s
                         %s
                        """.formatted(PRECIO, VALOR_AL_COSTO, REGLA_DE_CONTADO, FILTROS, ORDEN))
                .withFetchSize(Integer.MIN_VALUE)
                .param("empresa", empresa)
                .param("categoria", categoriaId)
                .param("patron", patron(q))
                .query((RowCallbackHandler) rs -> fila.accept(fila(rs, rs.getBigDecimal("precio_contado"))));
    }

    /** El nombre de una categoría de la empresa; vacío si no existe o es de otra empresa. */
    public Optional<String> nombreDeCategoria(Integer empresa, Integer categoriaId) {
        return jdbc.sql("""
                        SELECT nombre FROM categorias
                         WHERE empresa_id = :empresa AND categoria_id = :categoria
                        """)
                .param("empresa", empresa)
                .param("categoria", categoriaId)
                .query(String.class)
                .optional();
    }

    // ------------------------------------------------------------------------------ por dentro

    private record TotalesYConteo(Totales totales, long filas) {
    }

    /**
     * Una sola sentencia: todos los totales y el conteo de la tabla en el mismo recorrido de {@code productos}.
     * {@code COALESCE} porque sin filas las sumas dan nulo y una categoría vacía debe dar ceros.
     * {@code v.precio_contado IS NULL} es «sin precio»: sin regla, o inactivo (la vista solo tiene activos).
     */
    private TotalesYConteo leerTotales(Integer empresa, Integer categoriaId, String patron) {
        return jdbc.sql("""
                        SELECT CAST(COALESCE(SUM(x.existencia > 0), 0) AS SIGNED) AS referencias,
                               CAST(COALESCE(SUM(GREATEST(x.existencia, 0)), 0) AS SIGNED) AS unidades,
                               COALESCE(SUM(GREATEST(x.existencia, 0) * x.costo), 0) AS valor_al_costo,
                               COALESCE(SUM(CASE WHEN x.existencia > 0 AND x.precio IS NOT NULL
                                                 THEN x.existencia * x.precio END), 0) AS valor_a_precio,
                               CAST(COALESCE(SUM(x.existencia > 0 AND x.precio IS NULL), 0)
                                    AS SIGNED) AS sin_precio,
                               CAST(COALESCE(SUM(x.existencia < 0), 0) AS SIGNED) AS referencias_negativas,
                               CAST(COALESCE(SUM(GREATEST(-x.existencia, 0)), 0) AS SIGNED) AS unidades_bajo_cero,
                               COALESCE(SUM(GREATEST(-x.existencia, 0) * x.costo), 0) AS valor_pendiente,
                               CAST(COALESCE(SUM(x.existencia <> 0), 0) AS SIGNED) AS filas
                          FROM (SELECT p.existencia, p.costo, %s AS precio
                                  FROM productos p
                                  %s
                                 WHERE p.empresa_id = :empresa
                                   %s) x
                        """.formatted(PRECIO, REGLA_DE_CONTADO, FILTROS))
                .param("empresa", empresa)
                .param("categoria", categoriaId)
                .param("patron", patron)
                .query((rs, n) -> new TotalesYConteo(new Totales(
                        rs.getLong("referencias"), rs.getLong("unidades"),
                        rs.getBigDecimal("valor_al_costo"), rs.getBigDecimal("valor_a_precio"),
                        rs.getLong("sin_precio"), rs.getLong("referencias_negativas"),
                        rs.getLong("unidades_bajo_cero"), rs.getBigDecimal("valor_pendiente")),
                        rs.getLong("filas")))
                .single();
    }

    private List<Fila> leerPagina(Integer empresa, Integer categoriaId, String patron, int pagina, int tamano) {
        List<Fila> sinPrecio = jdbc.sql("""
                        SELECT p.producto_id, p.codigo, p.nombre, c.nombre AS categoria, p.activo,
                               p.existencia, p.costo, %s AS valor_al_costo
                          FROM productos p
                          JOIN categorias c ON c.empresa_id = p.empresa_id AND c.categoria_id = p.categoria_id
                         WHERE p.empresa_id = :empresa AND c.empresa_id = :empresa
                           AND p.existencia <> 0
                           %s
                         %s
                         LIMIT :tamano OFFSET :desplazamiento
                        """.formatted(VALOR_AL_COSTO, FILTROS, ORDEN))
                .param("empresa", empresa)
                .param("categoria", categoriaId)
                .param("patron", patron)
                .param("tamano", tamano)
                .param("desplazamiento", (long) pagina * tamano)
                .query((rs, n) -> fila(rs, null))
                .list();
        if (sinPrecio.isEmpty()) {
            return sinPrecio;
        }

        // Los precios solo de los ids de la página: una consulta más, del tamaño de la página.
        List<Integer> ids = sinPrecio.stream().map(Fila::productoId).toList();
        Map<Integer, BigDecimal> precios = new HashMap<>();
        jdbc.sql("""
                        SELECT producto_id, precio_contado
                          FROM v_precios_producto
                         WHERE empresa_id = :empresa AND producto_id IN (:ids)
                        """)
                .param("empresa", empresa)
                .param("ids", ids)
                .query((RowCallbackHandler) rs -> {
                    BigDecimal precio = rs.getBigDecimal("precio_contado");
                    if (precio != null) {
                        precios.put(rs.getInt("producto_id"), precio);
                    }
                });

        List<Fila> filas = new ArrayList<>(sinPrecio.size());
        for (Fila f : sinPrecio) {
            filas.add(conPrecio(f, precios.get(f.productoId())));
        }
        return filas;
    }

    private static Fila fila(ResultSet rs, BigDecimal precio) throws SQLException {
        Fila base = new Fila(rs.getInt("producto_id"), rs.getString("codigo"), rs.getString("nombre"),
                rs.getString("categoria"), rs.getBoolean("activo"), rs.getInt("existencia"),
                rs.getBigDecimal("costo"), null, rs.getBigDecimal("valor_al_costo"), null);
        return conPrecio(base, precio);
    }

    /** El precio y su valor (solo de lo que hay en bodega), o nulos si no tiene precio. */
    private static Fila conPrecio(Fila f, BigDecimal precio) {
        if (precio == null) {
            return f;
        }
        BigDecimal valor = precio.multiply(BigDecimal.valueOf(Math.max(f.existencia(), 0)));
        return new Fila(f.productoId(), f.codigo(), f.nombre(), f.categoria(), f.activo(), f.existencia(),
                f.costo(), precio, f.valorAlCosto(), valor);
    }

    /** El texto como patrón de {@code LIKE «contiene»}; la barra, el % y el _ valen por sí mismos. */
    private static String patron(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String literal = q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + literal + "%";
    }
}

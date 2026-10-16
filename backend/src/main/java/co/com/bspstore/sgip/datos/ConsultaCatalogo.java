package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.ProductoEnCatalogo;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Lectura del catálogo contra la vista v_precios_producto.
 *
 * No es una entidad JPA a propósito. La vista no es un agregado que se
 * guarde: es el resultado de cruzar el producto con la regla de margen y
 * la regla de impuesto vigentes, y mapearla como entidad obligaría a
 * declarar como columnas cosas que el motor calcula en cada consulta.
 * Además, la aplicación arranca con ddl-auto en validate, y mapear una
 * vista como tabla invita a que la validación se queje de tipos que
 * nadie va a escribir nunca.
 *
 * Todas las consultas filtran por empresa. La vista expone empresa_id
 * justamente para que ese filtro exista en un solo sitio (RN-16).
 */
@Repository
public class ConsultaCatalogo {

    private static final String COLUMNAS = """
            producto_id, codigo, sku, nombre, presentacion, categoria, proveedor,
            costo, existencia, existencia_minima, estado_stock,
            precio_contado, precio_credito, tratamiento_iva, tarifa_contado,
            total_contado, tributacion_sin_configurar, cerca_umbral,
            imagen_principal IS NOT NULL AS tiene_imagen
            """;

    private final JdbcClient jdbc;

    public ConsultaCatalogo(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ProductoEnCatalogo> listar(Integer empresaId, String busqueda, int limite) {
        // El comodín se arma aquí y se pasa como parámetro, no se concatena
        // dentro de la sentencia: el texto lo escribe quien usa la pantalla.
        String patron = (busqueda == null || busqueda.isBlank()) ? null : "%" + busqueda.trim() + "%";
        return jdbc.sql("""
                        SELECT %s FROM v_precios_producto
                         WHERE empresa_id = :empresa
                           AND (:patron IS NULL
                                OR nombre LIKE :patron
                                OR codigo LIKE :patron
                                OR sku    LIKE :patron)
                         ORDER BY nombre
                         LIMIT :limite
                        """.formatted(COLUMNAS))
                .param("empresa", empresaId)
                .param("patron", patron)
                .param("limite", limite)
                .query(ProductoEnCatalogo.class)
                .list();
    }

    public Optional<ProductoEnCatalogo> buscar(Integer empresaId, Integer productoId) {
        return jdbc.sql("""
                        SELECT %s FROM v_precios_producto
                         WHERE empresa_id = :empresa AND producto_id = :producto
                        """.formatted(COLUMNAS))
                .param("empresa", empresaId)
                .param("producto", productoId)
                .query(ProductoEnCatalogo.class)
                .optional();
    }

    /** Lo que la pantalla P-10 necesita tras leer un código de barras. */
    public Optional<ProductoEnCatalogo> porCodigoBarras(Integer empresaId, String codigoBarras) {
        // La vista no expone codigo_barras, de modo que el producto se
        // localiza en la tabla y se lee de la vista. La subconsulta repite
        // el filtro de empresa a propósito: así ninguna de las dos mitades
        // depende de que la otra acote bien.
        return jdbc.sql("""
                        SELECT %s FROM v_precios_producto
                         WHERE empresa_id = :empresa
                           AND producto_id = (SELECT p.producto_id FROM productos p
                                               WHERE p.empresa_id = :empresa
                                                 AND p.codigo_barras = :barras)
                        """.formatted(COLUMNAS))
                .param("empresa", empresaId)
                .param("barras", codigoBarras)
                .query(ProductoEnCatalogo.class)
                .optional();
    }
}

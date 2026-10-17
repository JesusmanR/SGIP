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

    /**
     * El identificador de la imagen principal, no su ruta.
     *
     * Va aquí y no en la vista porque es lo único que el catálogo
     * necesita para armar una dirección que no mienta: antes pedía «la
     * imagen principal del producto 6», que nombra un papel y no un
     * archivo, y una dirección así devuelve cosas distintas en momentos
     * distintos. Con el identificador la dirección cambia cuando cambia
     * la principal.
     *
     * La subconsulta repite el filtro de empresa a propósito, como la de
     * código de barras más abajo: así ninguna de las dos mitades depende
     * de que la otra acote bien. La vista traía una subconsulta parecida
     * para imagen_principal que no lo repetía; la migración V15 la
     * corrigió, y verificar_aislamiento.sh comprueba en cada entrega que
     * siga acotando.
     */
    private static final String IMAGEN_PRINCIPAL = """
            (SELECT i.imagen_id FROM producto_imagenes i
              WHERE i.empresa_id = v.empresa_id
                AND i.producto_id = v.producto_id
                AND i.principal = TRUE)
            """;

    private static final String COLUMNAS = """
            producto_id, codigo, sku, nombre, presentacion,
            descripcion, codigo_barras,
            categoria_id, categoria, proveedor_id, proveedor,
            costo, existencia, existencia_minima, estado_stock,
            precio_contado, precio_credito, tratamiento_iva, tarifa_contado,
            total_contado, tributacion_sin_configurar, cerca_umbral,
            %1$s AS imagen_principal_id,
            %1$s IS NOT NULL AS tiene_imagen
            """.formatted(IMAGEN_PRINCIPAL);

    private final JdbcClient jdbc;

    public ConsultaCatalogo(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * El catálogo con sus filtros (RF-16).
     *
     * Los cuatro filtros son opcionales y se combinan: cada uno se anula
     * a sí mismo cuando llega nulo, con el patrón «:x IS NULL OR …». Eso
     * deja una sola sentencia en lugar de una por combinación, y una
     * sentencia que el motor puede preparar una vez.
     *
     * Los identificadores se comparan contra las columnas que la V17
     * añadió a la vista. Filtrar por el nombre de la categoría habría
     * obligado a comparar cadenas, que se rompe con un acento o con un
     * cambio de nombre.
     */
    public List<ProductoEnCatalogo> listar(Integer empresaId, String busqueda,
                                           Integer categoriaId, Integer proveedorId,
                                           String estadoStock, int limite) {
        // El comodín se arma aquí y se pasa como parámetro, no se concatena
        // dentro de la sentencia: el texto lo escribe quien usa la pantalla.
        String patron = (busqueda == null || busqueda.isBlank()) ? null : "%" + busqueda.trim() + "%";
        return jdbc.sql("""
                        SELECT %s FROM v_precios_producto v
                         WHERE empresa_id = :empresa
                           AND (:patron IS NULL
                                OR nombre LIKE :patron
                                OR codigo LIKE :patron
                                OR sku    LIKE :patron)
                           AND (:categoria IS NULL OR categoria_id = :categoria)
                           AND (:proveedor IS NULL OR proveedor_id = :proveedor)
                           AND (:estado    IS NULL OR estado_stock = :estado)
                         ORDER BY nombre
                         LIMIT :limite
                        """.formatted(COLUMNAS))
                .param("empresa", empresaId)
                .param("patron", patron)
                .param("categoria", categoriaId)
                .param("proveedor", proveedorId)
                .param("estado", estadoStock)
                .param("limite", limite)
                .query(ProductoEnCatalogo.class)
                .list();
    }

    public Optional<ProductoEnCatalogo> buscar(Integer empresaId, Integer productoId) {
        return jdbc.sql("""
                        SELECT %s FROM v_precios_producto v
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
                        SELECT %s FROM v_precios_producto v
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

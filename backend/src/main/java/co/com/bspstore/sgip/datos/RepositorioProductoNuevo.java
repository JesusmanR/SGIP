package co.com.bspstore.sgip.datos;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

/**
 * Lo que hace falta para crear un producto dentro de la venta (RF-42): el proveedor y la categoría
 * genéricos de la empresa, las comprobaciones de que el código y el código de barras estén libres y
 * la fila del producto.
 *
 * Toda sentencia lleva {@code empresa_id} (ADR-0011), también las subconsultas. Escribe con
 * {@code JdbcClient} y no con la entidad {@code Producto}: la venta ya trabaja con esta conexión y
 * necesita saber, sin pasar por la memoria de JPA, que la fila nació dentro de su transacción.
 */
@Repository
public class RepositorioProductoNuevo {

    private final JdbcClient jdbc;

    public RepositorioProductoNuevo(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** El proveedor y la categoría de los productos nuevos; cada uno es nulo si la empresa no lo tiene. */
    public record SinProveedorYSinCategoria(Integer proveedorId, Integer categoriaId) {}

    /**
     * Los identificadores de «Sin proveedor» y «Sin categoría» de la empresa, en una sola consulta.
     * Los siembran la V45 y el alta de la empresa; esta consulta solo los busca. Si hubiera dos con
     * el mismo nombre (no puede: la clave única de las categorías lo impide y el proveedor lleva un
     * NIT reservado) se toma el más antiguo.
     */
    public SinProveedorYSinCategoria buscarSinProveedorYSinCategoria(Integer empresaId) {
        return jdbc.sql("""
                        SELECT (SELECT p.proveedor_id FROM proveedores p
                                 WHERE p.empresa_id = :empresa AND p.razon_social = :proveedor
                                 ORDER BY p.proveedor_id LIMIT 1) AS proveedor_id,
                               (SELECT c.categoria_id FROM categorias c
                                 WHERE c.empresa_id = :empresa AND c.nombre = :categoria
                                 ORDER BY c.categoria_id LIMIT 1) AS categoria_id
                        """)
                .param("empresa", empresaId)
                .param("proveedor", RepositorioPlataforma.NOMBRE_SIN_PROVEEDOR)
                .param("categoria", RepositorioPlataforma.NOMBRE_SIN_CATEGORIA)
                .query(SinProveedorYSinCategoria.class)
                .single();
    }

    /** Si algún producto de la empresa, activo o no, ya tiene ese código o ese SKU. */
    public boolean existeCodigo(Integer empresaId, String codigo) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM productos
                                        WHERE empresa_id = :empresa AND (codigo = :codigo OR sku = :codigo))
                        """)
                .param("empresa", empresaId)
                .param("codigo", codigo)
                .query(Boolean.class)
                .single();
    }

    /** Si algún producto de la empresa, activo o no, ya tiene ese código de barras. */
    public boolean existeCodigoDeBarras(Integer empresaId, String codigoDeBarras) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM productos
                                        WHERE empresa_id = :empresa AND codigo_barras = :barras)
                        """)
                .param("empresa", empresaId)
                .param("barras", codigoDeBarras)
                .query(Boolean.class)
                .single();
    }

    public record NuevoProducto(Integer empresaId, Integer categoriaId, Integer proveedorId, String codigo,
                                String nombre, String codigoDeBarras, BigDecimal costo) {}

    /**
     * Inserta el producto con existencia cero, el costo que se le da marcado como provisional y el
     * SKU igual al código (como hace el alta de catálogo cuando no dan SKU). Si el código de barras
     * lo ganó otra transacción entre la comprobación y esta sentencia, el motor lo rechaza con
     * {@code uq_productos_barras}: quien llama traduce ese choque.
     */
    public Integer insertar(NuevoProducto p) {
        KeyHolder llaves = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO productos (empresa_id, categoria_id, proveedor_id, codigo, sku, codigo_barras,
                                               nombre, costo, costo_provisional, existencia)
                        VALUES (:empresa, :categoria, :proveedor, :codigo, :codigo, :barras,
                                :nombre, :costo, TRUE, 0)
                        """)
                .param("empresa", p.empresaId())
                .param("categoria", p.categoriaId())
                .param("proveedor", p.proveedorId())
                .param("codigo", p.codigo())
                .param("barras", p.codigoDeBarras())
                .param("nombre", p.nombre())
                .param("costo", p.costo())
                .update(llaves);
        Number clave = llaves.getKey();
        if (clave == null) {
            throw new IllegalStateException("El motor no devolvió el identificador del producto nuevo.");
        }
        return clave.intValue();
    }
}

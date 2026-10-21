package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.FilaDeCarga;
import co.com.bspstore.sgip.logica.CabeceraCarga;
import co.com.bspstore.sgip.logica.ProductoDelCatalogo;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Las cargas de listas de precios (RF-24, RF-26, RF-27).
 *
 * Todas las consultas filtran por empresa, y las escrituras además
 * declaran la empresa en la fila. No es redundante: la V28 puso claves
 * compuestas en estas cuatro tablas justamente porque antes una carga
 * de un comercio podía apuntar al catálogo de otro y reescribirle los
 * costos. La columna repetida es lo que hace que el motor pueda
 * negarse, en vez de depender de que esta clase se acuerde.
 */
@Repository
public class RepositorioImportacion {

    private final JdbcClient jdbc;

    public RepositorioImportacion(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean proveedorPropio(Integer empresaId, Integer proveedorId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM proveedores
                         WHERE empresa_id = :empresa AND proveedor_id = :proveedor
                        """)
                .param("empresa", empresaId)
                .param("proveedor", proveedorId)
                .query(Integer.class)
                .single() > 0;
    }

    /**
     * El catálogo de la empresa indexado por cómo lo nombra el proveedor.
     *
     * Una sola consulta y no una por fila. Con el tope de veinte mil
     * filas que admite el RF-24, preguntar por cada una sería veinte mil
     * viajes a la base y el RF-27 pide cinco mil registros en menos de
     * un minuto.
     *
     * Se indexa por código y también por SKU porque el proveedor nombra
     * sus productos como quiere, y en este catálogo el SKU se copia del
     * código salvo que alguien lo cambie. Si los dos apuntan a productos
     * distintos manda el código: es el identificador que el RF-15 trata
     * como propio del comercio.
     */
    public Map<String, ProductoDelCatalogo> catalogoPorReferencia(Integer empresaId) {
        List<ProductoDelCatalogo> filas = jdbc.sql("""
                        SELECT producto_id, codigo, sku, costo
                          FROM productos
                         WHERE empresa_id = :empresa AND activo = TRUE
                        """)
                .param("empresa", empresaId)
                .query(ProductoDelCatalogo.class)
                .list();

        Map<String, ProductoDelCatalogo> indice = new HashMap<>(filas.size() * 2);
        for (ProductoDelCatalogo p : filas) {
            if (p.sku() != null) {
                indice.putIfAbsent(normalizar(p.sku()), p);
            }
        }
        // El código se mete después y pisando: manda sobre el SKU.
        for (ProductoDelCatalogo p : filas) {
            indice.put(normalizar(p.codigo()), p);
        }
        return indice;
    }

    /** Cómo se cotejan las referencias: sin espacios y en mayúsculas. */
    public static String normalizar(String referencia) {
        return referencia == null ? "" : referencia.trim().toUpperCase(Locale.ROOT);
    }

    public Long insertarCarga(Integer empresaId, Integer proveedorId, Integer usuarioId,
                              String nombreArchivo, int leidos, int aceptados, int rechazados) {
        return jdbc.sql("""
                        INSERT INTO cargas_precios
                               (empresa_id, proveedor_id, usuario_id, origen, nombre_archivo,
                                estado, registros_leidos, registros_aceptados,
                                registros_rechazados)
                        VALUES (:empresa, :proveedor, :usuario, 'ARCHIVO', :archivo,
                                'VALIDADA', :leidos, :aceptados, :rechazados)
                        """)
                .param("empresa", empresaId)
                .param("proveedor", proveedorId)
                .param("usuario", usuarioId)
                .param("archivo", nombreArchivo)
                .param("leidos", leidos)
                .param("aceptados", aceptados)
                .param("rechazados", rechazados)
                .update();
        return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    /**
     * Guarda el detalle en bloques.
     *
     * Una sentencia por fila son veinte mil viajes; una sola sentencia
     * con veinte mil valores revienta el tope del paquete de MySQL. El
     * bloque es el término medio y el tamaño está puesto por lo que
     * cabe holgado en el paquete por omisión de cuatro megabytes.
     */
    public void insertarDetalles(Integer empresaId, Long cargaId, List<FilaDeCarga> filas) {
        final int TAMANO_BLOQUE = 500;
        for (int desde = 0; desde < filas.size(); desde += TAMANO_BLOQUE) {
            List<FilaDeCarga> bloque = filas.subList(
                    desde, Math.min(desde + TAMANO_BLOQUE, filas.size()));
            StringBuilder sentencia = new StringBuilder("""
                    INSERT INTO detalle_carga_precios
                           (empresa_id, carga_id, numero_fila, producto_id,
                            referencia_origen, descripcion_origen, costo,
                            costo_anterior, aceptado, motivo_rechazo)
                    VALUES """);
            List<Object> valores = new ArrayList<>();
            for (int i = 0; i < bloque.size(); i++) {
                sentencia.append(i == 0 ? "" : ", ")
                         .append("(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            }
            var llamada = jdbc.sql(sentencia.toString());
            for (FilaDeCarga f : bloque) {
                valores.add(empresaId);
                valores.add(cargaId);
                valores.add(f.numeroFila());
                valores.add(f.productoId());
                valores.add(f.referencia());
                valores.add(f.descripcion());
                valores.add(f.costo());
                valores.add(f.costoAnterior());
                valores.add(f.aceptado());
                valores.add(f.motivoRechazo());
            }
            llamada.params(valores).update();
        }
    }

    public Optional<CabeceraCarga> buscarCarga(Integer empresaId, Long cargaId) {
        return jdbc.sql("""
                        SELECT c.carga_id, c.proveedor_id, p.razon_social AS proveedor,
                               c.nombre_archivo, c.estado, c.fecha, c.fecha_aplicacion,
                               c.registros_leidos   AS leidos,
                               c.registros_aceptados AS aceptados,
                               c.registros_rechazados AS rechazados
                          FROM cargas_precios c
                          JOIN proveedores p
                            ON p.empresa_id = c.empresa_id
                           AND p.proveedor_id = c.proveedor_id
                         WHERE c.empresa_id = :empresa AND c.carga_id = :carga
                        """)
                .param("empresa", empresaId)
                .param("carga", cargaId)
                .query(CabeceraCarga.class)
                .optional();
    }

    /**
     * Las filas registradas de una carga.
     *
     * Los rechazos van primero: son lo que hay que mirar, y en una lista
     * de veinte mil referencias la vista previa del RF-24 se queda corta
     * si se llena de filas correctas.
     */
    public List<FilaDeCarga> detallesDe(Integer empresaId, Long cargaId, int limite) {
        return jdbc.sql("""
                        SELECT numero_fila, producto_id, referencia_origen AS referencia,
                               descripcion_origen AS descripcion, costo, costo_anterior,
                               aceptado, motivo_rechazo
                          FROM detalle_carga_precios
                         WHERE empresa_id = :empresa AND carga_id = :carga
                         ORDER BY aceptado ASC, numero_fila ASC
                         LIMIT :limite
                        """)
                .param("empresa", empresaId)
                .param("carga", cargaId)
                .param("limite", limite)
                .query(FilaDeCarga.class)
                .list();
    }

    public List<FilaDeCarga> aceptadosDe(Integer empresaId, Long cargaId) {
        return jdbc.sql("""
                        SELECT numero_fila, producto_id, referencia_origen AS referencia,
                               descripcion_origen AS descripcion, costo, costo_anterior,
                               aceptado, motivo_rechazo
                          FROM detalle_carga_precios
                         WHERE empresa_id = :empresa AND carga_id = :carga
                           AND aceptado = TRUE
                         ORDER BY numero_fila ASC
                        """)
                .param("empresa", empresaId)
                .param("carga", cargaId)
                .query(FilaDeCarga.class)
                .list();
    }

    public BigDecimal costoActual(Integer empresaId, Integer productoId) {
        return jdbc.sql("""
                        SELECT costo FROM productos
                         WHERE empresa_id = :empresa AND producto_id = :producto
                        """)
                .param("empresa", empresaId)
                .param("producto", productoId)
                .query(BigDecimal.class)
                .single();
    }
}

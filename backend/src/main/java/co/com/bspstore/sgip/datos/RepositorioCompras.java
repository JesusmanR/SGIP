package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.OrdenDeCompra;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Órdenes de compra, sus líneas y las recepciones (RF-22, RF-23).
 *
 * Todas las sentencias acotan por empresa, y desde la V24 pueden: esas
 * tablas llevan `empresa_id` y sus claves foráneas lo incluyen, de modo
 * que el filtro no es la única barrera sino la segunda.
 *
 * Como en {@link RepositorioInventario}, el motivo de no usar JPA es el
 * bloqueo: registrar una recepción exige leer lo ya recibido de cada
 * línea y sumarle lo que llega, y entre esas dos cosas no puede colarse
 * otra entrega. Dos recepciones simultáneas de la misma orden leerían el
 * mismo acumulado y las dos cabrían en lo pedido, cada una por su
 * cuenta; juntas lo pasarían.
 */
@Repository
public class RepositorioCompras {

    private final JdbcClient jdbc;

    public RepositorioCompras(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------ órdenes

    public boolean existeConsecutivo(Integer empresaId, String consecutivo) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM ordenes_compra
                         WHERE empresa_id = :empresa AND consecutivo = :consecutivo
                        """)
                .param("empresa", empresaId)
                .param("consecutivo", consecutivo)
                .query(Integer.class)
                .single() > 0;
    }

    public Long insertarOrden(Integer empresaId, Integer proveedorId, Integer usuarioId,
                              String consecutivo, String observacion) {
        jdbc.sql("""
                        INSERT INTO ordenes_compra
                               (empresa_id, proveedor_id, usuario_id, consecutivo,
                                estado, observacion)
                        VALUES (:empresa, :proveedor, :usuario, :consecutivo,
                                'ABIERTA', :observacion)
                        """)
                .param("empresa", empresaId)
                .param("proveedor", proveedorId)
                .param("usuario", usuarioId)
                .param("consecutivo", consecutivo)
                .param("observacion", observacion)
                .update();
        return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    public void insertarItem(Integer empresaId, Long ordenId, Integer productoId,
                             int cantidad, BigDecimal costoUnitario) {
        jdbc.sql("""
                        INSERT INTO orden_compra_items
                               (orden_id, empresa_id, producto_id, cantidad, costo_unitario)
                        VALUES (:orden, :empresa, :producto, :cantidad, :costo)
                        """)
                .param("orden", ordenId)
                .param("empresa", empresaId)
                .param("producto", productoId)
                .param("cantidad", cantidad)
                .param("costo", costoUnitario)
                .update();
    }

    public void fijarEstado(Integer empresaId, Long ordenId, String estado) {
        jdbc.sql("""
                        UPDATE ordenes_compra SET estado = :estado
                         WHERE empresa_id = :empresa AND orden_id = :orden
                        """)
                .param("estado", estado)
                .param("empresa", empresaId)
                .param("orden", ordenId)
                .update();
    }

    /** El estado actual, con la orden bloqueada. Vacío si no es de esta empresa. */
    public Optional<String> bloquearOrden(Integer empresaId, Long ordenId) {
        return jdbc.sql("""
                        SELECT estado FROM ordenes_compra
                         WHERE empresa_id = :empresa AND orden_id = :orden
                           FOR UPDATE
                        """)
                .param("empresa", empresaId)
                .param("orden", ordenId)
                .query(String.class)
                .optional();
    }

    private static final String CABECERA = """
            SELECT o.orden_id, o.consecutivo, o.proveedor_id,
                   pr.razon_social AS proveedor,
                   o.usuario_id, u.nombre AS usuario,
                   o.estado, o.fecha, o.observacion,
                   (SELECT COALESCE(SUM(i.cantidad * i.costo_unitario), 0)
                      FROM orden_compra_items i
                     WHERE i.empresa_id = o.empresa_id
                       AND i.orden_id   = o.orden_id) AS total
              FROM ordenes_compra o
              JOIN proveedores pr ON pr.proveedor_id = o.proveedor_id
                                 AND pr.empresa_id   = o.empresa_id
              JOIN usuarios    u  ON u.usuario_id    = o.usuario_id
                                 AND u.empresa_id    = o.empresa_id
             WHERE o.empresa_id = :empresa
            """;

    /**
     * La cabecera sin sus líneas. El servicio las pide aparte y las
     * junta, en lugar de traerlo todo con un enlace: con un enlace, una
     * orden de cinco líneas vuelve cinco veces repetida y hay que
     * deduplicarla en Java, que es más código y más fácil de equivocar
     * que dos consultas.
     */
    public Optional<Cabecera> buscarCabecera(Integer empresaId, Long ordenId) {
        return jdbc.sql(CABECERA + " AND o.orden_id = :orden")
                .param("empresa", empresaId)
                .param("orden", ordenId)
                .query(Cabecera.class)
                .optional();
    }

    public List<Cabecera> listarCabeceras(Integer empresaId, String estado, int limite) {
        return jdbc.sql(CABECERA + """
                           AND (:estado IS NULL OR o.estado = :estado)
                         ORDER BY o.fecha DESC, o.orden_id DESC
                         LIMIT :limite
                        """)
                .param("empresa", empresaId)
                .param("estado", estado)
                .param("limite", limite)
                .query(Cabecera.class)
                .list();
    }

    // ------------------------------------------------------------- líneas

    private static final String LINEAS = """
            SELECT i.item_id, i.producto_id, p.codigo, p.nombre AS producto,
                   i.cantidad, i.cantidad_recibida,
                   i.cantidad - i.cantidad_recibida AS pendiente,
                   i.costo_unitario,
                   i.cantidad * i.costo_unitario AS subtotal
              FROM orden_compra_items i
              JOIN productos p ON p.producto_id = i.producto_id
                              AND p.empresa_id  = i.empresa_id
             WHERE i.empresa_id = :empresa AND i.orden_id = :orden
             ORDER BY p.nombre
            """;

    public List<OrdenDeCompra.LineaDeOrden> lineasDe(Integer empresaId, Long ordenId) {
        return jdbc.sql(LINEAS)
                .param("empresa", empresaId)
                .param("orden", ordenId)
                .query(OrdenDeCompra.LineaDeOrden.class)
                .list();
    }

    /**
     * Las líneas con sus filas bloqueadas, para registrar una recepción.
     *
     * Sin el bloqueo, dos entregas simultáneas de la misma orden leen el
     * mismo `cantidad_recibida` y las dos comprueban que lo suyo cabe en
     * lo pedido. Por separado cada una tiene razón; juntas superan la
     * orden, y entonces `ck_oci_recibida` rechaza la segunda a mitad de
     * la transacción —lo cual está bien, porque la restricción está ahí
     * para eso— pero el usuario recibe un error de integridad en lugar de
     * «ya no cabe». Con el bloqueo, la segunda espera y lee el acumulado
     * de verdad.
     *
     * Devuelve las líneas sin los datos del producto: lo que hace falta
     * para decidir es el identificador, lo pedido y lo recibido.
     */
    public List<LineaBloqueada> bloquearLineas(Integer empresaId, Long ordenId) {
        return jdbc.sql("""
                        SELECT item_id, producto_id, cantidad, cantidad_recibida
                          FROM orden_compra_items
                         WHERE empresa_id = :empresa AND orden_id = :orden
                           FOR UPDATE
                        """)
                .param("empresa", empresaId)
                .param("orden", ordenId)
                .query(LineaBloqueada.class)
                .list();
    }

    public void sumarRecibido(Integer empresaId, Long itemId, int cantidad) {
        jdbc.sql("""
                        UPDATE orden_compra_items
                           SET cantidad_recibida = cantidad_recibida + :cantidad
                         WHERE empresa_id = :empresa AND item_id = :item
                        """)
                .param("cantidad", cantidad)
                .param("empresa", empresaId)
                .param("item", itemId)
                .update();
    }

    // -------------------------------------------------------- recepciones

    public Long insertarRecepcion(Integer empresaId, Long ordenId, Integer usuarioId,
                                  String observacion) {
        jdbc.sql("""
                        INSERT INTO recepciones (orden_id, empresa_id, usuario_id, observacion)
                        VALUES (:orden, :empresa, :usuario, :observacion)
                        """)
                .param("orden", ordenId)
                .param("empresa", empresaId)
                .param("usuario", usuarioId)
                .param("observacion", observacion)
                .update();
        return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    /** La cabecera tal como sale de la base, sin sus líneas. */
    public record Cabecera(
            Long ordenId,
            String consecutivo,
            Integer proveedorId,
            String proveedor,
            Integer usuarioId,
            String usuario,
            String estado,
            java.time.LocalDateTime fecha,
            String observacion,
            BigDecimal total
    ) {}

    /** Lo que hace falta de una línea para decidir si una entrega cabe. */
    public record LineaBloqueada(
            Long itemId,
            Integer productoId,
            Integer cantidad,
            Integer cantidadRecibida
    ) {

        public int pendiente() {
            return cantidad - cantidadRecibida;
        }

        public boolean completa() {
            return pendiente() == 0;
        }
    }
}

package co.com.bspstore.sgip.datos;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Los encargos (RF-42, RF-69): las líneas de venta con {@code cantidad_encargo > 0} que todavía no se
 * han entregado, y la marca de entrega a mano.
 *
 * <h2>El estado no se guarda</h2>
 *
 * Un encargo está «pendiente» mientras la existencia de su producto siga en negativo y «llegó» desde
 * que es cero o más. Se calcula al consultar: no hay trigger y {@code ventas.reposicion_pendiente} no
 * se actualiza (esa marca dice que la venta tuvo encargo, no cuál es su estado de hoy).
 *
 * Toda sentencia lleva {@code empresa_id} (ADR-0011), también las uniones. La lista es una sola
 * consulta con un tope y va por {@code ix_venitems_encargos}. Los datos personales (nombre y teléfono
 * del cliente) se devuelven a quien llama y no se escriben en ningún registro del servidor.
 */
@Repository
public class RepositorioEncargos {

    private final JdbcClient jdbc;

    public RepositorioEncargos(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Una fila de la lista; el estado y la antigüedad los calcula quien llama. */
    public record FilaDeEncargo(Long itemId, Long ventaId, String consecutivo, LocalDateTime fecha, String cliente,
                                String telefono, Integer productoId, String codigo, String producto, int cantidad,
                                int cantidadEncargo, LocalDate entregaEstimada, int existencia) {}

    /**
     * Los encargos sin entregar de una venta vigente (no anulada) y de una línea que no se devolvió por
     * completo (RF-68; la subconsulta repite el filtro por empresa) cuyo producto ya llegó ({@code llego}) o todavía
     * no. Del que prometió la fecha más cercana al más lejano: es lo que hay que perseguir primero.
     */
    public List<FilaDeEncargo> listar(Integer empresaId, boolean llego, int tope) {
        return jdbc.sql("""
                        SELECT i.item_id, v.venta_id, v.consecutivo, v.fecha, c.razon_social AS cliente,
                               c.telefono, p.producto_id, p.codigo, p.nombre AS producto, i.cantidad,
                               i.cantidad_encargo, i.entrega_estimada, p.existencia
                          FROM venta_items i
                          JOIN ventas v ON v.empresa_id = i.empresa_id AND v.venta_id = i.venta_id
                          JOIN clientes c ON c.empresa_id = v.empresa_id AND c.cliente_id = v.cliente_id
                          JOIN productos p ON p.empresa_id = i.empresa_id AND p.producto_id = i.producto_id
                         WHERE i.empresa_id = :empresa AND i.cantidad_encargo > 0
                           AND i.encargo_entregado_en IS NULL AND v.estado <> 'ANULADA'
                           AND i.cantidad > (SELECT COALESCE(SUM(di.cantidad), 0)
                                   FROM devolucion_items di
                                   JOIN devoluciones dv ON dv.empresa_id = di.empresa_id
                                                       AND dv.devolucion_id = di.devolucion_id
                                  WHERE dv.empresa_id = :empresa AND di.empresa_id = :empresa
                                    AND dv.venta_id = i.venta_id AND di.producto_id = i.producto_id)

                           AND (p.existencia >= 0) = :llego
                         ORDER BY i.entrega_estimada, i.item_id
                         LIMIT :tope
                        """)
                .param("empresa", empresaId)
                .param("llego", llego)
                .param("tope", tope)
                .query(FilaDeEncargo.class)
                .list();
    }

    /**
     * Marca la línea de encargo como entregada, <b>solo si es un encargo y todavía no lo estaba</b>: la
     * condición {@code encargo_entregado_en IS NULL} va en la propia actualización y es lo que decide quién
     * llegó primero cuando dos entregas coinciden (véase {@link RepositorioDomicilios#marcarEntregado}).
     */
    public int marcarEntregado(Integer empresaId, Long itemId, LocalDateTime cuando, Integer quien) {
        return jdbc.sql("""
                        UPDATE venta_items SET encargo_entregado_en = :cuando, encargo_entregado_por = :quien
                         WHERE empresa_id = :empresa AND item_id = :item AND cantidad_encargo > 0
                           AND encargo_entregado_en IS NULL
                        """)
                .param("cuando", cuando)
                .param("quien", quien)
                .param("empresa", empresaId)
                .param("item", itemId)
                .update();
    }

    /** Cómo está una línea de encargo: si ya se entregó y si su venta sigue vigente. Vacío si no es un encargo de la empresa. */
    public record EstadoDelEncargo(boolean entregado, boolean ventaVigente, boolean devuelto) {}

    public Optional<EstadoDelEncargo> estadoDelEncargo(Integer empresaId, Long itemId) {
        return jdbc.sql("""
                        SELECT i.encargo_entregado_en IS NOT NULL AS entregado, v.estado <> 'ANULADA' AS venta_vigente,
                               i.cantidad <= (SELECT COALESCE(SUM(di.cantidad), 0)
                                   FROM devolucion_items di
                                   JOIN devoluciones dv ON dv.empresa_id = di.empresa_id
                                                       AND dv.devolucion_id = di.devolucion_id
                                  WHERE dv.empresa_id = :empresa AND di.empresa_id = :empresa
                                    AND dv.venta_id = i.venta_id AND di.producto_id = i.producto_id)
 AS devuelto
                          FROM venta_items i
                          JOIN ventas v ON v.empresa_id = i.empresa_id AND v.venta_id = i.venta_id
                         WHERE i.empresa_id = :empresa AND i.item_id = :item AND i.cantidad_encargo > 0
                        """)
                .param("empresa", empresaId)
                .param("item", itemId)
                .query(EstadoDelEncargo.class)
                .optional();
    }
}

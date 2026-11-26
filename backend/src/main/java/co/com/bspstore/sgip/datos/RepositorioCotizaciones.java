package co.com.bspstore.sgip.datos;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Las cotizaciones (RF-37) en la base. Toda consulta lleva la empresa: una cotización de otra empresa no existe
 * para quien pregunta.
 *
 * {@code VENCIDA} nunca se escribe: la lectura la calcula con el día que recibe ({@code hoy}, el de Bogotá según
 * el reloj de la aplicación, no el {@code CURDATE()} del motor): una cotización {@code VIGENTE} con la fecha de
 * vencimiento anterior a ese día se lee como {@code VENCIDA}.
 *
 * Las líneas guardan {@code subtotal} y {@code valor_iva} calculados por {@code CalculoDeVenta}, los mismos de la
 * venta en que se convierta (V50).
 */
@Repository
public class RepositorioCotizaciones {

    private final JdbcClient jdbc;
    private final JdbcTemplate plantilla;

    public RepositorioCotizaciones(JdbcClient jdbc, JdbcTemplate plantilla) {
        this.jdbc = jdbc;
        this.plantilla = plantilla;
    }

    /** La cabecera por guardar. Se guarda siempre {@code VIGENTE}, sin descuento ni envío. */
    public record NuevaCotizacion(Integer empresaId, String consecutivo, Integer clienteId, Integer usuarioId,
                                  LocalDate fechaEmision, LocalDate fechaVencimiento, int vigenciaDias,
                                  BigDecimal subtotal, BigDecimal iva, String observaciones) {}

    /** Una línea por guardar, con el triple congelado y los importes que dio el cálculo de la venta. */
    public record NuevaLinea(Integer productoId, int cantidad, BigDecimal precioUnitario, BigDecimal tarifaIva,
                             String tratamientoIva, BigDecimal subtotal, BigDecimal valorIva) {}

    /** La cabecera leída, con el estado ya calculado para el día dado. */
    public record Cabecera(Long id, String numero, String estado, LocalDate fechaEmision, LocalDate fechaVencimiento,
                           int vigenciaDias, Integer clienteId, String clienteNombre, String clienteIdentificacion,
                           Integer usuarioId, String creadaPor, BigDecimal subtotal, BigDecimal iva,
                           BigDecimal total, String observaciones, Long ventaId, String motivoAnulacion) {}

    /** Una línea leída, con el nombre y el código del producto. */
    public record Linea(Integer productoId, String codigo, String nombre, int cantidad, BigDecimal precioUnitario,
                        BigDecimal tarifaIva, String tratamientoIva, BigDecimal subtotal, BigDecimal valorIva) {}

    /** Cuáles de esos productos existen en la empresa, estén activos o no. */
    public Set<Integer> productosDeLaEmpresa(Integer empresaId, Collection<Integer> ids) {
        return new HashSet<>(jdbc.sql("""
                        SELECT producto_id FROM productos
                         WHERE empresa_id = :empresa AND producto_id IN (:ids)
                        """)
                .param("empresa", empresaId)
                .param("ids", ids)
                .query(Integer.class)
                .list());
    }

    public Long insertarCabecera(NuevaCotizacion c) {
        KeyHolder llaves = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO cotizaciones (empresa_id, consecutivo, cliente_id, usuario_id, estado,
                                                  fecha_emision, vigencia_dias, fecha_vencimiento, subtotal,
                                                  descuento, iva, envio, observaciones)
                        VALUES (:empresa, :consecutivo, :cliente, :usuario, 'VIGENTE',
                                :emision, :vigencia, :vencimiento, :subtotal, 0, :iva, 0, :observaciones)
                        """)
                .param("empresa", c.empresaId())
                .param("consecutivo", c.consecutivo())
                .param("cliente", c.clienteId())
                .param("usuario", c.usuarioId())
                .param("emision", c.fechaEmision())
                .param("vigencia", c.vigenciaDias())
                .param("vencimiento", c.fechaVencimiento())
                .param("subtotal", c.subtotal())
                .param("iva", c.iva())
                .param("observaciones", c.observaciones())
                .update(llaves);
        return llaves.getKeyAs(Number.class).longValue();
    }

    private static final String INSERTAR_LINEA = """
            INSERT INTO cotizacion_items (cotizacion_id, empresa_id, producto_id, cantidad, precio_unitario,
                                          tarifa_iva, tratamiento_iva, subtotal, valor_iva)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /** Las líneas de la cotización, en un solo lote. */
    public void insertarLineas(Integer empresaId, Long cotizacionId, List<NuevaLinea> lineas) {
        List<Object[]> valores = new ArrayList<>(lineas.size());
        for (NuevaLinea l : lineas) {
            valores.add(new Object[]{cotizacionId, empresaId, l.productoId(), l.cantidad(), l.precioUnitario(),
                    l.tarifaIva(), l.tratamientoIva(), l.subtotal(), l.valorIva()});
        }
        plantilla.batchUpdate(INSERTAR_LINEA, valores);
    }

    /**
     * La cabecera de la cotización de la empresa, con su cliente y quien la creó, y su estado calculado para
     * {@code hoy}. Vacía si no existe o es de otra empresa.
     */
    public Optional<Cabecera> cabecera(Integer empresaId, Long id, LocalDate hoy) {
        return jdbc.sql("""
                        SELECT c.cotizacion_id AS id, c.consecutivo AS numero,
                               CASE WHEN c.estado = 'VIGENTE' AND c.fecha_vencimiento < :hoy
                                    THEN 'VENCIDA' ELSE c.estado END AS estado,
                               c.fecha_emision AS fechaEmision, c.fecha_vencimiento AS fechaVencimiento,
                               c.vigencia_dias AS vigenciaDias, c.cliente_id AS clienteId,
                               cl.razon_social AS clienteNombre, cl.nit AS clienteIdentificacion,
                               c.usuario_id AS usuarioId, u.nombre AS creadaPor,
                               c.subtotal, c.iva, c.total, c.observaciones,
                               (SELECT v.venta_id FROM ventas v
                                 WHERE v.empresa_id = c.empresa_id AND v.cotizacion_id = c.cotizacion_id) AS ventaId,
                               c.motivo_anulacion AS motivoAnulacion
                          FROM cotizaciones c
                          JOIN clientes cl ON cl.empresa_id = c.empresa_id AND cl.cliente_id = c.cliente_id
                          JOIN usuarios u ON u.empresa_id = c.empresa_id AND u.usuario_id = c.usuario_id
                         WHERE c.empresa_id = :empresa AND c.cotizacion_id = :id
                        """)
                .param("hoy", hoy)
                .param("empresa", empresaId)
                .param("id", id)
                .query(Cabecera.class)
                .optional();
    }

    /** Las líneas de la cotización, por producto. */
    public List<Linea> lineas(Integer empresaId, Long cotizacionId) {
        return jdbc.sql("""
                        SELECT i.producto_id AS productoId, p.codigo, p.nombre, i.cantidad,
                               i.precio_unitario AS precioUnitario, i.tarifa_iva AS tarifaIva,
                               i.tratamiento_iva AS tratamientoIva, i.subtotal, i.valor_iva AS valorIva
                          FROM cotizacion_items i
                          JOIN productos p ON p.empresa_id = i.empresa_id AND p.producto_id = i.producto_id
                         WHERE i.empresa_id = :empresa AND i.cotizacion_id = :id
                         ORDER BY i.producto_id
                        """)
                .param("empresa", empresaId)
                .param("id", cotizacionId)
                .query(Linea.class)
                .list();
    }
}

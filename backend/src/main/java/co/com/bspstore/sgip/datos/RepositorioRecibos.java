package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.modelo.TipoMedio;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Lo que el recibo de una venta lee (RF-70): tres consultas, las mismas con una línea que con doscientas. Solo
 * lee, y toda sentencia lleva {@code empresa_id} (ADR-0011), también las uniones: la venta de otra empresa es,
 * para quien pregunta, una venta que no existe. Nunca lee el costo ni la utilidad.
 */
@Repository
public class RepositorioRecibos {

    private final JdbcClient jdbc;

    public RepositorioRecibos(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * La venta con lo que la rodea: la empresa, si es responsable de IVA, el cajero, el cliente y, si es a
     * domicilio, los datos de la entrega. {@code responsableIva} es falso cuando la empresa no tiene fila en
     * {@code empresa_config} (V34).
     *
     * Los datos de la empresa ({@code empresa}, {@code nit}, {@code tipoDocumentoEmpresa},
     * {@code direccionEmpresa}, {@code telefonoEmpresa}) son la copia que la venta guardó (V48). La copia está
     * ausente si y solo si {@code ventas.empresa_nit} es nulo (una venta anterior a la V48): entonces salen todos
     * los datos actuales de {@code empresas}, y nunca una mezcla de los dos. {@code direccion}, {@code ciudad} y
     * {@code telefono} siguen siendo los del domicilio de entrega del cliente.
     */
    public record CabeceraDeRecibo(String empresa, String nit, boolean responsableIva, String consecutivo,
                                   LocalDateTime fecha, String cajero, String cliente, String tipoDocumento,
                                   String identificacion, BigDecimal subtotal, BigDecimal descuento, BigDecimal iva,
                                   BigDecimal total, String estadoPago, boolean contraEntrega, boolean domicilio,
                                   String direccion, String ciudad, String telefono, String recibe,
                                   String tipoDocumentoEmpresa, String direccionEmpresa, String telefonoEmpresa) {}

    /** Una línea con el nombre del producto; {@code total} ya lleva el descuento y el IVA. */
    public record LineaDeVenta(String nombre, int cantidad, BigDecimal precioUnitario, BigDecimal descuento,
                               BigDecimal iva, BigDecimal total, int cantidadEncargo, LocalDate entregaEstimada) {}

    /** Un pago con el nombre y la clase de su medio. */
    public record PagoDeVenta(String medio, TipoMedio tipo, BigDecimal monto, BigDecimal recibido,
                              String referencia) {}

    public Optional<CabeceraDeRecibo> cabecera(Integer empresaId, Long ventaId) {
        return jdbc.sql("""
                        SELECT CASE WHEN v.empresa_nit IS NULL THEN COALESCE(e.nombre_comercial, e.razon_social)
                                    ELSE v.empresa_nombre END AS empresa,
                               CASE WHEN v.empresa_nit IS NULL THEN e.nit ELSE v.empresa_nit END AS nit,
                               COALESCE(ec.responsable_iva, FALSE) AS responsable_iva,
                               v.consecutivo, v.fecha, u.nombre AS cajero, c.razon_social AS cliente,
                               c.tipo_documento, c.nit AS identificacion,
                               v.subtotal, v.descuento, v.iva, v.total, v.estado_pago,
                               v.pago_contra_entrega AS contra_entrega, v.tipo_entrega = 'DOMICILIO' AS domicilio,
                               d.direccion, d.ciudad, d.telefono, d.recibe,
                               CASE WHEN v.empresa_nit IS NULL THEN e.tipo_documento
                                    ELSE v.empresa_tipo_documento END AS tipo_documento_empresa,
                               CASE WHEN v.empresa_nit IS NULL THEN e.direccion
                                    ELSE v.empresa_direccion END AS direccion_empresa,
                               CASE WHEN v.empresa_nit IS NULL THEN e.telefono
                                    ELSE v.empresa_telefono END AS telefono_empresa
                          FROM ventas v
                          JOIN empresas e ON e.empresa_id = v.empresa_id
                          LEFT JOIN empresa_config ec ON ec.empresa_id = v.empresa_id
                          JOIN usuarios u ON u.empresa_id = v.empresa_id AND u.usuario_id = v.usuario_id
                          JOIN clientes c ON c.empresa_id = v.empresa_id AND c.cliente_id = v.cliente_id
                          LEFT JOIN domicilios_venta d ON d.empresa_id = v.empresa_id AND d.venta_id = v.venta_id
                         WHERE v.empresa_id = :empresa AND v.venta_id = :venta
                        """)
                .param("empresa", empresaId)
                .param("venta", ventaId)
                .query(CabeceraDeRecibo.class)
                .optional();
    }

    /** Las líneas en el orden en que se vendieron, con el nombre del producto, en una sola consulta. */
    public List<LineaDeVenta> lineas(Integer empresaId, Long ventaId) {
        return jdbc.sql("""
                        SELECT p.nombre, i.cantidad, i.precio_unitario, i.descuento, i.valor_iva AS iva,
                               i.subtotal - i.descuento + i.valor_iva AS total, i.cantidad_encargo,
                               i.entrega_estimada
                          FROM venta_items i
                          JOIN productos p ON p.empresa_id = i.empresa_id AND p.producto_id = i.producto_id
                         WHERE i.empresa_id = :empresa AND i.venta_id = :venta
                         ORDER BY i.item_id
                        """)
                .param("empresa", empresaId)
                .param("venta", ventaId)
                .query(LineaDeVenta.class)
                .list();
    }

    /** Los pagos en el orden en que se registraron, con el medio, en una sola consulta. */
    public List<PagoDeVenta> pagos(Integer empresaId, Long ventaId) {
        return jdbc.sql("""
                        SELECT m.nombre AS medio, m.tipo, p.monto, p.recibido, p.referencia
                          FROM pagos_venta p
                          JOIN medios_pago m ON m.empresa_id = p.empresa_id AND m.medio_id = p.medio_id
                         WHERE p.empresa_id = :empresa AND p.venta_id = :venta
                         ORDER BY p.pago_id
                        """)
                .param("empresa", empresaId)
                .param("venta", ventaId)
                .query(PagoDeVenta.class)
                .list();
    }
}

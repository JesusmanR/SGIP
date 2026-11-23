package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.Reembolso;
import co.com.bspstore.sgip.api.dto.VentaConsultada;
import co.com.bspstore.sgip.logica.devolucion.CalculoDeDevolucion.LineaVendida;
import co.com.bspstore.sgip.logica.devolucion.CalculoDeDevolucion.UnidadesDevueltas;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Lo que la devolución y el saldo a favor escriben y leen (RF-68): {@code devoluciones},
 * {@code devolucion_items}, {@code reembolsos}, {@code saldos_cliente} y {@code movimientos_saldo},
 * más las lecturas de la venta que se devuelve.
 *
 * Toda sentencia lleva {@code empresa_id} (ADR-0011), también las uniones y las subconsultas: la venta de
 * otra empresa es, para quien pregunta, una venta que no existe. Las escrituras de varias filas (ítems y
 * reembolsos) van por lotes, de modo que las consultas de una devolución no dependen de cuántas líneas ni
 * cuántos reembolsos tenga. Ninguna sentencia lleva datos concatenados.
 *
 * <h2>Los bloqueos que toma</h2>
 *
 * En el orden global de la caja (ADR-0030), después del turno: la <b>venta</b> ({@link #bloquearVenta}),
 * los productos (los bloquea {@code RepositorioVentas}) y el <b>saldo del cliente</b>
 * ({@link #bloquearSaldo} y {@link #abonarSaldo}). El consecutivo y el kárdex son de sus repositorios.
 */
@Repository
public class RepositorioDevoluciones {

    private final JdbcClient jdbc;
    private final JdbcTemplate plantilla;

    public RepositorioDevoluciones(JdbcClient jdbc, JdbcTemplate plantilla) {
        this.jdbc = jdbc;
        this.plantilla = plantilla;
    }

    // ------------------------------------------------------------ la venta

    /** Lo que la devolución necesita saber de la venta, ya bloqueada. */
    public record VentaADevolver(Long ventaId, Integer clienteId, BigDecimal total, String estado, String estadoPago) {}

    /**
     * La venta con su fila tomada {@code FOR UPDATE} por clave primaria hasta el fin de la transacción: es
     * lo que pone en fila a dos devoluciones de la misma venta (y a una devolución con el cobro contra
     * entrega de esa venta). Va después del turno y antes de los productos. {@code FORCE INDEX (PRIMARY)}
     * como el bloqueo de los productos y de la venta del cobro: sin él el optimizador podría recorrer otro
     * índice y bloquear en otro orden. Vacío si la venta no es de la empresa.
     */
    public Optional<VentaADevolver> bloquearVenta(Integer empresaId, Long ventaId) {
        return jdbc.sql("""
                        SELECT venta_id, cliente_id, total, estado, estado_pago
                          FROM ventas FORCE INDEX (PRIMARY)
                         WHERE venta_id = :venta AND empresa_id = :empresa
                           FOR UPDATE
                        """)
                .param("venta", ventaId)
                .param("empresa", empresaId)
                .query(VentaADevolver.class)
                .optional();
    }

    /**
     * Las líneas de la venta con lo que ya se devolvió de cada una, en <b>una sola consulta</b>. Tiene que
     * leerse con la venta ya bloqueada: la transacción corre en READ COMMITTED y así ve la devolución que la
     * hermana confirmó mientras esperaba el bloqueo. El total de la línea es el ya descontado y con IVA.
     */
    public List<LineaVendida> lineasDeLaVenta(Integer empresaId, Long ventaId) {
        return jdbc.sql("""
                        SELECT i.producto_id, i.cantidad,
                               i.subtotal - i.descuento + i.valor_iva AS total,
                               COALESCE(d.cantidad, 0) AS ya_devueltas,
                               COALESCE(d.valor, 0) AS ya_devuelto
                          FROM venta_items i
                          LEFT JOIN (SELECT di.producto_id, SUM(di.cantidad) AS cantidad, SUM(di.valor) AS valor
                                       FROM devolucion_items di
                                       JOIN devoluciones dv ON dv.empresa_id = di.empresa_id
                                                           AND dv.devolucion_id = di.devolucion_id
                                      WHERE dv.empresa_id = :empresa AND dv.venta_id = :venta
                                        AND di.empresa_id = :empresa
                                      GROUP BY di.producto_id) d ON d.producto_id = i.producto_id
                         WHERE i.empresa_id = :empresa AND i.venta_id = :venta
                         ORDER BY i.producto_id
                        """)
                .param("empresa", empresaId)
                .param("venta", ventaId)
                .query((fila, n) -> new LineaVendida(fila.getInt("producto_id"), fila.getInt("cantidad"),
                        fila.getBigDecimal("total"), fila.getInt("ya_devueltas"), fila.getBigDecimal("ya_devuelto")))
                .list();
    }

    /** Deja la venta en el estado que le toca tras la devolución. Quien llama tiene su fila bloqueada. */
    public void actualizarVenta(Integer empresaId, Long ventaId, String estado, String estadoPago) {
        int filas = jdbc.sql("""
                        UPDATE ventas SET estado = :estado, estado_pago = :estadoPago
                         WHERE empresa_id = :empresa AND venta_id = :venta
                        """)
                .param("estado", estado)
                .param("estadoPago", estadoPago)
                .param("empresa", empresaId)
                .param("venta", ventaId)
                .update();
        if (filas != 1) {
            throw new IllegalStateException("La venta " + ventaId + " desapareció dentro de su propia transacción.");
        }
    }

    // ------------------------------------------------------------ lo que se escribe

    /** La clave de idempotencia que el motor informa cuando dos devoluciones chocan en ella. */
    public static final String CLAVE_UNICA_DE_IDEMPOTENCIA = "uq_devoluciones_idempotencia";

    /**
     * Lo que se guarda de una devolución nueva: además de lo suyo, la clave de idempotencia, la huella de la
     * carga y lo que se respondió (el estado en que quedó la venta y lo que quedó por cobrar), para que el
     * reintento reciba la misma respuesta.
     */
    public record NuevaDevolucion(Integer empresaId, String consecutivo, Long ventaId, String motivo,
                                  Integer autorizadoPor, Integer registradoPor, Long cierreId, LocalDateTime creadoEn,
                                  String clave, String huella, String estadoResultante, BigDecimal saldoPorCobrar) {}

    /**
     * Inserta la devolución y devuelve su identificador. {@code creado_en} lo pone la aplicación. Una clave
     * de idempotencia ya usada lanza {@link org.springframework.dao.DuplicateKeyException}; quien llama la
     * distingue de otra clave única por {@link #CLAVE_UNICA_DE_IDEMPOTENCIA}.
     */
    public Long insertarDevolucion(NuevaDevolucion d) {
        KeyHolder llaves = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO devoluciones (empresa_id, consecutivo, venta_id, motivo, autorizado_por,
                                                  registrado_por, cierre_id, creado_en, clave_idempotencia,
                                                  huella_carga, estado_resultante, saldo_por_cobrar)
                        VALUES (:empresa, :consecutivo, :venta, :motivo, :autorizadoPor, :registradoPor, :cierre,
                                :creadoEn, :clave, :huella, :estado, :saldoPorCobrar)
                        """)
                .param("clave", d.clave())
                .param("huella", d.huella())
                .param("estado", d.estadoResultante())
                .param("saldoPorCobrar", d.saldoPorCobrar())
                .param("empresa", d.empresaId())
                .param("consecutivo", d.consecutivo())
                .param("venta", d.ventaId())
                .param("motivo", d.motivo())
                .param("autorizadoPor", d.autorizadoPor())
                .param("registradoPor", d.registradoPor())
                .param("cierre", d.cierreId())
                .param("creadoEn", d.creadoEn())
                .update(llaves);
        Number clave = llaves.getKey();
        if (clave == null) {
            throw new IllegalStateException("El motor no devolvió el identificador de la devolución recién registrada.");
        }
        return clave.longValue();
    }

    // ------------------------------------------------------------ el reintento

    /** Una devolución ya registrada con esa clave: lo que se respondió entonces. */
    public record DevolucionPorClave(Long devolucionId, String huella, String consecutivo, String estadoResultante,
                                     BigDecimal saldoPorCobrar, BigDecimal valorDevuelto, BigDecimal reembolsado) {}

    /**
     * La devolución de esa clave de idempotencia, si la hay, con lo devuelto y lo reembolsado en la misma
     * consulta. No bloquea: quien llama tiene la venta bloqueada y, en READ COMMITTED, lee lo último que se
     * confirmó. Los dos totales son subconsultas que repiten el filtro por empresa.
     */
    public Optional<DevolucionPorClave> buscarPorClave(Integer empresaId, String clave) {
        return jdbc.sql("""
                        SELECT d.devolucion_id, d.huella_carga AS huella, d.consecutivo, d.estado_resultante,
                               d.saldo_por_cobrar,
                               COALESCE((SELECT SUM(di.valor) FROM devolucion_items di
                                          WHERE di.empresa_id = :empresa AND di.devolucion_id = d.devolucion_id), 0)
                                   AS valor_devuelto,
                               COALESCE((SELECT SUM(r.monto) FROM reembolsos r
                                          WHERE r.empresa_id = :empresa AND r.devolucion_id = d.devolucion_id), 0)
                                   AS reembolsado
                          FROM devoluciones d
                         WHERE d.empresa_id = :empresa AND d.clave_idempotencia = :clave
                        """)
                .param("empresa", empresaId)
                .param("clave", clave)
                .query(DevolucionPorClave.class)
                .optional();
    }

    /** Los reembolsos de una devolución, en el orden en que se guardaron. */
    public List<Reembolso> reembolsosDe(Integer empresaId, Long devolucionId) {
        return jdbc.sql("""
                        SELECT medio_id, monto, referencia FROM reembolsos
                         WHERE empresa_id = :empresa AND devolucion_id = :devolucion
                         ORDER BY reembolso_id
                        """)
                .param("empresa", empresaId)
                .param("devolucion", devolucionId)
                .query(Reembolso.class)
                .list();
    }

    // ------------------------------------------------------------ lo que se pagó y se reembolsó sin saldo

    /**
     * Lo pagado por la venta y lo ya reembolsado de ella por medios que NO son el saldo a favor (RF-68): lo
     * que el cliente pagó con saldo solo vuelve como saldo, así que lo que sale por los demás medios no
     * puede pasar de lo que entró por ellos. Una sola consulta, con la venta ya bloqueada.
     */
    public record DineroSinSaldo(BigDecimal pagado, BigDecimal reembolsado) {}

    public DineroSinSaldo dineroSinSaldo(Integer empresaId, Long ventaId) {
        return jdbc.sql("""
                        SELECT COALESCE((SELECT SUM(p.monto)
                                           FROM pagos_venta p
                                           JOIN medios_pago m ON m.empresa_id = p.empresa_id AND m.medio_id = p.medio_id
                                          WHERE p.empresa_id = :empresa AND p.venta_id = :venta
                                            AND m.tipo <> 'SALDO_A_FAVOR'), 0) AS pagado,
                               COALESCE((SELECT SUM(r.monto)
                                           FROM reembolsos r
                                           JOIN devoluciones d ON d.empresa_id = r.empresa_id
                                                              AND d.devolucion_id = r.devolucion_id
                                           JOIN medios_pago m ON m.empresa_id = r.empresa_id AND m.medio_id = r.medio_id
                                          WHERE d.empresa_id = :empresa AND d.venta_id = :venta
                                            AND m.tipo <> 'SALDO_A_FAVOR'), 0) AS reembolsado
                        """)
                .param("empresa", empresaId)
                .param("venta", ventaId)
                .query(DineroSinSaldo.class)
                .single();
    }

    // ------------------------------------------------------------ consultar una venta

    /** La cabecera de la venta consultada, con su cliente y lo reembolsado sin saldo. */
    public record CabeceraDeVenta(Long ventaId, String consecutivo, String estado, String estadoPago, BigDecimal total,
                                  Integer clienteId, String cliente, boolean consumidorFinal,
                                  BigDecimal reembolsadoSinSaldo) {}

    /** La venta de la empresa con ese consecutivo (el de otra empresa no existe para quien pregunta). */
    public Optional<CabeceraDeVenta> cabeceraPorConsecutivo(Integer empresaId, String consecutivo) {
        return jdbc.sql("""
                        SELECT v.venta_id, v.consecutivo, v.estado, v.estado_pago, v.total, v.cliente_id,
                               c.razon_social AS cliente, c.consumidor_final,
                               COALESCE((SELECT SUM(r.monto)
                                           FROM reembolsos r
                                           JOIN devoluciones d ON d.empresa_id = r.empresa_id
                                                              AND d.devolucion_id = r.devolucion_id
                                           JOIN medios_pago m ON m.empresa_id = r.empresa_id AND m.medio_id = r.medio_id
                                          WHERE d.empresa_id = :empresa AND d.venta_id = v.venta_id
                                            AND m.tipo <> 'SALDO_A_FAVOR'), 0) AS reembolsado_sin_saldo
                          FROM ventas v
                          JOIN clientes c ON c.empresa_id = v.empresa_id AND c.cliente_id = v.cliente_id
                         WHERE v.empresa_id = :empresa AND v.consecutivo = :consecutivo
                        """)
                .param("empresa", empresaId)
                .param("consecutivo", consecutivo)
                .query(CabeceraDeVenta.class)
                .optional();
    }

    /** Las líneas de la venta con lo devuelto de cada una, en una sola consulta. Nunca el costo ni la utilidad. */
    public List<VentaConsultada.Linea> lineasConsultadas(Integer empresaId, Long ventaId) {
        return jdbc.sql("""
                        SELECT i.producto_id, p.nombre, i.cantidad,
                               COALESCE(d.cantidad, 0) AS cantidad_devuelta,
                               i.subtotal - i.descuento + i.valor_iva AS total,
                               COALESCE(d.valor, 0) AS valor_devuelto
                          FROM venta_items i
                          JOIN productos p ON p.empresa_id = i.empresa_id AND p.producto_id = i.producto_id
                          LEFT JOIN (SELECT di.producto_id, SUM(di.cantidad) AS cantidad, SUM(di.valor) AS valor
                                       FROM devolucion_items di
                                       JOIN devoluciones dv ON dv.empresa_id = di.empresa_id
                                                           AND dv.devolucion_id = di.devolucion_id
                                      WHERE dv.empresa_id = :empresa AND dv.venta_id = :venta
                                        AND di.empresa_id = :empresa
                                      GROUP BY di.producto_id) d ON d.producto_id = i.producto_id
                         WHERE i.empresa_id = :empresa AND i.venta_id = :venta
                         ORDER BY i.producto_id
                        """)
                .param("empresa", empresaId)
                .param("venta", ventaId)
                .query(VentaConsultada.Linea.class)
                .list();
    }

    /** Los pagos de la venta con el nombre y la clase de su medio. */
    public List<VentaConsultada.Pago> pagosConsultados(Integer empresaId, Long ventaId) {
        return jdbc.sql("""
                        SELECT p.medio_id, m.nombre AS medio, m.tipo, p.monto
                          FROM pagos_venta p
                          JOIN medios_pago m ON m.empresa_id = p.empresa_id AND m.medio_id = p.medio_id
                         WHERE p.empresa_id = :empresa AND p.venta_id = :venta
                         ORDER BY p.pago_id
                        """)
                .param("empresa", empresaId)
                .param("venta", ventaId)
                .query(VentaConsultada.Pago.class)
                .list();
    }

    private static final String INSERTAR_ITEM = """
            INSERT INTO devolucion_items (devolucion_id, empresa_id, producto_id, cantidad, valor)
            VALUES (?, ?, ?, ?, ?)
            """;

    /** Lo devuelto de cada producto, en un solo lote. */
    public void insertarItems(Integer empresaId, Long devolucionId, List<UnidadesDevueltas> unidades) {
        List<Object[]> valores = new ArrayList<>(unidades.size());
        for (UnidadesDevueltas u : unidades) {
            valores.add(new Object[]{devolucionId, empresaId, u.productoId(), u.cantidad(), u.valor()});
        }
        plantilla.batchUpdate(INSERTAR_ITEM, valores);
    }

    private static final String INSERTAR_REEMBOLSO = """
            INSERT INTO reembolsos (devolucion_id, empresa_id, medio_id, monto, referencia)
            VALUES (?, ?, ?, ?, ?)
            """;

    /** Cómo se devolvió el dinero, en un solo lote. */
    public void insertarReembolsos(Integer empresaId, Long devolucionId, List<Reembolso> reembolsos) {
        List<Object[]> valores = new ArrayList<>(reembolsos.size());
        for (Reembolso r : reembolsos) {
            valores.add(new Object[]{devolucionId, empresaId, r.medioId(), r.monto(), r.referencia()});
        }
        plantilla.batchUpdate(INSERTAR_REEMBOLSO, valores);
    }

    // ------------------------------------------------------------ el saldo a favor

    /**
     * El saldo del cliente con su fila tomada {@code FOR UPDATE} hasta el fin de la transacción. Sin fila
     * (el cliente nunca recibió un abono) el saldo es cero y no hay nada que bloquear: quien lo gasta
     * recibe un 400, y quien lo abona usa {@link #abonarSaldo}, que toma el bloqueo al escribir. Va después
     * de los productos y antes del consecutivo.
     */
    public Optional<BigDecimal> bloquearSaldo(Integer empresaId, Integer clienteId) {
        return jdbc.sql("""
                        SELECT saldo FROM saldos_cliente
                         WHERE empresa_id = :empresa AND cliente_id = :cliente
                           FOR UPDATE
                        """)
                .param("empresa", empresaId)
                .param("cliente", clienteId)
                .query(BigDecimal.class)
                .optional();
    }

    /**
     * Suma {@code monto} al saldo del cliente y deja tomada su fila. Es un solo {@code INSERT ... ON
     * DUPLICATE KEY UPDATE}: si la fila no existe la crea y si existe pide directamente el bloqueo
     * EXCLUSIVO y espera. Un {@code INSERT IGNORE} seguido de una lectura toma en ese caso un bloqueo
     * compartido, y con varias transacciones a la vez las que esperan lo tienen y después piden el
     * exclusivo: un ciclo (véase {@code RepositorioConsecutivos}).
     */
    public void abonarSaldo(Integer empresaId, Integer clienteId, BigDecimal monto) {
        jdbc.sql("""
                        INSERT INTO saldos_cliente (empresa_id, cliente_id, saldo)
                        VALUES (:empresa, :cliente, :monto)
                        ON DUPLICATE KEY UPDATE saldo = saldo + :monto
                        """)
                .param("empresa", empresaId)
                .param("cliente", clienteId)
                .param("monto", monto)
                .update();
    }

    /**
     * Resta {@code monto} al saldo, con la fila ya bloqueada por {@link #bloquearSaldo}. La condición
     * {@code saldo >= monto} es una red de seguridad (la columna tampoco admite un negativo): si no cambia
     * ninguna fila, algo se saltó el bloqueo y es un error de programación, no un dato del usuario.
     */
    public void descontarSaldo(Integer empresaId, Integer clienteId, BigDecimal monto) {
        int filas = jdbc.sql("""
                        UPDATE saldos_cliente SET saldo = saldo - :monto
                         WHERE empresa_id = :empresa AND cliente_id = :cliente AND saldo >= :monto
                        """)
                .param("monto", monto)
                .param("empresa", empresaId)
                .param("cliente", clienteId)
                .update();
        if (filas != 1) {
            throw new IllegalStateException("El saldo del cliente no alcanzaba después de comprobarlo con la fila bloqueada.");
        }
    }

    /** El abono de una devolución al saldo del cliente. */
    public void insertarAbono(Integer empresaId, Integer clienteId, BigDecimal monto, Long devolucionId,
                              LocalDateTime creadoEn) {
        insertarMovimiento(empresaId, clienteId, "ABONO", monto, devolucionId, null, creadoEn);
    }

    /** El uso del saldo en una venta. */
    public void insertarUso(Integer empresaId, Integer clienteId, BigDecimal monto, Long ventaId,
                            LocalDateTime creadoEn) {
        insertarMovimiento(empresaId, clienteId, "USO", monto, null, ventaId, creadoEn);
    }

    private void insertarMovimiento(Integer empresaId, Integer clienteId, String tipo, BigDecimal monto,
                                    Long devolucionId, Long ventaId, LocalDateTime creadoEn) {
        jdbc.sql("""
                        INSERT INTO movimientos_saldo (empresa_id, cliente_id, tipo, monto, devolucion_id, venta_id,
                                                       creado_en)
                        VALUES (:empresa, :cliente, :tipo, :monto, :devolucion, :venta, :creadoEn)
                        """)
                .param("empresa", empresaId)
                .param("cliente", clienteId)
                .param("tipo", tipo)
                .param("monto", monto)
                .param("devolucion", devolucionId)
                .param("venta", ventaId)
                .param("creadoEn", creadoEn)
                .update();
    }

    /** El saldo del cliente, sin bloquear nada: para consultarlo. Cero si nunca recibió un abono. */
    public BigDecimal saldoDe(Integer empresaId, Integer clienteId) {
        return jdbc.sql("""
                        SELECT saldo FROM saldos_cliente
                         WHERE empresa_id = :empresa AND cliente_id = :cliente
                        """)
                .param("empresa", empresaId)
                .param("cliente", clienteId)
                .query(BigDecimal.class)
                .optional()
                .orElse(BigDecimal.ZERO);
    }

    /** El medio de sistema «Saldo a favor» de la empresa: el que la pantalla usa para pagar con saldo. */
    public Optional<Integer> medioDeSaldo(Integer empresaId) {
        return jdbc.sql("""
                        SELECT medio_id FROM medios_pago
                         WHERE empresa_id = :empresa AND tipo = 'SALDO_A_FAVOR'
                         ORDER BY medio_id
                         LIMIT 1
                        """)
                .param("empresa", empresaId)
                .query(Integer.class)
                .optional();
    }
}

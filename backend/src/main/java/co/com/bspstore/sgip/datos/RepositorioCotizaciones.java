package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.CotizacionEnLista;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

    /** Una línea leída, con el nombre y el código del producto y si el producto sigue activo. */
    public record Linea(Integer productoId, String codigo, String nombre, boolean activo, int cantidad,
                        BigDecimal precioUnitario, BigDecimal tarifaIva, String tratamientoIva, BigDecimal subtotal,
                        BigDecimal valorIva) {}

    /**
     * Lo que pide la lista: el estado (uno de {@code VIGENTE}, {@code VENCIDA}, {@code CONVERTIDA},
     * {@code ANULADA}, o nulo para todos) y la búsqueda ya preparada: el número exacto con que se escribió (si lo
     * parece) y los patrones de {@code LIKE} para la identificación y el nombre del cliente. Sin búsqueda, los
     * tres van nulos.
     */
    public record Filtro(String estado, String numero, String prefijo, String contiene) {}

    /** El estado que ve quien lee: {@code VENCIDA} se calcula, no se guarda. */
    private static final String ESTADO_CALCULADO = """
            CASE WHEN c.estado = 'VIGENTE' AND c.fecha_vencimiento < :hoy THEN 'VENCIDA' ELSE c.estado END""";

    /**
     * De dónde salen las filas y qué las filtra, siempre con la empresa. Es una sola sentencia fija, con los
     * filtros como parámetros nulos cuando no se piden (como la lista de clientes): nada de lo que escribe quien
     * consulta entra en el texto de la sentencia. El orden {@code cotizacion_id DESC} sale de
     * {@code uq_cotizaciones_empresa}; qué índice conviene con muchas cotizaciones lo decide el motor y se mide
     * con {@code EXPLAIN} sobre datos reales (hoy no hay una medida que justifique más).
     */
    private static final String DESDE_Y_FILTRO = """
              FROM cotizaciones c
              JOIN clientes cl ON cl.empresa_id = c.empresa_id AND cl.cliente_id = c.cliente_id
             WHERE c.empresa_id = :empresa
               AND (:estado IS NULL
                    OR (:estado = 'VIGENTE' AND c.estado = 'VIGENTE' AND c.fecha_vencimiento >= :hoy)
                    OR (:estado = 'VENCIDA' AND c.estado = 'VIGENTE' AND c.fecha_vencimiento < :hoy)
                    OR (:estado IN ('CONVERTIDA', 'ANULADA') AND c.estado = :estado))
               AND (:prefijo IS NULL
                    OR c.consecutivo = :numero
                    OR cl.nit LIKE :prefijo
                    OR cl.razon_social LIKE :contiene)
            """;

    /**
     * Una página de las cotizaciones de la empresa, la más reciente primero ({@code cotizacion_id DESC} sobre
     * {@code uq_cotizaciones_empresa}), con su estado calculado para {@code hoy}. Una sola consulta, con el cliente
     * unido, sea cual sea el tamaño de la página.
     */
    public List<CotizacionEnLista> listar(Integer empresaId, LocalDate hoy, Filtro filtro, int limite,
                                          long desplazamiento) {
        return jdbc.sql("""
                        SELECT c.cotizacion_id AS id, c.consecutivo AS numero, c.fecha_emision AS fechaEmision,
                               cl.razon_social AS clienteNombre, c.total, c.fecha_vencimiento AS fechaVencimiento,
                               """ + ESTADO_CALCULADO + """
                         AS estado
                        """ + DESDE_Y_FILTRO + """
                         ORDER BY c.cotizacion_id DESC
                         LIMIT :limite OFFSET :desplazamiento
                        """)
                .param("empresa", empresaId)
                .param("hoy", hoy)
                .param("estado", filtro.estado())
                .param("numero", filtro.numero())
                .param("prefijo", filtro.prefijo())
                .param("contiene", filtro.contiene())
                .param("limite", limite)
                .param("desplazamiento", desplazamiento)
                .query(CotizacionEnLista.class)
                .list();
    }

    /** Cuántas cotizaciones de la empresa cumplen el mismo filtro que {@link #listar}. */
    public long contar(Integer empresaId, LocalDate hoy, Filtro filtro) {
        return jdbc.sql("""
                        SELECT COUNT(*)
                        """ + DESDE_Y_FILTRO)
                .param("empresa", empresaId)
                .param("hoy", hoy)
                .param("estado", filtro.estado())
                .param("numero", filtro.numero())
                .param("prefijo", filtro.prefijo())
                .param("contiene", filtro.contiene())
                .query(Long.class)
                .single();
    }

    /** El identificador de la cotización de la empresa con ese número exacto ({@code COT-00000012}). */
    public Optional<Long> idPorNumero(Integer empresaId, String numero) {
        return jdbc.sql("""
                        SELECT cotizacion_id FROM cotizaciones
                         WHERE empresa_id = :empresa AND consecutivo = :numero
                        """)
                .param("empresa", empresaId)
                .param("numero", numero)
                .query(Long.class)
                .optional();
    }

    /** Lo mínimo de una cotización tomada para anularla: su estado guardado y quién la creó. */
    public record Tomada(String estado, Integer usuarioId) {}

    /**
     * Toma la fila de la cotización de la empresa con {@code FOR UPDATE}: quien la anula (o la convierte en
     * venta) espera a que el otro confirme y ve el estado ya confirmado. Vacía si no existe o es de otra empresa.
     * Solo vale dentro de una transacción.
     */
    public Optional<Tomada> tomar(Integer empresaId, Long id) {
        return jdbc.sql("""
                        SELECT estado, usuario_id AS usuarioId FROM cotizaciones
                         WHERE empresa_id = :empresa AND cotizacion_id = :id
                           FOR UPDATE
                        """)
                .param("empresa", empresaId)
                .param("id", id)
                .query(Tomada.class)
                .optional();
    }

    /** Deja la cotización anulada, con quién, cuándo y por qué. Ya se tomó con {@link #tomar} y era vigente. */
    public void anular(Integer empresaId, Long id, Integer usuarioId, LocalDateTime cuando, String motivo) {
        jdbc.sql("""
                        UPDATE cotizaciones
                           SET estado = 'ANULADA', anulada_por = :usuario, anulada_en = :cuando,
                               motivo_anulacion = :motivo
                         WHERE empresa_id = :empresa AND cotizacion_id = :id
                        """)
                .param("usuario", usuarioId)
                .param("cuando", cuando)
                .param("motivo", motivo)
                .param("empresa", empresaId)
                .param("id", id)
                .update();
    }

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
                        SELECT i.producto_id AS productoId, p.codigo, p.nombre, p.activo, i.cantidad,
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

package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.MedioDePago;
import co.com.bspstore.sgip.api.dto.VentaRegistrada;
import co.com.bspstore.sgip.logica.venta.LineaCalculada;
import co.com.bspstore.sgip.logica.venta.TipoDescuento;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Lo que la venta del mostrador escribe y lee (RF-41): {@code ventas}, {@code venta_items} y
 * {@code pagos_venta}, más las lecturas que la venta necesita de {@code productos},
 * {@code v_precios_producto} y {@code medios_pago}.
 *
 * <h2>Por qué la V44 quitó {@code ventas.medio_id} sin rellenar {@code pagos_venta}</h2>
 *
 * El medio de pago va en cada pago, no en la venta (cobro mixto). La V44 elimina la columna sin
 * convertir su valor en un pago: hasta esta entrega la caja no existía, nada escribía en {@code ventas}
 * y ninguna base de producción tiene ventas reales; las pocas filas de demostración o de pruebas no
 * tienen cobros que reconstruir. Si algún día hubiera ventas con medio, habría que migrarlas a mano
 * antes de aplicar la V44.
 *
 * Toda sentencia lleva {@code empresa_id} (ADR-0011). Las escrituras de las líneas y los pagos van
 * por lotes ({@code batchUpdate}, como la importación de listas), de modo que las consultas de una
 * venta no dependen de cuántas líneas ni cuántos pagos tenga. Ninguna sentencia lleva datos
 * concatenados: lo que cambia entre llamadas son parámetros.
 */
@Repository
public class RepositorioVentas {

    /** La clave única de la idempotencia: el nombre con que el motor informa el choque. */
    public static final String CLAVE_UNICA_DE_IDEMPOTENCIA = "uq_ventas_idempotencia";

    private final JdbcClient jdbc;
    private final JdbcTemplate plantilla;

    public RepositorioVentas(JdbcClient jdbc, JdbcTemplate plantilla) {
        this.jdbc = jdbc;
        this.plantilla = plantilla;
    }

    // ------------------------------------------------------------ lo que se lee para vender

    public record VentaPorClave(Long ventaId, String huella) {}

    /**
     * La venta de esa clave de idempotencia, si la hay. No bloquea: quien llama ya tiene los
     * bloqueos de la venta y, con la transacción en READ COMMITTED, lee lo último que se confirmó.
     */
    public Optional<VentaPorClave> buscarPorClave(Integer empresaId, String clave) {
        return jdbc.sql("""
                        SELECT venta_id, huella_carga AS huella FROM ventas
                         WHERE empresa_id = :empresa AND clave_idempotencia = :clave
                        """)
                .param("empresa", empresaId)
                .param("clave", clave)
                .query(VentaPorClave.class)
                .optional();
    }

    public record ProductoBloqueado(Integer id, String nombre, int existencia, BigDecimal costo) {}

    /**
     * Los productos de la venta, por {@code producto_id} ascendente y con las filas bloqueadas
     * ({@code FOR UPDATE}) hasta que termine la transacción. El orden es el de la clave primaria, el
     * mismo para toda venta y toda carga de precios, y por eso no hay ciclo de espera entre dos que
     * comparten productos. {@code FORCE INDEX (PRIMARY)}: sin él el optimizador puede recorrer
     * otro índice y bloquear en otro orden (véase {@code RepositorioImportacion}). Los que no son
     * de la empresa no aparecen: para quien pregunta, no existen.
     */
    public List<ProductoBloqueado> bloquearProductos(Integer empresaId, Collection<Integer> ids) {
        return jdbc.sql("""
                        SELECT producto_id AS id, nombre, existencia, costo
                          FROM productos FORCE INDEX (PRIMARY)
                         WHERE producto_id IN (:ids) AND empresa_id = :empresa
                         ORDER BY producto_id
                           FOR UPDATE
                        """)
                .param("empresa", empresaId)
                .param("ids", ids)
                .query(ProductoBloqueado.class)
                .list();
    }

    public record PrecioVigente(Integer productoId, BigDecimal precio, BigDecimal tarifa, String tratamiento) {}

    /**
     * El precio de contado SIN IVA, la tarifa y el tratamiento de cada producto, en una sola
     * consulta. La vista solo muestra productos activos y los que no tienen regla de precio salen
     * con precio nulo; quien llama decide qué es vendible.
     */
    public List<PrecioVigente> preciosVigentes(Integer empresaId, Collection<Integer> ids) {
        return jdbc.sql("""
                        SELECT producto_id, precio_contado AS precio, tarifa_contado AS tarifa,
                               tratamiento_iva AS tratamiento
                          FROM v_precios_producto
                         WHERE empresa_id = :empresa AND producto_id IN (:ids)
                        """)
                .param("empresa", empresaId)
                .param("ids", ids)
                .query(PrecioVigente.class)
                .list();
    }

    /** Los medios de pago de la empresa con esos identificadores, activos o no. */
    public List<MedioDePago> mediosPorId(Integer empresaId, Collection<Integer> ids) {
        return jdbc.sql("""
                        SELECT medio_id AS id, nombre, tipo, activo
                          FROM medios_pago
                         WHERE empresa_id = :empresa AND medio_id IN (:ids)
                        """)
                .param("empresa", empresaId)
                .param("ids", ids)
                .query(MedioDePago.class)
                .list();
    }

    // ------------------------------------------------------------ lo que se escribe

    public record NuevaVenta(Integer empresaId, String consecutivo, Integer clienteId, Integer usuarioId,
                             LocalDateTime fecha, Long cierreId, BigDecimal subtotal, BigDecimal descuento,
                             BigDecimal iva, boolean sinExistencia, Integer autorizadaPor, String motivoSinExistencia,
                             boolean autorizacionAutoconcedida, String claveIdempotencia, String huellaCarga,
                             TipoDescuento descuentoTipo, BigDecimal descuentoValor, boolean domicilio,
                             boolean contraEntrega) {}

    /**
     * Inserta la venta y devuelve su identificador. Una venta con clave de idempotencia ya usada
     * lanza {@link DuplicateKeyException}; quien llama distingue ese choque del de otra clave única
     * por el nombre de la restricción ({@link #CLAVE_UNICA_DE_IDEMPOTENCIA}). {@code total} no se
     * escribe: es una columna generada. {@code forma_pago} es siempre CONTADO y el plazo, cero.
     *
     * Un domicilio pagado por adelantado nace PAGADA, como el mostrador; el de pago contra entrega nace
     * PENDIENTE (por cobrar) y con {@code pago_contra_entrega}, y el cobro posterior la pasa a PAGADA.
     */
    public Long insertarVenta(NuevaVenta v) {
        KeyHolder llaves = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO ventas (empresa_id, consecutivo, cliente_id, usuario_id, fecha, forma_pago,
                                            estado_pago, cierre_id, plazo_dias, subtotal, descuento, iva, envio,
                                            sin_existencia, autorizada_por, motivo_sin_existencia,
                                            autorizacion_autoconcedida, reposicion_pendiente, clave_idempotencia,
                                            huella_carga, descuento_tipo, descuento_valor, tipo_entrega,
                                            pago_contra_entrega)
                        VALUES (:empresa, :consecutivo, :cliente, :usuario, :fecha, 'CONTADO',
                                :estadoPago, :cierre, 0, :subtotal, :descuento, :iva, 0,
                                :sinExistencia, :autorizadaPor, :motivo,
                                :autoconcedida, :sinExistencia, :clave,
                                :huella, :descuentoTipo, :descuentoValor, :tipoEntrega, :contraEntrega)
                        """)
                .param("empresa", v.empresaId())
                .param("consecutivo", v.consecutivo())
                .param("cliente", v.clienteId())
                .param("usuario", v.usuarioId())
                .param("fecha", v.fecha())
                .param("cierre", v.cierreId())
                .param("subtotal", v.subtotal())
                .param("descuento", v.descuento())
                .param("iva", v.iva())
                .param("sinExistencia", v.sinExistencia())
                .param("autorizadaPor", v.autorizadaPor())
                .param("motivo", v.motivoSinExistencia())
                .param("autoconcedida", v.autorizacionAutoconcedida())
                .param("clave", v.claveIdempotencia())
                .param("huella", v.huellaCarga())
                .param("descuentoTipo", v.descuentoTipo() == null ? null : v.descuentoTipo().name())
                .param("descuentoValor", v.descuentoValor())
                .param("estadoPago", v.contraEntrega() ? "PENDIENTE" : "PAGADA")
                .param("tipoEntrega", v.domicilio() ? "DOMICILIO" : "MOSTRADOR")
                .param("contraEntrega", v.contraEntrega())
                .update(llaves);
        Number clave = llaves.getKey();
        if (clave == null) {
            throw new IllegalStateException("El motor no devolvió el identificador de la venta recién registrada.");
        }
        return clave.longValue();
    }

    /**
     * Los datos de entrega de la venta a domicilio. Nacen sin entregar; la entrega la marca
     * {@code ServicioDomicilios}. Tiene que llamarse después de {@link #insertarVenta}: la clave
     * foránea es hacia la venta.
     */
    public void insertarDomicilio(Integer empresaId, Long ventaId, String direccion, String ciudad, String telefono,
                                  String recibe) {
        jdbc.sql("""
                        INSERT INTO domicilios_venta (venta_id, empresa_id, direccion, ciudad, telefono, recibe)
                        VALUES (:venta, :empresa, :direccion, :ciudad, :telefono, :recibe)
                        """)
                .param("venta", ventaId)
                .param("empresa", empresaId)
                .param("direccion", direccion)
                .param("ciudad", ciudad)
                .param("telefono", telefono)
                .param("recibe", recibe)
                .update();
    }

    public record NuevaLinea(Integer productoId, int cantidad, BigDecimal precioUnitario, BigDecimal costoUnitario,
                             BigDecimal tarifaIva, String tratamientoIva, BigDecimal subtotal, BigDecimal descuento,
                             BigDecimal valorIva, BigDecimal utilidad, int cantidadEncargo, LocalDate entregaEstimada) {}

    private static final String INSERTAR_LINEA = """
            INSERT INTO venta_items (venta_id, empresa_id, producto_id, cantidad, precio_unitario, costo_unitario,
                                     tarifa_iva, tratamiento_iva, subtotal, descuento, valor_iva, utilidad,
                                     cantidad_encargo, entrega_estimada)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /** Las líneas de la venta, en un solo lote. */
    public void insertarLineas(Integer empresaId, Long ventaId, List<NuevaLinea> lineas) {
        List<Object[]> valores = new ArrayList<>(lineas.size());
        for (NuevaLinea l : lineas) {
            valores.add(new Object[]{ventaId, empresaId, l.productoId(), l.cantidad(), l.precioUnitario(),
                    l.costoUnitario(), l.tarifaIva(), l.tratamientoIva(), l.subtotal(), l.descuento(), l.valorIva(),
                    l.utilidad(), l.cantidadEncargo(), l.entregaEstimada()});
        }
        plantilla.batchUpdate(INSERTAR_LINEA, valores);
    }

    /**
     * Un pago por guardar. {@code deSaldo} marca el del medio de sistema «Saldo a favor»: no pasa por la
     * caja y por eso se guarda sin turno ({@code cierre_id} nulo).
     */
    public record NuevoPago(Integer medioId, BigDecimal monto, String referencia, BigDecimal recibido,
                            boolean deSaldo) {
        public NuevoPago(Integer medioId, BigDecimal monto, String referencia, BigDecimal recibido) {
            this(medioId, monto, referencia, recibido, false);
        }
    }

    private static final String INSERTAR_PAGO = """
            INSERT INTO pagos_venta (empresa_id, venta_id, medio_id, monto, referencia, recibido, cierre_id,
                                     registrado_por, registrado_en)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /**
     * Los pagos de la venta, en un solo lote, con el cajero y la hora de la venta y el turno de quien
     * cobra, salvo el pago con saldo a favor, que no lleva turno.
     */
    public void insertarPagos(Integer empresaId, Long ventaId, Long cierreId, Integer registradoPor,
                              LocalDateTime registradoEn, List<NuevoPago> pagos) {
        List<Object[]> valores = new ArrayList<>(pagos.size());
        for (NuevoPago p : pagos) {
            valores.add(new Object[]{empresaId, ventaId, p.medioId(), p.monto(), p.referencia(), p.recibido(),
                    p.deSaldo() ? null : cierreId, registradoPor, registradoEn});
        }
        plantilla.batchUpdate(INSERTAR_PAGO, valores);
    }

    // ------------------------------------------------------------ la venta ya registrada

    /**
     * La venta tal como se le respondió al mostrador cuando se registró: el cambio sale de lo
     * recibido en efectivo menos lo cobrado en efectivo, y las líneas, de lo guardado. Nunca lee
     * el costo ni la utilidad.
     *
     * Una venta de pago contra entrega se registró sin pagos y su respuesta fue con cambio cero: los
     * pagos que tenga son del cobro posterior (con su propio efectivo y su propio cambio), no de la
     * venta, así que el reintento devuelve lo mismo que la primera respuesta aunque ya se haya cobrado.
     */
    public Optional<VentaRegistrada> leer(Integer empresaId, Long ventaId) {
        Optional<Object[]> cabecera = jdbc.sql("""
                        SELECT venta_id, consecutivo, total, pago_contra_entrega FROM ventas
                         WHERE empresa_id = :empresa AND venta_id = :venta
                        """)
                .param("empresa", empresaId)
                .param("venta", ventaId)
                .query((fila, n) -> new Object[]{fila.getLong("venta_id"), fila.getString("consecutivo"),
                        fila.getBigDecimal("total"), fila.getBoolean("pago_contra_entrega")})
                .optional();
        if (cabecera.isEmpty()) {
            return Optional.empty();
        }
        List<LineaCalculada> lineas = jdbc.sql("""
                        SELECT producto_id, cantidad, precio_unitario, subtotal, descuento, tarifa_iva,
                               tratamiento_iva, valor_iva
                          FROM venta_items
                         WHERE empresa_id = :empresa AND venta_id = :venta
                         ORDER BY producto_id
                        """)
                .param("empresa", empresaId)
                .param("venta", ventaId)
                .query((fila, n) -> {
                    BigDecimal subtotal = fila.getBigDecimal("subtotal");
                    BigDecimal descuento = fila.getBigDecimal("descuento");
                    BigDecimal base = subtotal.subtract(descuento);
                    BigDecimal iva = fila.getBigDecimal("valor_iva");
                    return new LineaCalculada(fila.getInt("producto_id"), fila.getInt("cantidad"),
                            fila.getBigDecimal("precio_unitario"), subtotal, descuento, base,
                            fila.getBigDecimal("tarifa_iva").setScale(4, RoundingMode.UNNECESSARY),
                            fila.getString("tratamiento_iva"), iva, base.add(iva));
                })
                .list();
        boolean contraEntrega = (Boolean) cabecera.get()[3];
        BigDecimal cambio = contraEntrega ? BigDecimal.ZERO : jdbc.sql("""
                        SELECT COALESCE(SUM(p.recibido - p.monto), 0)
                          FROM pagos_venta p
                          JOIN medios_pago m ON m.empresa_id = p.empresa_id AND m.medio_id = p.medio_id
                         WHERE p.empresa_id = :empresa AND p.venta_id = :venta AND m.tipo = 'EFECTIVO'
                        """)
                .param("empresa", empresaId)
                .param("venta", ventaId)
                .query(BigDecimal.class)
                .single();
        Object[] c = cabecera.get();
        return Optional.of(new VentaRegistrada((Long) c[0], (String) c[1], (BigDecimal) c[2],
                cambio.setScale(2, RoundingMode.HALF_UP), List.copyOf(lineas)));
    }
}

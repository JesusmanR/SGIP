package co.com.bspstore.sgip.datos;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Los domicilios de la caja (RF-44, RF-69): las listas de lo que falta por despachar y por cobrar, la
 * entrega y el bloqueo de la venta que se cobra después. {@code domicilios_venta} nace con la venta
 * (véase {@code RepositorioVentas.insertarDomicilio}); aquí se lee y se marca como entregado.
 *
 * Toda sentencia lleva {@code empresa_id} (ADR-0011), también las uniones: el domicilio, la venta y el
 * cliente se juntan por empresa y por clave, de modo que el de otra empresa es, para quien pregunta, un
 * domicilio que no existe. Ninguna sentencia lleva datos concatenados; lo que cambia entre llamadas son
 * parámetros. Los datos personales (cliente, dirección, teléfono) se devuelven a quien llama y no se
 * escriben en ningún registro del servidor.
 *
 * Las dos listas van de la más antigua a la más reciente y con un tope: lo que lleva más tiempo sin
 * despachar o sin cobrar es lo que importa, y el tope deja fuera a lo más nuevo, no a lo más viejo. Cada
 * lista es una sola consulta, sin importar cuántas filas devuelva.
 */
@Repository
public class RepositorioDomicilios {

    private final JdbcClient jdbc;

    public RepositorioDomicilios(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Una fila de las listas; el valor por cobrar y la antigüedad los calcula quien llama. */
    public record FilaDeDomicilio(Long ventaId, String consecutivo, LocalDateTime fecha, String cliente,
                                  String direccion, String ciudad, String telefono, String recibe,
                                  boolean contraEntrega, String estadoPago, BigDecimal total) {}

    private static final String COLUMNAS = """
            SELECT v.venta_id, v.consecutivo, v.fecha, c.razon_social AS cliente, d.direccion, d.ciudad,
                   d.telefono, d.recibe, v.pago_contra_entrega AS contra_entrega, v.estado_pago, v.total
              FROM domicilios_venta d
              JOIN ventas v ON v.empresa_id = d.empresa_id AND v.venta_id = d.venta_id
              JOIN clientes c ON c.empresa_id = v.empresa_id AND c.cliente_id = v.cliente_id
            """;

    /** Los domicilios que todavía no se entregaron, de una venta que sigue vigente. */
    public List<FilaDeDomicilio> listarPorDespachar(Integer empresaId, int tope) {
        return jdbc.sql(COLUMNAS + """
                         WHERE d.empresa_id = :empresa AND v.empresa_id = :empresa AND d.entregado_en IS NULL
                           AND v.estado = 'REGISTRADA'
                         ORDER BY v.fecha, v.venta_id
                         LIMIT :tope
                        """)
                .param("empresa", empresaId)
                .param("tope", tope)
                .query(FilaDeDomicilio.class)
                .list();
    }

    /** Las ventas a domicilio de pago contra entrega que todavía no se cobraron, entregadas o no. */
    public List<FilaDeDomicilio> listarPorCobrar(Integer empresaId, int tope) {
        return jdbc.sql(COLUMNAS + """
                         WHERE d.empresa_id = :empresa AND v.empresa_id = :empresa AND v.pago_contra_entrega = TRUE
                           AND v.estado_pago = 'PENDIENTE' AND v.estado = 'REGISTRADA'
                         ORDER BY v.fecha, v.venta_id
                         LIMIT :tope
                        """)
                .param("empresa", empresaId)
                .param("tope", tope)
                .query(FilaDeDomicilio.class)
                .list();
    }

    // ------------------------------------------------------------ la entrega

    /**
     * Marca el domicilio como entregado, <b>solo si todavía no lo estaba</b>: la condición
     * {@code entregado_en IS NULL} va en la propia actualización y es lo que decide quién llegó primero
     * cuando dos entregas coinciden. La segunda espera la fila de la primera, la encuentra ya entregada y
     * no cambia nada: devuelve cero filas. Nadie lee antes de escribir, porque una lectura previa no ve
     * lo que la otra aún no confirmó.
     */
    public int marcarEntregado(Integer empresaId, Long ventaId, LocalDateTime cuando, Integer quien) {
        return jdbc.sql("""
                        UPDATE domicilios_venta SET entregado_en = :cuando, entregado_por = :quien
                         WHERE empresa_id = :empresa AND venta_id = :venta AND entregado_en IS NULL
                        """)
                .param("cuando", cuando)
                .param("quien", quien)
                .param("empresa", empresaId)
                .param("venta", ventaId)
                .update();
    }

    /** Cómo está un domicilio: si ya se entregó y si su venta sigue vigente. Vacío si no es de la empresa. */
    public record EstadoDelDomicilio(boolean entregado, boolean ventaVigente) {}

    public Optional<EstadoDelDomicilio> estadoDelDomicilio(Integer empresaId, Long ventaId) {
        return jdbc.sql("""
                        SELECT d.entregado_en IS NOT NULL AS entregado, v.estado = 'REGISTRADA' AS venta_vigente
                          FROM domicilios_venta d
                          JOIN ventas v ON v.empresa_id = d.empresa_id AND v.venta_id = d.venta_id
                         WHERE d.empresa_id = :empresa AND d.venta_id = :venta
                        """)
                .param("empresa", empresaId)
                .param("venta", ventaId)
                .query(EstadoDelDomicilio.class)
                .optional();
    }

    // ------------------------------------------------------------ el cobro posterior

    /** Lo que el cobro necesita saber de la venta, ya bloqueada. */
    public record VentaPorCobrar(Long ventaId, String consecutivo, BigDecimal total, String estado, String estadoPago,
                                 boolean contraEntrega) {}

    /**
     * La venta, con su fila tomada {@code FOR UPDATE} por clave primaria hasta el fin de la transacción:
     * es lo que hace que dos cobros de la misma venta se pongan en fila (el segundo, al despertar, la
     * encuentra ya pagada). Va después del bloqueo del turno y antes de cualquier otro (orden global de
     * la caja). {@code FORCE INDEX (PRIMARY)} como el bloqueo de los productos y del turno: sin él el
     * optimizador podría recorrer otro índice y bloquear en otro orden. Vacío si la venta no es de la
     * empresa.
     */
    public Optional<VentaPorCobrar> bloquearVenta(Integer empresaId, Long ventaId) {
        return jdbc.sql("""
                        SELECT venta_id, consecutivo, total, estado, estado_pago,
                               pago_contra_entrega AS contra_entrega
                          FROM ventas FORCE INDEX (PRIMARY)
                         WHERE venta_id = :venta AND empresa_id = :empresa
                           FOR UPDATE
                        """)
                .param("venta", ventaId)
                .param("empresa", empresaId)
                .query(VentaPorCobrar.class)
                .optional();
    }

    /**
     * Pasa la venta a pagada. Quien llama tiene la fila bloqueada y acaba de comprobar que estaba por
     * cobrar, de modo que la actualización es por clave y sin condición: si se pudiera llegar aquí sin
     * el bloqueo, dos cobros pasarían los dos (por eso la prueba de dos hilos sostiene el bloqueo desde
     * fuera y comprueba que los dos esperan).
     */
    public void marcarPagada(Integer empresaId, Long ventaId) {
        int filas = jdbc.sql("""
                        UPDATE ventas SET estado_pago = 'PAGADA'
                         WHERE empresa_id = :empresa AND venta_id = :venta
                        """)
                .param("empresa", empresaId)
                .param("venta", ventaId)
                .update();
        if (filas != 1) {
            throw new IllegalStateException("La venta " + ventaId + " desapareció dentro de su propia transacción.");
        }
    }
}

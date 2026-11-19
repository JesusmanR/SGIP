package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.MovimientoDeInventario;
import co.com.bspstore.sgip.modelo.TipoMovimiento;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * El kárdex: {@code movimientos_inventario} y la existencia de
 * {@code productos} (RF-48, RF-49).
 *
 * No es JPA, y aquí el motivo es el bloqueo. La operación central del
 * módulo —leer el saldo, escribir el movimiento, dejar el saldo nuevo—
 * tiene que ser atómica frente a otra igual sobre el mismo producto, y
 * eso se consigue con un {@code SELECT ... FOR UPDATE} explícito. Con
 * JPA habría que pedirle a Hibernate un bloqueo pesimista y confiar en
 * que lo traduzca; con una sentencia escrita se ve lo que el motor va a
 * hacer.
 *
 * Por qué importa tanto: sin bloqueo, dos ingresos simultáneos sobre la
 * misma referencia leen el mismo saldo —diez—, los dos escriben «de diez
 * a quince», y el kárdex queda con dos filas que arrancan del mismo
 * sitio. La suma deja de dar la existencia y el RF-49 se incumple sin
 * que ninguna restricción se entere: cada fila, por separado, es
 * perfectamente coherente. Es la única incoherencia que la V23 no puede
 * atajar, porque sus comprobaciones miran una fila a la vez.
 */
@Repository
public class RepositorioInventario {

    private final JdbcClient jdbc;
    private final JdbcTemplate plantilla;

    public RepositorioInventario(JdbcClient jdbc, JdbcTemplate plantilla) {
        this.jdbc = jdbc;
        this.plantilla = plantilla;
    }

    /**
     * El saldo actual del producto, con la fila bloqueada hasta que
     * termine la transacción.
     *
     * Vacío si el producto no es de esta empresa, que es el mismo
     * resultado que si no existiera: el filtro por empresa va en la
     * misma sentencia que el bloqueo, de modo que no hay un instante en
     * el que se haya bloqueado una fila ajena.
     *
     * Tiene que llamarse dentro de una transacción. Fuera de una, cada
     * sentencia se confirma sola y el bloqueo se suelta al terminar el
     * SELECT, con lo que no protege de nada.
     */
    public Optional<Integer> bloquearSaldo(Integer empresaId, Integer productoId) {
        return jdbc.sql("""
                        SELECT existencia FROM productos
                         WHERE empresa_id = :empresa AND producto_id = :producto
                           FOR UPDATE
                        """)
                .param("empresa", empresaId)
                .param("producto", productoId)
                .query(Integer.class)
                .optional();
    }

    /**
     * El saldo actual del producto, sin bloquear nada.
     *
     * Es para leer —el cuadre del RF-49—, no para escribir: quien vaya a
     * cambiar la existencia tiene que usar {@link #bloquearSaldo}. Vacío
     * si el producto no es de esta empresa, igual que allí.
     */
    public Optional<Integer> saldoActual(Integer empresaId, Integer productoId) {
        return jdbc.sql("""
                        SELECT existencia FROM productos
                         WHERE empresa_id = :empresa AND producto_id = :producto
                        """)
                .param("empresa", empresaId)
                .param("producto", productoId)
                .query(Integer.class)
                .optional();
    }

    /** Devuelve el identificador que el motor asignó al movimiento. */
    public Long insertarMovimiento(Integer empresaId, Integer productoId, Integer usuarioId,
                                   TipoMovimiento tipo, int cantidad,
                                   int anterior, int posterior,
                                   String documento, String motivo, Long recepcionId) {
        return insertarMovimiento(empresaId, productoId, usuarioId, tipo, cantidad, anterior, posterior,
                documento, motivo, recepcionId, null);
    }

    /**
     * Lo mismo, con quién autorizó el movimiento (un egreso que deja la existencia en negativo,
     * por ejemplo). La firma anterior se conserva y delega aquí con {@code null}.
     */
    public Long insertarMovimiento(Integer empresaId, Integer productoId, Integer usuarioId,
                                   TipoMovimiento tipo, int cantidad,
                                   int anterior, int posterior,
                                   String documento, String motivo, Long recepcionId,
                                   Integer autorizadoPor) {
        jdbc.sql("""
                        INSERT INTO movimientos_inventario
                               (empresa_id, producto_id, usuario_id, tipo, cantidad,
                                existencia_anterior, existencia_posterior,
                                documento, motivo, recepcion_id, autorizado_por)
                        VALUES (:empresa, :producto, :usuario, :tipo, :cantidad,
                                :anterior, :posterior, :documento, :motivo, :recepcion, :autoriza)
                        """)
                .param("empresa", empresaId)
                .param("producto", productoId)
                .param("usuario", usuarioId)
                .param("tipo", tipo.name())
                .param("cantidad", cantidad)
                .param("anterior", anterior)
                .param("posterior", posterior)
                .param("documento", documento)
                .param("motivo", motivo)
                .param("recepcion", recepcionId)
                .param("autoriza", autorizadoPor)
                .update();
        // LAST_INSERT_ID() va en la misma conexión y la misma transacción
        // que el INSERT, de modo que no puede devolver el de otra sesión.
        return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    /**
     * Un egreso de una venta: lo que sale de un producto, su saldo antes y después y quién autorizó
     * que la existencia quedara en negativo (nulo en una venta normal).
     */
    public record Egreso(Integer productoId, int cantidad, int anterior, int posterior, Integer autorizadoPor) {}

    private static final String INSERTAR_EGRESO = """
            INSERT INTO movimientos_inventario
                   (empresa_id, producto_id, usuario_id, tipo, cantidad,
                    existencia_anterior, existencia_posterior, documento, autorizado_por)
            VALUES (?, ?, ?, 'EGRESO', ?, ?, ?, ?, ?)
            """;

    private static final String FIJAR_SALDO = """
            UPDATE productos SET existencia = ?
             WHERE empresa_id = ? AND producto_id = ?
            """;

    /**
     * Los egresos de una venta: un movimiento por producto y el saldo nuevo en cada uno, cada
     * cosa en un solo lote. Es lo mismo que hace {@code ServicioInventario} con un movimiento (las
     * dos escrituras juntas, el saldo con el valor que el movimiento declara como posterior), pero
     * sin una ida y vuelta por producto: las consultas de una venta no crecen con sus líneas.
     *
     * Quien llama tiene los productos bloqueados ({@code FOR UPDATE}) y es de ahí de donde salen
     * {@code anterior} y {@code posterior}; la restricción {@code ck_movimientos_coherencia} rechaza
     * una fila que no cuadre.
     */
    public void registrarEgresos(Integer empresaId, Integer usuarioId, String documento, List<Egreso> egresos) {
        List<Object[]> movimientos = new ArrayList<>(egresos.size());
        List<Object[]> saldos = new ArrayList<>(egresos.size());
        for (Egreso e : egresos) {
            movimientos.add(new Object[]{empresaId, e.productoId(), usuarioId, e.cantidad(), e.anterior(),
                    e.posterior(), documento, e.autorizadoPor()});
            saldos.add(new Object[]{e.posterior(), empresaId, e.productoId()});
        }
        plantilla.batchUpdate(INSERTAR_EGRESO, movimientos);
        plantilla.batchUpdate(FIJAR_SALDO, saldos);
    }

    /** Lo que vuelve a la bodega de un producto con una devolución: unidades y saldo antes y después. */
    public record Reingreso(Integer productoId, int cantidad, int anterior, int posterior) {}

    private static final String INSERTAR_DEVOLUCION = """
            INSERT INTO movimientos_inventario
                   (empresa_id, producto_id, usuario_id, tipo, cantidad,
                    existencia_anterior, existencia_posterior, documento)
            VALUES (?, ?, ?, 'DEVOLUCION', ?, ?, ?, ?)
            """;

    /**
     * Los reingresos de una devolución: un movimiento DEVOLUCION por producto y el saldo nuevo en cada uno,
     * en un solo lote cada cosa, como {@link #registrarEgresos}. {@code documento} es el consecutivo de la
     * devolución. Quien llama tiene los productos bloqueados ({@code FOR UPDATE}) y de ahí salen
     * {@code anterior} y {@code posterior}; {@code ck_movimientos_coherencia} rechaza una fila que no cuadre.
     */
    public void registrarReingresos(Integer empresaId, Integer usuarioId, String documento, List<Reingreso> reingresos) {
        List<Object[]> movimientos = new ArrayList<>(reingresos.size());
        List<Object[]> saldos = new ArrayList<>(reingresos.size());
        for (Reingreso r : reingresos) {
            movimientos.add(new Object[]{empresaId, r.productoId(), usuarioId, r.cantidad(), r.anterior(),
                    r.posterior(), documento});
            saldos.add(new Object[]{r.posterior(), empresaId, r.productoId()});
        }
        plantilla.batchUpdate(INSERTAR_DEVOLUCION, movimientos);
        plantilla.batchUpdate(FIJAR_SALDO, saldos);
    }

    /**
     * Deja el saldo nuevo en el producto.
     *
     * Escribe el valor calculado en lugar de sumar en la propia
     * sentencia —{@code existencia = existencia + :cantidad}— a
     * propósito: el valor que se guarda tiene que ser exactamente el que
     * el movimiento declara como posterior, y una suma hecha por el
     * motor podría partir de un saldo distinto si el bloqueo fallara.
     * Así, si algo va mal, las dos cifras discrepan y se nota.
     */
    public int fijarSaldo(Integer empresaId, Integer productoId, int saldo) {
        return jdbc.sql("""
                        UPDATE productos SET existencia = :saldo
                         WHERE empresa_id = :empresa AND producto_id = :producto
                        """)
                .param("saldo", saldo)
                .param("empresa", empresaId)
                .param("producto", productoId)
                .update();
    }

    private static final String KARDEX = """
            SELECT m.movimiento_id, m.producto_id, p.codigo, p.nombre AS producto,
                   m.tipo, m.cantidad, m.existencia_anterior, m.existencia_posterior,
                   m.documento, m.motivo, m.recepcion_id,
                   m.usuario_id, u.nombre AS usuario, m.fecha
              FROM movimientos_inventario m
              JOIN productos p ON p.producto_id = m.producto_id
                              AND p.empresa_id  = m.empresa_id
              JOIN usuarios  u ON u.usuario_id  = m.usuario_id
                              AND u.empresa_id  = m.empresa_id
             WHERE m.empresa_id = :empresa
            """;

    /**
     * El kárdex de una referencia, del movimiento más reciente al más
     * antiguo.
     *
     * El orden incluye {@code movimiento_id} además de la fecha porque
     * dos movimientos del mismo segundo —una recepción que entra varias
     * líneas— tendrían la misma fecha, y entonces el orden sería el que
     * el motor quisiera. Con el identificador, la cadena se lee siempre
     * igual, que es lo que permite comprobar que el posterior de uno es
     * el anterior del siguiente.
     */
    public List<MovimientoDeInventario> kardex(Integer empresaId, Integer productoId, int limite) {
        return jdbc.sql(KARDEX + """
                           AND m.producto_id = :producto
                         ORDER BY m.fecha DESC, m.movimiento_id DESC
                         LIMIT :limite
                        """)
                .param("empresa", empresaId)
                .param("producto", productoId)
                .param("limite", limite)
                .query(MovimientoDeInventario.class)
                .list();
    }

    /** Los últimos movimientos de toda la empresa, para la pantalla de inventario. */
    public List<MovimientoDeInventario> ultimos(Integer empresaId, int limite) {
        return jdbc.sql(KARDEX + """
                         ORDER BY m.fecha DESC, m.movimiento_id DESC
                         LIMIT :limite
                        """)
                .param("empresa", empresaId)
                .param("limite", limite)
                .query(MovimientoDeInventario.class)
                .list();
    }

    /**
     * La comprobación del RF-49 para una referencia: la suma algebraica
     * de sus movimientos.
     *
     * El ajuste usa el salto y no la cantidad porque es el único que
     * puede ir en las dos direcciones, de modo que su magnitud no dice
     * hacia dónde. Es la misma expresión que usa
     * {@code database/verificar_kardex.sh}, y conviene que lo sea: si
     * alguna vez discreparan, una de las dos estaría midiendo otra cosa.
     */
    public int sumaDeMovimientos(Integer empresaId, Integer productoId) {
        Integer suma = jdbc.sql("""
                        SELECT COALESCE(SUM(CASE tipo
                                              WHEN 'EGRESO' THEN -cantidad
                                              WHEN 'AJUSTE' THEN existencia_posterior
                                                                 - existencia_anterior
                                              ELSE cantidad
                                            END), 0)
                          FROM movimientos_inventario
                         WHERE empresa_id = :empresa AND producto_id = :producto
                        """)
                .param("empresa", empresaId)
                .param("producto", productoId)
                .query(Integer.class)
                .single();
        return suma == null ? 0 : suma;
    }
}

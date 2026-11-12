package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.Turno;
import co.com.bspstore.sgip.util.Hoy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Los turnos de caja de cada empresa (RF-45), sobre {@code cierres_caja}.
 *
 * Toda sentencia lleva {@code empresa_id} (ADR-0011) y el turno de otra empresa es,
 * para quien pregunta, un turno que no existe. Las columnas {@code apertura} y
 * {@code cierre} son DATETIME sin zona: guardan la hora de pared de Bogotá, la misma
 * que ve la sesión de la base (ADR-0030). Aquí se leen con ese desplazamiento para que
 * el cliente reciba un instante sin ambigüedad.
 *
 * Quién tiene un turno abierto lo dice {@code abierto_de}, la columna generada de la
 * V43 que lleva el índice único: buscar por ella usa ese índice y, al bloquear, toma
 * solo la fila de ese turno y no el historial del cajero.
 */
@Repository
public class RepositorioTurnos {

    private static final String TURNO = """
            SELECT c.cierre_id, c.usuario_id, u.nombre, c.apertura, c.base_efectivo, c.cierre
              FROM cierres_caja c
              JOIN usuarios u ON u.empresa_id = c.empresa_id AND u.usuario_id = c.usuario_id
            """;

    private final JdbcClient jdbc;

    public RepositorioTurnos(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** El turno abierto del cajero, leído sin bloqueo. */
    public Optional<Turno> abiertoDe(Integer empresa, Integer cajero) {
        return jdbc.sql(TURNO + " WHERE c.empresa_id = :empresa AND c.abierto_de = :cajero")
                .param("empresa", empresa)
                .param("cajero", cajero)
                .query(RepositorioTurnos::turno)
                .optional();
    }

    /**
     * El identificador del turno abierto del cajero, con la fila tomada en modo
     * compartido ({@code FOR SHARE}): quien la tiene no impide que otra operación del
     * mismo turno la tome también, y sí impide que el cierre ({@code FOR UPDATE}) la
     * tome hasta que la operación termine. Fuera de una transacción el bloqueo se
     * soltaría al acabar la sentencia, así que solo se llama dentro de una.
     *
     * Se pide también {@code usuario_id}, que no está en el índice único, a propósito:
     * con solo {@code cierre_id} el índice cubriría la lectura y, en modo compartido,
     * InnoDB bloquearía únicamente la entrada del índice y no la fila del turno. El
     * cierre toma esa fila con {@code FOR UPDATE} por su clave primaria y no esperaría.
     * Leer una columna más obliga a ir a la fila y a bloquearla.
     */
    public Optional<Long> bloquearAbiertoDe(Integer empresa, Integer cajero) {
        return jdbc.sql("""
                        SELECT cierre_id, usuario_id FROM cierres_caja
                         WHERE empresa_id = :empresa AND abierto_de = :cajero
                           FOR SHARE
                        """)
                .param("empresa", empresa)
                .param("cajero", cajero)
                .query((fila, numero) -> fila.getLong("cierre_id"))
                .optional();
    }

    public Optional<Turno> buscar(Integer empresa, Long id) {
        return jdbc.sql(TURNO + " WHERE c.empresa_id = :empresa AND c.cierre_id = :id")
                .param("empresa", empresa)
                .param("id", id)
                .query(RepositorioTurnos::turno)
                .optional();
    }

    /**
     * Abre el turno y devuelve su identificador. Si el cajero ya tiene uno abierto, el
     * índice único de la V43 lo rechaza con una {@code DuplicateKeyException}.
     */
    public Long insertar(Integer empresa, Integer cajero, LocalDateTime apertura, BigDecimal base) {
        KeyHolder llaves = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO cierres_caja (empresa_id, usuario_id, apertura, base_efectivo)
                        VALUES (:empresa, :cajero, :apertura, :base)
                        """)
                .param("empresa", empresa)
                .param("cajero", cajero)
                .param("apertura", apertura)
                .param("base", base)
                .update(llaves);
        Number clave = llaves.getKey();
        if (clave == null) {
            throw new IllegalStateException("El motor no devolvió el identificador del turno recién abierto.");
        }
        return clave.longValue();
    }

    private static Turno turno(ResultSet fila, int numero) throws SQLException {
        return new Turno(fila.getLong("cierre_id"), fila.getInt("usuario_id"), fila.getString("nombre"),
                conDesplazamiento(fila.getObject("apertura", LocalDateTime.class)),
                // En pesos enteros: la columna guarda dos decimales que siempre son cero.
                fila.getBigDecimal("base_efectivo").setScale(0, RoundingMode.HALF_UP),
                conDesplazamiento(fila.getObject("cierre", LocalDateTime.class)));
    }

    private static OffsetDateTime conDesplazamiento(LocalDateTime horaDeBogota) {
        return horaDeBogota == null ? null : horaDeBogota.atZone(Hoy.ZONA).toOffsetDateTime();
    }
}

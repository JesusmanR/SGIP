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
 * V43 que lleva el índice único: buscar por ella usa ese índice y no recorre el
 * historial del cajero. Esa búsqueda no bloquea nunca; los bloqueos de la caja sobre
 * el turno van por la clave primaria (véase {@link #bloquearAbiertoDe}).
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
     * Son dos pasos, y el orden importa. Primero se averigua cuál es el turno sin
     * bloquear nada, por {@code abierto_de}. Después se toma <b>solo la fila</b>, por su
     * clave primaria, y se repite {@code cierre IS NULL}: es una lectura actual, de modo
     * que un turno que se cerró después del primer paso ya no aparece y la operación
     * recibe el «sin turno abierto».
     *
     * No se toma ningún bloqueo por un índice secundario. El cierre bloquea la fila por su
     * clave primaria y después escribe {@code cierre}, lo que cambia {@code abierto_de} y le
     * obliga a tocar {@code uq_cierres_abierto}; y toda inserción que referencia el turno
     * comprueba la clave foránea compuesta {@code fk_ventas_cierre_emp} (V11), que toma un
     * bloqueo compartido sobre la entrada de {@code uq_cierres_empresa}. Si una operación
     * tuviera una entrada así y esperara la fila, el cierre esperaría a la operación para
     * tocarla y la operación al cierre para leer la fila: un interbloqueo, y el motor mata a
     * una de las dos. Con las dos puntas en {@code PRIMARY} piden lo mismo en el mismo orden y
     * no hay ciclo. <b>Por eso el cierre (tarea 10) debe tomar el turno solo por
     * {@code PRIMARY}, con {@code FORCE INDEX (PRIMARY)}</b>, ni por {@code uq_cierres_abierto}
     * ni por {@code uq_cierres_empresa}. Tampoco queda un bloqueo de hueco sobre una clave única
     * que no existe cuando el cajero no tiene turno.
     *
     * Caso seguro del 409: si entre los dos pasos el turno se cierra y el mismo cajero abre
     * otro, el segundo paso no encuentra el turno que averiguó el primero y la operación
     * recibe el 409 de «sin turno abierto» aunque ya exista el turno nuevo. Es una ventana
     * de milisegundos y el error es hacia el lado seguro: nada se registra contra un turno
     * que no era el que la operación vio; el cajero repite la operación.
     */
    public Optional<Long> bloquearAbiertoDe(Integer empresa, Integer cajero) {
        Optional<Long> turno = jdbc.sql("""
                        SELECT cierre_id FROM cierres_caja
                         WHERE empresa_id = :empresa AND abierto_de = :cajero
                        """)
                .param("empresa", empresa)
                .param("cajero", cajero)
                .query(Long.class)
                .optional();
        if (turno.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        SELECT cierre_id FROM cierres_caja FORCE INDEX (PRIMARY)
                         WHERE cierre_id = :turno AND empresa_id = :empresa AND cierre IS NULL
                           FOR SHARE
                        """)
                .param("turno", turno.get())
                .param("empresa", empresa)
                .query(Long.class)
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

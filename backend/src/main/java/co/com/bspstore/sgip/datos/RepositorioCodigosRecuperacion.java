package co.com.bspstore.sgip.datos;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Los códigos con los que se recupera la contraseña (V52, RF-65) y las dos cuentas
 * de la bitácora que fijan sus topes.
 *
 * Es el único camino a la tabla {@code codigos_recuperacion}: no hay entidad JPA. Se
 * escribe y se lee por JDBC para que la hora que la aplicación escribe sea la misma
 * que lee, sin la conversión de zona que Hibernate haría entre la JVM y la conexión
 * (ver {@code RepositorioPin}). Toda hora llega por parámetro y sale de
 * {@code Hoy.ahora(reloj)} (ADR-0030); aquí no se llama a {@code NOW()} ni a
 * {@code CURRENT_TIMESTAMP} para decidir nada.
 *
 * La tabla no tiene {@code empresa_id}: el código es de un usuario, que se identificó
 * con su correo antes de llegar aquí.
 *
 * Los métodos que escriben suponen una transacción abierta por quien los llama.
 * {@link #bloquearVigente} en particular solo ordena a dos confirmaciones simultáneas
 * si la transacción sigue abierta después de él: el bloqueo de la fila dura hasta su
 * final.
 */
@Repository
public class RepositorioCodigosRecuperacion {

    /**
     * El código vigente de un usuario. {@code hash} es derivado y aun así no sale en
     * el {@code toString}: un registro impreso en un log no debe acercar a nadie a
     * adivinar el código.
     */
    public record CodigoVigente(long id, String hash, int intentos) {
        @Override
        public String toString() {
            return "CodigoVigente[id=" + id + ", intentos=" + intentos + "]";
        }
    }

    private final JdbcClient jdbc;

    public RepositorioCodigosRecuperacion(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Guarda un código nuevo, sin usar y sin intentos, y devuelve su identificador. */
    public long crear(Integer usuarioId, String codigoHash, LocalDateTime creadoEn,
                      LocalDateTime expira, String origen) {
        KeyHolder llave = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO codigos_recuperacion (usuario_id, codigo_hash, creado_en, expira, origen)
                        VALUES (:usuario, :hash, :creadoEn, :expira, :origen)
                        """)
                .param("usuario", usuarioId)
                .param("hash", codigoHash)
                .param("creadoEn", creadoEn)
                .param("expira", expira)
                .param("origen", origen)
                .update(llave);
        return llave.getKey().longValue();
    }

    /**
     * Marca como usados los códigos que aún valen de este usuario, para que pedir uno
     * nuevo deje vigente solo al último. Devuelve cuántos invalidó.
     */
    public int invalidarVigentes(Integer usuarioId, LocalDateTime ahora) {
        return jdbc.sql("""
                        UPDATE codigos_recuperacion
                           SET usado_en = :ahora
                         WHERE usuario_id = :usuario
                           AND usado_en IS NULL
                           AND expira > :ahora
                        """)
                .param("usuario", usuarioId)
                .param("ahora", ahora)
                .update();
    }

    /** Cuántos códigos se crearon para este usuario desde {@code desde}, inclusive. */
    public int contarCreadosDesde(Integer usuarioId, LocalDateTime desde) {
        return jdbc.sql("""
                        SELECT COUNT(*)
                          FROM codigos_recuperacion
                         WHERE usuario_id = :usuario
                           AND creado_en >= :desde
                        """)
                .param("usuario", usuarioId)
                .param("desde", desde)
                .query(Integer.class)
                .single();
    }

    /**
     * El código más reciente del usuario que no se ha usado ni ha vencido, con su fila
     * tomada ({@code FOR UPDATE}) hasta el final de la transacción: dos confirmaciones
     * a la vez se ordenan y sus intentos no se pisan. Un código que vence exactamente
     * en {@code ahora} ya no vale.
     */
    public Optional<CodigoVigente> bloquearVigente(Integer usuarioId, LocalDateTime ahora) {
        return jdbc.sql("""
                        SELECT codigo_id, codigo_hash, intentos
                          FROM codigos_recuperacion
                         WHERE usuario_id = :usuario
                           AND usado_en IS NULL
                           AND expira > :ahora
                         ORDER BY codigo_id DESC
                         LIMIT 1
                           FOR UPDATE
                        """)
                .param("usuario", usuarioId)
                .param("ahora", ahora)
                .query((rs, n) -> new CodigoVigente(rs.getLong("codigo_id"),
                        rs.getString("codigo_hash"), rs.getInt("intentos")))
                .optional();
    }

    /** Suma una confirmación fallida a este código. */
    public void sumarIntento(long codigoId) {
        jdbc.sql("""
                        UPDATE codigos_recuperacion
                           SET intentos = LEAST(intentos, 254) + 1
                         WHERE codigo_id = :codigo
                        """)
                .param("codigo", codigoId)
                .update();
    }

    /** Marca el código como usado: ya no vale. */
    public void marcarUsado(long codigoId, LocalDateTime ahora) {
        jdbc.sql("""
                        UPDATE codigos_recuperacion
                           SET usado_en = :ahora
                         WHERE codigo_id = :codigo
                        """)
                .param("codigo", codigoId)
                .param("ahora", ahora)
                .update();
    }

    /**
     * Las confirmaciones fallidas de este usuario desde {@code desde}, inclusive: las
     * filas de la bitácora con el motivo {@code RECUPERACION_CODIGO_INVALIDO}. La
     * {@code fecha} de la bitácora la pone la base con la hora de su sesión, que es la
     * de Bogotá (ADR-0030), la misma en que el servicio calcula {@code desde}.
     */
    public int contarFallosDeRecuperacion(Integer usuarioId, LocalDateTime desde) {
        return jdbc.sql("""
                        SELECT COUNT(*)
                          FROM auditoria_accesos
                         WHERE usuario_id = :usuario
                           AND motivo = 'RECUPERACION_CODIGO_INVALIDO'
                           AND fecha >= :desde
                        """)
                .param("usuario", usuarioId)
                .param("desde", desde)
                .query(Integer.class)
                .single();
    }

    /**
     * Los hechos de recuperación (cualquier motivo {@code RECUPERACION_*}) que dejó
     * este origen desde {@code desde}, inclusive. El guion bajo del patrón va escapado:
     * sin escapar es un comodín y contaría un motivo como {@code RECUPERACIONX}.
     */
    public int contarSolicitudesDesdeOrigen(String origen, LocalDateTime desde) {
        return jdbc.sql("""
                        SELECT COUNT(*)
                          FROM auditoria_accesos
                         WHERE origen = :origen
                           AND motivo LIKE 'RECUPERACION!_%' ESCAPE '!'
                           AND fecha >= :desde
                        """)
                .param("origen", origen)
                .param("desde", desde)
                .query(Integer.class)
                .single();
    }
}

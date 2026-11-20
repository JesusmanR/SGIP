package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.Turno;
import co.com.bspstore.sgip.modelo.TipoMedio;
import co.com.bspstore.sgip.util.Hoy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
     * no hay ciclo. <b>Por eso el cierre ({@link #bloquearParaCerrar}) toma el turno solo por
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

    /** El turno que el cierre tiene bloqueado: su identificador y la base con que se abrió. */
    public record TurnoPorCerrar(Long id, BigDecimal baseEfectivo) {
    }

    /** Un turno de la lista con lo que guarda {@code cierres_caja}: lo esperado, lo contado y la observación. */
    public record TurnoDeLista(Turno turno, BigDecimal esperado, BigDecimal contado, BigDecimal diferencia,
                               String observacion) {
    }

    /**
     * Los pagos del turno agrupados por tipo de medio. Cuenta por {@code pagos_venta.cierre_id}, el turno
     * en que el dinero entró al cajón, y no por el de la venta: un cobro contra entrega es del turno de
     * quien cobra. El pago con saldo a favor no lleva turno y no aparece. {@code ix_pagos_cierre} cubre la
     * búsqueda.
     */
    private static final String PAGOS_DEL_TURNO = """
            SELECT m.tipo, SUM(p.monto) AS total
              FROM pagos_venta p
              JOIN medios_pago m ON m.empresa_id = p.empresa_id AND m.medio_id = p.medio_id
             WHERE p.empresa_id = :empresa AND p.cierre_id = :turno
             GROUP BY m.tipo
            """;

    /**
     * Lo reembolsado en efectivo por las devoluciones del turno ({@code devoluciones.cierre_id}): el dinero que
     * salió del cajón. Los reembolsos a otro medio no lo tocan, y los que van a saldo a favor tampoco.
     */
    private static final String REEMBOLSOS_EN_EFECTIVO_DEL_TURNO = """
            SELECT COALESCE(SUM(r.monto), 0)
              FROM devoluciones d
              JOIN reembolsos r ON r.empresa_id = d.empresa_id AND r.devolucion_id = d.devolucion_id
              JOIN medios_pago m ON m.empresa_id = r.empresa_id AND m.medio_id = r.medio_id
             WHERE d.empresa_id = :empresa AND d.cierre_id = :turno AND m.tipo = 'EFECTIVO'
            """;

    /**
     * Los turnos que se abrieron en la ventana {@code [desde, hasta)}, con lo que guardó el cierre. Si el cajero
     * es nulo salen los de toda la empresa. Una sola consulta sea cual sea el número de turnos.
     */
    private static final String TURNOS_DEL_PERIODO = """
            SELECT c.cierre_id, c.usuario_id, u.nombre, c.apertura, c.base_efectivo, c.cierre,
                   c.esperado, c.contado, c.observacion
              FROM cierres_caja c
              JOIN usuarios u ON u.empresa_id = c.empresa_id AND u.usuario_id = c.usuario_id
             WHERE c.empresa_id = :empresa AND c.apertura >= :desde AND c.apertura < :hasta
               AND (:cajero IS NULL OR c.usuario_id = :cajero)
             ORDER BY c.apertura DESC, c.cierre_id DESC
            """;

    private static final String PAGOS_DEL_PERIODO = """
            SELECT p.cierre_id, m.tipo, SUM(p.monto) AS total
              FROM cierres_caja c
              JOIN pagos_venta p ON p.empresa_id = c.empresa_id AND p.cierre_id = c.cierre_id
              JOIN medios_pago m ON m.empresa_id = p.empresa_id AND m.medio_id = p.medio_id
             WHERE c.empresa_id = :empresa AND c.apertura >= :desde AND c.apertura < :hasta
               AND (:cajero IS NULL OR c.usuario_id = :cajero)
             GROUP BY p.cierre_id, m.tipo
            """;

    private static final String REEMBOLSOS_EN_EFECTIVO_DEL_PERIODO = """
            SELECT d.cierre_id, SUM(r.monto) AS total
              FROM cierres_caja c
              JOIN devoluciones d ON d.empresa_id = c.empresa_id AND d.cierre_id = c.cierre_id
              JOIN reembolsos r ON r.empresa_id = d.empresa_id AND r.devolucion_id = d.devolucion_id
              JOIN medios_pago m ON m.empresa_id = r.empresa_id AND m.medio_id = r.medio_id
             WHERE c.empresa_id = :empresa AND c.apertura >= :desde AND c.apertura < :hasta
               AND (:cajero IS NULL OR c.usuario_id = :cajero) AND m.tipo = 'EFECTIVO'
             GROUP BY d.cierre_id
            """;

    /**
     * Toma el turno abierto del cajero para cerrarlo: la fila queda bloqueada con {@code FOR UPDATE}, por su
     * clave primaria y solo por ella. Espera a las ventas, los cobros y las devoluciones en curso, que
     * la tienen con {@code FOR SHARE}, y las que lleguen después encuentran el turno cerrado. Vacío si el cajero
     * no tiene turno abierto, o si se cerró mientras esperaba.
     *
     * Son dos pasos, como en {@link #bloquearAbiertoDe}: primero se averigua cuál es el turno sin bloquear
     * nada, por {@code abierto_de}; después se toma solo la fila, por la clave primaria y repitiendo
     * {@code cierre IS NULL}. El cierre escribe {@code cierre} y con ello cambia {@code abierto_de}, de modo que
     * tocará {@code uq_cierres_abierto}; y toda operación que inserta algo del turno comprueba una clave
     * foránea hacia {@code uq_cierres_empresa}. Bloquear por alguno de esos dos índices, en vez de por
     * {@code PRIMARY}, abriría un interbloqueo contra una venta en curso. <b>La transacción que llama debe ir
     * en {@code READ_COMMITTED}</b>: el primer paso es una lectura sin bloqueo y, con la lectura repetible, abriría la
     * instantánea antes de esperar; las sumas que vienen después no verían la venta que acaba de confirmarse.
     */
    public Optional<TurnoPorCerrar> bloquearParaCerrar(Integer empresa, Integer cajero) {
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
                        SELECT cierre_id, base_efectivo FROM cierres_caja FORCE INDEX (PRIMARY)
                         WHERE cierre_id = :turno AND empresa_id = :empresa AND cierre IS NULL
                           FOR UPDATE
                        """)
                .param("turno", turno.get())
                .param("empresa", empresa)
                .query((fila, numero) -> new TurnoPorCerrar(fila.getLong("cierre_id"),
                        enPesos(fila.getBigDecimal("base_efectivo"))))
                .optional();
    }

    /** Lo cobrado en el turno por tipo de medio, solo los tipos con pagos, en pesos enteros. */
    public Map<TipoMedio, BigDecimal> pagosPorMedio(Integer empresa, Long turno) {
        Map<TipoMedio, BigDecimal> porMedio = new EnumMap<>(TipoMedio.class);
        jdbc.sql(PAGOS_DEL_TURNO)
                .param("empresa", empresa)
                .param("turno", turno)
                .query((fila, numero) -> Map.entry(TipoMedio.valueOf(fila.getString("tipo")),
                        enPesos(fila.getBigDecimal("total"))))
                .list()
                .forEach(par -> porMedio.put(par.getKey(), par.getValue()));
        return porMedio;
    }

    /** Lo reembolsado en efectivo por las devoluciones del turno, en pesos enteros (cero si no hubo). */
    public BigDecimal reembolsosEnEfectivo(Integer empresa, Long turno) {
        return enPesos(jdbc.sql(REEMBOLSOS_EN_EFECTIVO_DEL_TURNO)
                .param("empresa", empresa)
                .param("turno", turno)
                .query(BigDecimal.class)
                .single());
    }

    /**
     * Cierra el turno: escribe la hora, lo esperado, lo contado y la observación. {@code diferencia} es la
     * columna generada y no se escribe. Se llama con el turno ya bloqueado, y {@code cierre IS NULL} es la
     * última guarda: si no actualiza una fila, algo rompió el bloqueo y es un error, no un caso de negocio.
     */
    public void registrarCierre(Integer empresa, Long turno, LocalDateTime cierre, BigDecimal esperado,
                                BigDecimal contado, String observacion) {
        int filas = jdbc.sql("""
                        UPDATE cierres_caja
                           SET cierre = :cierre, esperado = :esperado, contado = :contado,
                               observacion = :observacion
                         WHERE cierre_id = :turno AND empresa_id = :empresa AND cierre IS NULL
                        """)
                .param("cierre", cierre)
                .param("esperado", esperado)
                .param("contado", contado)
                .param("observacion", observacion, Types.VARCHAR)
                .param("turno", turno)
                .param("empresa", empresa)
                .update();
        if (filas != 1) {
            throw new IllegalStateException("El cierre del turno " + turno + " no actualizó una fila: " + filas + ".");
        }
    }

    /** Los turnos abiertos en la ventana {@code [desde, hasta)}, los de un cajero o (nulo) los de toda la empresa. */
    public List<TurnoDeLista> turnosDelPeriodo(Integer empresa, LocalDateTime desde, LocalDateTime hasta,
                                               Integer cajeroONulo) {
        return jdbc.sql(TURNOS_DEL_PERIODO)
                .param("empresa", empresa)
                .param("desde", desde)
                .param("hasta", hasta)
                .param("cajero", cajeroONulo, Types.INTEGER)
                .query((fila, numero) -> new TurnoDeLista(turno(fila, numero),
                        enPesos(fila.getBigDecimal("esperado")),
                        fila.getBigDecimal("contado") == null ? null : enPesos(fila.getBigDecimal("contado")),
                        null,
                        fila.getString("observacion")))
                .list();
    }

    /** Lo cobrado por tipo de medio en cada turno de la ventana, en una sola consulta. */
    public Map<Long, Map<TipoMedio, BigDecimal>> pagosPorMedioDelPeriodo(Integer empresa, LocalDateTime desde,
                                                                         LocalDateTime hasta, Integer cajeroONulo) {
        Map<Long, Map<TipoMedio, BigDecimal>> porTurno = new HashMap<>();
        jdbc.sql(PAGOS_DEL_PERIODO)
                .param("empresa", empresa)
                .param("desde", desde)
                .param("hasta", hasta)
                .param("cajero", cajeroONulo, Types.INTEGER)
                .query((fila, numero) -> {
                    porTurno.computeIfAbsent(fila.getLong("cierre_id"), t -> new EnumMap<>(TipoMedio.class))
                            .put(TipoMedio.valueOf(fila.getString("tipo")), enPesos(fila.getBigDecimal("total")));
                    return null;
                })
                .list();
        return porTurno;
    }

    /** Lo reembolsado en efectivo en cada turno de la ventana, en una sola consulta; los turnos sin reembolsos no salen. */
    public Map<Long, BigDecimal> reembolsosEnEfectivoDelPeriodo(Integer empresa, LocalDateTime desde,
                                                               LocalDateTime hasta, Integer cajeroONulo) {
        Map<Long, BigDecimal> porTurno = new HashMap<>();
        jdbc.sql(REEMBOLSOS_EN_EFECTIVO_DEL_PERIODO)
                .param("empresa", empresa)
                .param("desde", desde)
                .param("hasta", hasta)
                .param("cajero", cajeroONulo, Types.INTEGER)
                .query((fila, numero) -> {
                    porTurno.put(fila.getLong("cierre_id"), enPesos(fila.getBigDecimal("total")));
                    return null;
                })
                .list();
        return porTurno;
    }

    private static BigDecimal enPesos(BigDecimal monto) {
        return monto.setScale(0, RoundingMode.HALF_UP);
    }

    private static Turno turno(ResultSet fila, int numero) throws SQLException {
        return new Turno(fila.getLong("cierre_id"), fila.getInt("usuario_id"), fila.getString("nombre"),
                conDesplazamiento(fila.getObject("apertura", LocalDateTime.class)),
                // En pesos enteros: la columna guarda dos decimales que siempre son cero.
                enPesos(fila.getBigDecimal("base_efectivo")),
                conDesplazamiento(fila.getObject("cierre", LocalDateTime.class)));
    }

    private static OffsetDateTime conDesplazamiento(LocalDateTime horaDeBogota) {
        return horaDeBogota == null ? null : horaDeBogota.atZone(Hoy.ZONA).toOffsetDateTime();
    }
}

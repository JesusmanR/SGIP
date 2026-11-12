package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.SolicitudApertura;
import co.com.bspstore.sgip.api.dto.Turno;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.datos.RepositorioTurnos;
import co.com.bspstore.sgip.util.Hoy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * El turno de caja (RF-45): abrirlo, saber cuál es el del usuario en sesión y exigir que
 * haya uno antes de vender, cobrar o devolver.
 *
 * La empresa y el cajero salen del contexto y no de un parámetro (RN-16): cada cajero
 * tiene a lo sumo un turno abierto, el suyo.
 *
 * <h2>La hora</h2>
 *
 * La apertura sale del {@code Clock} de la aplicación y se guarda como la hora de pared
 * de Bogotá ({@code LocalDateTime.ofInstant(reloj.instant(), Hoy.ZONA)}), al segundo,
 * que es lo que ve la sesión de la base (ADR-0030). No se usa {@code LocalDateTime.now(reloj)}:
 * ese reloj es el de la JVM, que en la CI y en un servidor es UTC. Tampoco el
 * {@code CURRENT_TIMESTAMP} del motor. <b>El cierre (tarea 10) debe escribir {@code cierre}
 * del mismo modo</b>: con otra zona, {@code ck_cierre_fechas} ({@code cierre >= apertura})
 * podría fallar y la petición acabaría en un 500.
 *
 * <h2>Una apertura por cajero</h2>
 *
 * La garantiza el índice único de la V43, no la lectura previa: esa lectura solo evita
 * ir al motor en el caso corriente. Si dos aperturas llegan a la vez, ninguna ve a la
 * otra, y la segunda choca con el índice ({@link DuplicateKeyException}); se traduce a
 * {@link TurnoYaAbiertoException} (409). Solo ese choque se traduce: otra violación de
 * integridad (una clave foránea rota) es un error de verdad y no «ya hay un turno».
 *
 * <h2>Orden global de bloqueos de la caja</h2>
 *
 * Este bean es el primer eslabón:
 * <ol>
 *   <li>el <b>turno</b> ({@code cierres_caja}): {@code FOR SHARE} en {@link #exigirTurnoAbierto()}
 *       y {@code FOR UPDATE} en el cierre (tarea 10);</li>
 *   <li>la <b>venta</b> ({@code FOR UPDATE}), solo en devoluciones y cobros contra entrega;</li>
 *   <li>los <b>productos</b>, por {@code producto_id} ascendente;</li>
 *   <li>el <b>saldo del cliente</b>;</li>
 *   <li>el <b>consecutivo</b>;</li>
 *   <li>el <b>kárdex</b>.</li>
 * </ol>
 * Quien toma uno de la lista no toma después uno anterior. La caja no toma la fila de la
 * empresa.
 */
@Service
public class ServicioTurnos {

    /** Lo que cabe en {@code DECIMAL(14,2)} sin decimales: 12 dígitos. */
    private static final BigDecimal BASE_MAXIMA = new BigDecimal("999999999999");

    private static final String BASE_NO_VALIDA =
            "La base de efectivo debe ser un monto en pesos enteros, de cero en adelante y de hasta 12 dígitos.";

    private final RepositorioTurnos turnos;
    private final Clock reloj;

    public ServicioTurnos(RepositorioTurnos turnos, Clock reloj) {
        this.turnos = turnos;
        this.reloj = reloj;
    }

    /**
     * Abre el turno del usuario en sesión con la base de efectivo indicada. 409 si ya
     * tiene uno abierto; 400 si la base no es un monto en pesos enteros de cero en adelante.
     */
    @Transactional
    public Turno abrir(SolicitudApertura solicitud) {
        BigDecimal base = validarBase(solicitud);
        Integer empresa = ContextoEmpresa.empresaActual();
        Integer cajero = cajeroEnSesion();
        if (turnos.abiertoDe(empresa, cajero).isPresent()) {
            throw new TurnoYaAbiertoException();
        }
        Long id;
        try {
            id = turnos.insertar(empresa, cajero, apertura(), base);
        } catch (DuplicateKeyException e) {
            // Ganó otra apertura del mismo cajero entre la lectura y este INSERT.
            throw new TurnoYaAbiertoException();
        }
        return turnos.buscar(empresa, id).orElseThrow(() -> new IllegalStateException(
                "El turno " + id + " no se encontró justo después de abrirlo."));
    }

    /** El turno abierto del usuario en sesión; vacío si no tiene. El de otro cajero nunca lo es. */
    @Transactional(readOnly = true)
    public Optional<Turno> actual() {
        return turnos.abiertoDe(ContextoEmpresa.empresaActual(), cajeroEnSesion());
    }

    /**
     * El identificador del turno abierto del usuario en sesión (el {@code cierre_id} que
     * guardan la venta, el cobro o la devolución), o {@link SinTurnoAbiertoException} (409).
     *
     * Lee la fila con {@code FOR SHARE} y por eso exige estar dentro de la transacción de
     * la operación ({@link Propagation#MANDATORY}): el cierre bloquea el mismo turno con
     * {@code FOR UPDATE} y espera a que esas operaciones terminen; las que lleguen después
     * del cierre no encuentran turno abierto y reciben el 409. Fuera de una transacción el
     * bloqueo se soltaría al terminar la sentencia y la garantía sería falsa.
     *
     * Es el primer bloqueo de la caja (véase el orden en la documentación de la clase): se
     * llama antes que cualquiera de los demás.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long exigirTurnoAbierto() {
        return turnos.bloquearAbiertoDe(ContextoEmpresa.empresaActual(), cajeroEnSesion())
                .orElseThrow(SinTurnoAbiertoException::new);
    }

    /**
     * Pesos enteros, de cero en adelante y de hasta 12 dígitos. Devuelve la base sin
     * decimales ni exponente, lista para guardar.
     */
    private static BigDecimal validarBase(SolicitudApertura solicitud) {
        BigDecimal base = solicitud == null ? null : solicitud.baseEfectivo();
        if (base == null || base.signum() < 0) {
            throw new DatoDeTurnoInvalidoException(BASE_NO_VALIDA);
        }
        // Se mira la forma sin ceros sobrantes antes de reescalar: 50000.00 es válida,
        // 50000.01 no, y 1E+20 se rechaza por grande sin llegar a expandirse.
        BigDecimal sinCerosSobrantes = base.signum() == 0 ? BigDecimal.ZERO : base.stripTrailingZeros();
        if (sinCerosSobrantes.scale() > 0 || sinCerosSobrantes.compareTo(BASE_MAXIMA) > 0) {
            throw new DatoDeTurnoInvalidoException(BASE_NO_VALIDA);
        }
        return sinCerosSobrantes.setScale(0);
    }

    private static Integer cajeroEnSesion() {
        return ContextoEmpresa.actual().orElseThrow(() -> new IllegalStateException(
                "No hay usuario autenticado en el contexto. Revise la cadena de seguridad.")).usuarioId();
    }

    /** La hora de pared de Bogotá, al segundo: la columna es un DATETIME sin fracción. */
    private LocalDateTime apertura() {
        return LocalDateTime.ofInstant(reloj.instant(), Hoy.ZONA).truncatedTo(ChronoUnit.SECONDS);
    }
}

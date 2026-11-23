package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.ResumenDeTurno;
import co.com.bspstore.sgip.api.dto.SolicitudApertura;
import co.com.bspstore.sgip.api.dto.SolicitudCierre;
import co.com.bspstore.sgip.api.dto.Turno;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.datos.RepositorioTurnos;
import co.com.bspstore.sgip.datos.RepositorioTurnos.TurnoDeLista;
import co.com.bspstore.sgip.datos.RepositorioTurnos.TurnoPorCerrar;
import co.com.bspstore.sgip.modelo.TipoMedio;
import co.com.bspstore.sgip.util.Hoy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
 * La apertura sale del {@code Clock} de la aplicación con {@link Hoy#ahora(Clock)}: la hora de
 * pared de Bogotá, al segundo, que es la que ve la sesión de la base (ADR-0030). No se usa
 * {@code LocalDateTime.now(reloj)}: ese reloj es el de la JVM, que en la CI y en un servidor es
 * UTC, y guardaría una hora 5 horas adelantada sin que nada falle. Tampoco el
 * {@code CURRENT_TIMESTAMP} del motor. El cierre escribe {@code cierre} del mismo modo: con la zona
 * de la JVM, el turno se cerraría «5 horas en el futuro» en silencio (el {@code ck_cierre_fechas},
 * {@code cierre >= apertura}, solo saltaría con una JVM al oeste de Bogotá).
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
 *       y {@code FOR UPDATE} en {@link #cerrar};</li>
 *   <li>la <b>venta</b> ({@code FOR UPDATE}), solo en devoluciones y cobros contra entrega;</li>
 *   <li>los <b>productos</b>, por {@code producto_id} ascendente;</li>
 *   <li>el <b>saldo del cliente</b>;</li>
 *   <li>el <b>consecutivo</b>;</li>
 *   <li>el <b>kárdex</b>.</li>
 * </ol>
 * Quien toma uno de la lista no toma después uno anterior. La caja no pide la fila de la
 * empresa.
 *
 * El turno se bloquea siempre <b>por su clave primaria</b> ({@code cierre_id}), en las dos
 * puntas: {@code FOR SHARE} aquí y {@code FOR UPDATE} en el cierre. <b>El cierre toma el
 * turno solo por {@code PRIMARY}, con {@code FORCE INDEX (PRIMARY)}</b>, y nunca por
 * {@code uq_cierres_abierto} ni por {@code uq_cierres_empresa}. Dos razones, la misma
 * enfermedad: (1) el cierre escribe {@code cierre} y con ello cambia {@code abierto_de}, de
 * modo que tiene que tocar {@code uq_cierres_abierto}; (2) toda inserción que referencia el
 * turno (venta, devolución, cobro) comprueba la clave foránea compuesta
 * {@code fk_ventas_cierre_emp} (V11), y para ello InnoDB toma un bloqueo compartido sobre la
 * entrada de {@code uq_cierres_empresa}. Un cierre que ya tuviera una de esas entradas, y una
 * operación que tuviera el turno y fuera por la entrada, se esperarían mutuamente: un
 * interbloqueo, y el motor mata a una de las dos.
 */
@Service
public class ServicioTurnos {

    /** Lo que cabe en {@code DECIMAL(14,2)} sin decimales: 12 dígitos. */
    private static final BigDecimal MONTO_MAXIMO = new BigDecimal("999999999999");

    /** Lo que cabe en {@code cierres_caja.observacion}. */
    private static final int OBSERVACION_MAXIMA = 200;

    /** Una lista de más de un año de turnos no es una consulta de caja: se pide por partes. */
    private static final int DIAS_MAXIMOS_DE_LISTA = 366;

    private static final String BASE_NO_VALIDA =
            "La base de efectivo debe ser un monto en pesos enteros, de cero en adelante y de hasta 12 dígitos.";

    private static final String CONTADO_NO_VALIDO =
            "El efectivo contado debe ser un monto en pesos enteros, de cero en adelante y de hasta 12 dígitos.";

    private static final String SIN_TURNO_PARA_CERRAR = "No tienes un turno abierto para cerrar.";

    private static final String OBSERVACION_MUY_LARGA = "La observación admite hasta 200 caracteres.";

    private static final String DIFERENCIA_SIN_EXPLICAR =
            "El efectivo contado no coincide con el esperado: explica la diferencia en la observación.";

    private static final String RANGO_NO_VALIDO =
            "Indica las fechas desde y hasta, la primera no posterior a la segunda y con un año a lo sumo entre ellas.";

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
            id = turnos.insertar(empresa, cajero, Hoy.ahora(reloj), base);
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
     * El turno abierto del usuario en sesión, con el mismo bloqueo {@code FOR SHARE} que
     * {@link #exigirTurnoAbierto()}, pero <b>sin fallar</b> si no tiene: vacío. Es para las operaciones
     * que solo necesitan turno si mueven dinero y no pueden saberlo antes de bloquear la venta (la
     * devolución: una venta contra entrega sin cobrar no pagó nada). El orden de bloqueos de la caja pone
     * el turno antes que la venta, así que se toma primero y quien llama decide después si lo exige.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Long> intentarTurnoAbierto() {
        return turnos.bloquearAbiertoDe(ContextoEmpresa.empresaActual(), cajeroEnSesion());
    }

    /**
     * Cierra el turno del usuario en sesión con el efectivo que contó. Devuelve el resumen del turno ya cerrado.
     *
     * Lo primero que se toma es el turno, {@code FOR UPDATE} y por su clave primaria
     * ({@link RepositorioTurnos#bloquearParaCerrar}): espera a las ventas, los cobros y las devoluciones en
     * curso, que lo tienen {@code FOR SHARE}, y las que lleguen después reciben el 409 de «sin turno abierto».
     * Con el turno bloqueado nadie más puede añadirle un pago o un reembolso, así que lo que se suma a
     * continuación es todo lo que el turno tendrá, y {@code esperado} y {@code contado} se guardan en esta misma
     * transacción. Por eso la transacción va en {@code READ_COMMITTED}: cada lectura ve lo último confirmado,
     * también la venta que el cierre acaba de esperar.
     *
     * <ul>
     *   <li>400 si el contado no son pesos enteros de cero en adelante, si la observación pasa de 200 caracteres,
     *       o si contado y esperado difieren y no hay observación que lo explique (el turno sigue abierto);</li>
     *   <li>409 si el usuario no tiene turno abierto, o si otro cierre del mismo turno llegó antes.</li>
     * </ul>
     *
     * <h2>Cuánto se espera en el cajón</h2>
     *
     * La base, más los pagos en efectivo con el turno ({@code pagos_venta.cierre_id}: ventas y cobros contra
     * entrega), menos los reembolsos en efectivo de las devoluciones del turno. El cambio que se entregó no
     * cuenta, porque {@code monto} ya es el neto; el pago con saldo a favor no lleva turno.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResumenDeTurno cerrar(SolicitudCierre solicitud) {
        BigDecimal contado = pesosEnteros(solicitud == null ? null : solicitud.efectivoContado(), CONTADO_NO_VALIDO);
        String observacion = observacion(solicitud.observacion());
        Integer empresa = ContextoEmpresa.empresaActual();

        TurnoPorCerrar turno = turnos.bloquearParaCerrar(empresa, cajeroEnSesion())
                .orElseThrow(() -> new SinTurnoAbiertoException(SIN_TURNO_PARA_CERRAR));
        Map<TipoMedio, BigDecimal> porMedio = turnos.pagosPorMedio(empresa, turno.id());
        BigDecimal esperado = esperado(turno.baseEfectivo(), porMedio, turnos.reembolsosEnEfectivo(empresa, turno.id()));
        BigDecimal diferencia = contado.subtract(esperado);
        if (diferencia.signum() != 0 && observacion == null) {
            throw new DatoDeTurnoInvalidoException(DIFERENCIA_SIN_EXPLICAR);
        }

        turnos.registrarCierre(empresa, turno.id(), Hoy.ahora(reloj), esperado, contado, observacion);
        Turno cerrado = turnos.buscar(empresa, turno.id()).orElseThrow(() -> new IllegalStateException(
                "El turno " + turno.id() + " no se encontró justo después de cerrarlo."));
        return new ResumenDeTurno(cerrado.id(), cerrado.cajero(), cerrado.apertura(), cerrado.cierre(),
                cerrado.baseEfectivo(), esperado, contado, diferencia, porMedio, observacion);
    }

    /**
     * El resumen del turno abierto del usuario en sesión, con el efectivo esperado hasta este momento, sin cerrar
     * nada ni bloquear nada. 404 si no tiene turno abierto. Tres consultas, sean cuantos sean los pagos; en una
     * sola transacción de solo lectura, para que las tres vean el mismo instante.
     */
    @Transactional(readOnly = true)
    public ResumenDeTurno resumenActual() {
        Integer empresa = ContextoEmpresa.empresaActual();
        Turno turno = turnos.abiertoDe(empresa, cajeroEnSesion())
                .orElseThrow(() -> new RecursoNoEncontradoException("No tienes un turno de caja abierto."));
        Map<TipoMedio, BigDecimal> porMedio = turnos.pagosPorMedio(empresa, turno.id());
        BigDecimal esperado = esperado(turno.baseEfectivo(), porMedio, turnos.reembolsosEnEfectivo(empresa, turno.id()));
        return new ResumenDeTurno(turno.id(), turno.cajero(), turno.apertura(), null, turno.baseEfectivo(), esperado,
                null, null, porMedio, null);
    }

    /**
     * Los turnos abiertos entre las dos fechas (inclusive, por el día de Bogotá de la apertura), del más reciente al
     * más antiguo. El administrador ve los de todos los cajeros de la empresa; cualquier otro rol, solo los suyos: lo
     * decide el servidor y las rutas no reciben cajero. Un turno cerrado trae lo que quedó guardado al cerrar; uno
     * abierto, el esperado de este momento, sin contado ni diferencia. Tres consultas sea cual sea el número de turnos.
     *
     * 400 si falta una fecha, si van invertidas o si el rango pasa de un año.
     */
    @Transactional(readOnly = true)
    public List<ResumenDeTurno> listar(LocalDate desde, LocalDate hasta) {
        if (desde == null || hasta == null || hasta.isBefore(desde)
                || ChronoUnit.DAYS.between(desde, hasta) > DIAS_MAXIMOS_DE_LISTA) {
            throw new DatoDeTurnoInvalidoException(RANGO_NO_VALIDO);
        }
        Integer empresa = ContextoEmpresa.empresaActual();
        Integer cajeroONulo = ContextoEmpresa.esAdministrador() ? null : cajeroEnSesion();
        LocalDateTime desdeInclusive = desde.atStartOfDay();
        LocalDateTime hastaExclusive = hasta.plusDays(1).atStartOfDay();

        List<TurnoDeLista> lista = turnos.turnosDelPeriodo(empresa, desdeInclusive, hastaExclusive, cajeroONulo);
        Map<Long, Map<TipoMedio, BigDecimal>> pagos =
                turnos.pagosPorMedioDelPeriodo(empresa, desdeInclusive, hastaExclusive, cajeroONulo);
        Map<Long, BigDecimal> reembolsos =
                turnos.reembolsosEnEfectivoDelPeriodo(empresa, desdeInclusive, hastaExclusive, cajeroONulo);

        return lista.stream().map(fila -> {
            Turno turno = fila.turno();
            Map<TipoMedio, BigDecimal> porMedio = pagos.getOrDefault(turno.id(), new EnumMap<>(TipoMedio.class));
            if (turno.cierre() != null) {
                // La diferencia es la columna generada del motor, no una resta repetida aquí: es la que se guardó.
                return new ResumenDeTurno(turno.id(), turno.cajero(), turno.apertura(), turno.cierre(),
                        turno.baseEfectivo(), fila.esperado(), fila.contado(), fila.diferencia(), porMedio,
                        fila.observacion());
            }
            BigDecimal esperado = esperado(turno.baseEfectivo(), porMedio,
                    reembolsos.getOrDefault(turno.id(), BigDecimal.ZERO));
            return new ResumenDeTurno(turno.id(), turno.cajero(), turno.apertura(), null, turno.baseEfectivo(),
                    esperado, null, null, porMedio, null);
        }).toList();
    }

    private static BigDecimal validarBase(SolicitudApertura solicitud) {
        return pesosEnteros(solicitud == null ? null : solicitud.baseEfectivo(), BASE_NO_VALIDA);
    }

    private static BigDecimal esperado(BigDecimal base, Map<TipoMedio, BigDecimal> pagosPorMedio,
                                       BigDecimal reembolsosEnEfectivo) {
        return base.add(pagosPorMedio.getOrDefault(TipoMedio.EFECTIVO, BigDecimal.ZERO))
                .subtract(reembolsosEnEfectivo);
    }

    /** La observación sin espacios sobrantes; en blanco es como no haberla escrito. Cabe en la columna. */
    private static String observacion(String texto) {
        String limpia = texto == null ? "" : texto.trim();
        if (limpia.length() > OBSERVACION_MAXIMA) {
            throw new DatoDeTurnoInvalidoException(OBSERVACION_MUY_LARGA);
        }
        return limpia.isEmpty() ? null : limpia;
    }

    /**
     * Pesos enteros, de cero en adelante y de hasta 12 dígitos. Devuelve el monto sin decimales ni
     * exponente, listo para guardar. El mensaje es fijo y no repite lo enviado.
     */
    private static BigDecimal pesosEnteros(BigDecimal monto, String mensaje) {
        if (monto == null || monto.signum() < 0) {
            throw new DatoDeTurnoInvalidoException(mensaje);
        }
        // Se mira la forma sin ceros sobrantes antes de reescalar: 50000.00 es válida,
        // 50000.01 no, y 1E+20 se rechaza por grande sin llegar a expandirse.
        BigDecimal sinCerosSobrantes = monto.signum() == 0 ? BigDecimal.ZERO : monto.stripTrailingZeros();
        if (sinCerosSobrantes.scale() > 0 || sinCerosSobrantes.compareTo(MONTO_MAXIMO) > 0) {
            throw new DatoDeTurnoInvalidoException(mensaje);
        }
        return sinCerosSobrantes.setScale(0);
    }

    private static Integer cajeroEnSesion() {
        return ContextoEmpresa.actual().orElseThrow(() -> new IllegalStateException(
                "No hay usuario autenticado en el contexto. Revise la cadena de seguridad.")).usuarioId();
    }
}

package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.CobroRegistrado;
import co.com.bspstore.sgip.api.dto.DomicilioPendiente;
import co.com.bspstore.sgip.api.dto.EntregaRegistrada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioDomicilios;
import co.com.bspstore.sgip.datos.RepositorioDomicilios.EstadoDelDomicilio;
import co.com.bspstore.sgip.datos.RepositorioDomicilios.FilaDeDomicilio;
import co.com.bspstore.sgip.datos.RepositorioDomicilios.VentaPorCobrar;
import co.com.bspstore.sgip.datos.RepositorioVentas;
import co.com.bspstore.sgip.logica.ValidadorDePagos.Validados;
import co.com.bspstore.sgip.util.Hoy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * Los domicilios de la caja (RF-44, RF-69): las listas de lo que falta por despachar y por cobrar, la
 * marca de entrega y el cobro posterior de un domicilio contra entrega. Los domicilios los hace un
 * tercero: aquí solo se sabe a dónde iba, quién lo entregó y cuándo, y si el dinero ya llegó.
 *
 * <h2>Las listas</h2>
 *
 * Una consulta cada una, de la venta más antigua a la más reciente y con un tope
 * ({@value #TOPE_DE_LA_LISTA}): el número de consultas no depende de cuántas filas haya. La antigüedad
 * se cuenta en días de Bogotá con el reloj de la aplicación ({@link Hoy}), nunca con el
 * {@code CURDATE()} del motor. Traen datos personales del cliente: son de estas rutas y no se escriben
 * en ningún registro del servidor (este servicio no registra nada de lo que lee).
 *
 * <h2>La entrega</h2>
 *
 * Quién la marcó y cuándo (la hora de Bogotá del reloj de la aplicación). La actualización es
 * condicionada a que siga sin entregar ({@code entregado_en IS NULL}) y no se lee nada antes: con dos
 * entregas a la vez, la segunda espera la fila de la primera y no encuentra nada que cambiar (409).
 *
 * <h2>El cobro posterior</h2>
 *
 * Una venta a domicilio contra entrega nace por cobrar y sin pagos. Cuando llega el dinero se registra
 * por el <b>saldo por cobrar completo</b> (un cobro parcial no se admite, decisión del 2026-11-09) con
 * las mismas reglas de pago de la venta ({@link ValidadorDePagos}), y cuenta en el <b>turno abierto de
 * quien lo registra</b> en ese momento, no en el turno de la venta.
 *
 * <b>Bloqueos</b>, en el orden global de la caja: el turno ({@code FOR SHARE}, que exige que quien
 * cobra tenga uno abierto) y después la venta ({@code FOR UPDATE}, por clave primaria). No toma
 * productos ni consecutivos: el cobro no mueve inventario ni numera nada. Dos cobros de la misma venta
 * se ponen en fila en el bloqueo de la venta; el segundo la encuentra pagada y recibe el 409. Es el
 * bloqueo, y no una condición de la actualización, lo que hace única la venta.
 */
@Service
public class ServicioDomicilios {

    /** Lo más que devuelve una lista: las más antiguas, que son las que más urge atender. */
    static final int TOPE_DE_LA_LISTA = 200;

    static final String NO_ES_CONTRA_ENTREGA = "Esta venta no se cobra contra entrega.";
    static final String YA_ESTA_PAGADA = "Esta venta ya está pagada.";
    static final String VENTA_ANULADA = "Esta venta está anulada.";
    static final String YA_SE_ENTREGO = "Este domicilio ya fue entregado.";
    static final String NO_EXISTE = "El domicilio no existe.";
    static final String VENTA_NO_EXISTE = "La venta no existe.";

    private enum Estado {POR_DESPACHAR, POR_COBRAR}

    private final RepositorioDomicilios domicilios;
    private final RepositorioVentas repositorioVentas;
    private final ServicioTurnos turnos;
    private final ValidadorDePagos validador;
    private final Clock reloj;

    public ServicioDomicilios(RepositorioDomicilios domicilios, RepositorioVentas repositorioVentas,
                              ServicioTurnos turnos, ValidadorDePagos validador, Clock reloj) {
        this.domicilios = domicilios;
        this.repositorioVentas = repositorioVentas;
        this.turnos = turnos;
        this.validador = validador;
        this.reloj = reloj;
    }

    // ------------------------------------------------------------ las listas

    /**
     * Los domicilios {@code POR_DESPACHAR} (sin entregar) o {@code POR_COBRAR} (contra entrega sin
     * cobrar), de la empresa de la sesión. El estado es obligatorio: uno que no existe es un 400 y no
     * una lista vacía que parezca decir que no hay nada.
     */
    @Transactional(readOnly = true)
    public List<DomicilioPendiente> listar(String estado) {
        Estado pedido = interpretar(estado);
        Integer empresa = ContextoEmpresa.empresaActual();
        List<FilaDeDomicilio> filas = pedido == Estado.POR_COBRAR
                ? domicilios.listarPorCobrar(empresa, TOPE_DE_LA_LISTA)
                : domicilios.listarPorDespachar(empresa, TOPE_DE_LA_LISTA);
        return filas.stream().map(this::pendiente).toList();
    }

    private DomicilioPendiente pendiente(FilaDeDomicilio f) {
        BigDecimal porCobrar = "PENDIENTE".equals(f.estadoPago()) ? saldoPorCobrar(f.total()) : BigDecimal.ZERO;
        return new DomicilioPendiente(f.ventaId(), f.consecutivo(), f.fecha().atZone(Hoy.ZONA).toOffsetDateTime(),
                f.cliente(), f.direccion(), f.ciudad(), f.telefono(), f.recibe(), f.contraEntrega(), f.estadoPago(),
                f.total(), porCobrar, antiguedadEnDias(f.fecha(), reloj));
    }

    private static Estado interpretar(String texto) {
        String limpio = texto == null ? "" : texto.trim().toUpperCase(Locale.ROOT);
        for (Estado e : Estado.values()) {
            if (e.name().equals(limpio)) {
                return e;
            }
        }
        throw new FiltroInvalidoException("Indica el estado de los domicilios: POR_DESPACHAR o POR_COBRAR.");
    }

    // ------------------------------------------------------------ lo que se debe

    /**
     * Lo que falta por cobrar de una venta a domicilio: <b>el único sitio</b> donde se calcula, y lo usan
     * el cobro y las listas. Hoy es el total de la venta; la tarea de devoluciones lo reducirá por lo
     * devuelto (una devolución parcial rebaja lo que queda por cobrar) sin tocar a quienes lo llaman.
     */
    static BigDecimal saldoPorCobrar(BigDecimal totalDeLaVenta) {
        return totalDeLaVenta;
    }

    /**
     * Los días de Bogotá desde que se vendió hasta hoy según el reloj de la aplicación; nunca negativos.
     */
    static long antiguedadEnDias(LocalDateTime fecha, Clock reloj) {
        return Math.max(0, ChronoUnit.DAYS.between(fecha.toLocalDate(), Hoy.de(reloj)));
    }

    // ------------------------------------------------------------ la entrega

    /**
     * Marca el domicilio como entregado por quien está en sesión y ahora. 404 si no es de la empresa (o
     * la venta no es un domicilio), 409 si ya estaba entregado o si la venta se anuló.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EntregaRegistrada entregar(Long ventaId) {
        if (ventaId == null || ventaId <= 0) {
            throw new RecursoNoEncontradoException(NO_EXISTE);
        }
        Integer empresa = ContextoEmpresa.empresaActual();
        Integer quien = usuarioEnSesion();
        LocalDateTime cuando = Hoy.ahora(reloj);
        int filas = domicilios.marcarEntregado(empresa, ventaId, cuando, quien);
        EstadoDelDomicilio estado = domicilios.estadoDelDomicilio(empresa, ventaId)
                .orElseThrow(() -> new RecursoNoEncontradoException(NO_EXISTE));
        if (filas == 0) {
            // No cambió nada: o ya estaba entregado (lo normal, y es quien llegó segundo) o la venta se anuló.
            throw new EstadoNoAdmiteLaOperacionException(estado.entregado() ? YA_SE_ENTREGO : VENTA_ANULADA);
        }
        if (!estado.ventaVigente()) {
            // Se marcó, pero la venta está anulada: la transacción se deshace y no queda marca.
            throw new EstadoNoAdmiteLaOperacionException(VENTA_ANULADA);
        }
        return new EntregaRegistrada(ventaId, cuando.atZone(Hoy.ZONA).toOffsetDateTime(), quien);
    }

    // ------------------------------------------------------------ el cobro

    /**
     * Cobra el saldo de un domicilio contra entrega con los pagos dados, en el turno abierto de quien
     * cobra. 400 si falta algún pago, si algún pago no sirve o si no suman exactamente el saldo (ni
     * parcial ni de más); 404 si la venta no es de la empresa; 409 si quien cobra no tiene turno abierto,
     * si la venta no es contra entrega, si ya está pagada o si está anulada.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CobroRegistrado cobrar(Long ventaId, List<PagoSolicitado> pedidos) {
        if (ventaId == null || ventaId <= 0) {
            throw new RecursoNoEncontradoException(VENTA_NO_EXISTE);
        }
        Integer empresa = ContextoEmpresa.empresaActual();
        if (pedidos == null || pedidos.isEmpty()) {
            throw new VentaInvalidaException("El cobro necesita al menos un pago.");
        }
        List<PagoSolicitado> pagos = NormalizadorDeVenta.pagos(pedidos);
        LocalDateTime ahora = Hoy.ahora(reloj);

        // 1. El turno de quien cobra (FOR SHARE). 2. La venta (FOR UPDATE). Ningún otro bloqueo.
        Long turno = turnos.exigirTurnoAbierto();
        VentaPorCobrar venta = domicilios.bloquearVenta(empresa, ventaId)
                .orElseThrow(() -> new RecursoNoEncontradoException(VENTA_NO_EXISTE));
        if (!venta.contraEntrega()) {
            throw new EstadoNoAdmiteLaOperacionException(NO_ES_CONTRA_ENTREGA);
        }
        if (!"REGISTRADA".equals(venta.estado())) {
            throw new EstadoNoAdmiteLaOperacionException(VENTA_ANULADA);
        }
        if (!"PENDIENTE".equals(venta.estadoPago())) {
            throw new EstadoNoAdmiteLaOperacionException(YA_ESTA_PAGADA);
        }

        BigDecimal saldo = saldoPorCobrar(venta.total());
        Validados validados = validador.validar(empresa, pagos, saldo, ValidadorDePagos.NO_SUMAN_EL_SALDO);
        repositorioVentas.insertarPagos(empresa, ventaId, turno, usuarioEnSesion(), ahora, validados.pagos());
        domicilios.marcarPagada(empresa, ventaId);
        return new CobroRegistrado(ventaId, venta.consecutivo(), saldo, validados.cambio());
    }

    private static Integer usuarioEnSesion() {
        return ContextoEmpresa.actual().map(UsuarioAutenticado::usuarioId).orElseThrow(() -> new IllegalStateException(
                "No hay usuario autenticado en el contexto. Revise la cadena de seguridad."));
    }
}

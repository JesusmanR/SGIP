package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.Cliente;
import co.com.bspstore.sgip.api.dto.DevolucionRegistrada;
import co.com.bspstore.sgip.api.dto.MedioDePago;
import co.com.bspstore.sgip.api.dto.Reembolso;
import co.com.bspstore.sgip.api.dto.SolicitudDevolucion;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioClientes;
import co.com.bspstore.sgip.datos.RepositorioConsecutivos;
import co.com.bspstore.sgip.datos.RepositorioDevoluciones;
import co.com.bspstore.sgip.datos.RepositorioDevoluciones.NuevaDevolucion;
import co.com.bspstore.sgip.datos.RepositorioDevoluciones.VentaADevolver;
import co.com.bspstore.sgip.datos.RepositorioInventario;
import co.com.bspstore.sgip.datos.RepositorioInventario.Reingreso;
import co.com.bspstore.sgip.datos.RepositorioVentas;
import co.com.bspstore.sgip.datos.RepositorioVentas.ProductoBloqueado;
import co.com.bspstore.sgip.logica.devolucion.CalculoDeDevolucion;
import co.com.bspstore.sgip.logica.devolucion.CalculoDeDevolucion.LineaVendida;
import co.com.bspstore.sgip.logica.devolucion.CalculoDeDevolucion.Resultado;
import co.com.bspstore.sgip.logica.devolucion.CalculoDeDevolucion.UnidadesDevueltas;
import co.com.bspstore.sgip.modelo.TipoConsecutivo;
import co.com.bspstore.sgip.modelo.TipoMedio;
import co.com.bspstore.sgip.modelo.TipoMovimiento;
import co.com.bspstore.sgip.util.Hoy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * La devolución o anulación de una venta dentro de UNA transacción (RF-68): reintegra la existencia,
 * deja el kárdex, reembolsa (a un medio o al saldo del cliente), actualiza la venta y la numera. Es el
 * bean transaccional que {@link ServicioDevoluciones} llama después de validar el PIN; son beans
 * distintos para que el proxy de Spring aplique la transacción.
 *
 * <h2>Orden de bloqueos (ADR-0030)</h2>
 *
 * <ol>
 *   <li>el <b>turno</b> del cajero, {@code FOR SHARE}, <b>sin fallar si no tiene</b>
 *       ({@link ServicioTurnos#intentarTurnoAbierto()}): solo hace falta si la devolución mueve dinero, y
 *       eso no se sabe hasta mirar la venta, que va después. Se toma siempre primero para no invertir el
 *       orden;</li>
 *   <li>la <b>venta</b>, {@code FOR UPDATE}: dos devoluciones de la misma venta (o una devolución y el
 *       cobro contra entrega de esa venta) se ponen en fila aquí, y la segunda lee lo que la primera
 *       confirmó;</li>
 *   <li>los <b>productos</b> devueltos, por {@code producto_id} ascendente y con {@code FORCE INDEX (PRIMARY)};</li>
 *   <li>el <b>saldo del cliente</b>, si algún reembolso es a saldo;</li>
 *   <li>el <b>consecutivo</b> {@code DEVOLUCION}, el último antes de insertar;</li>
 *   <li>el <b>kárdex</b>.</li>
 * </ol>
 *
 * Todo lo que se puede decidir sin el consecutivo (qué se devuelve, cuánto vale, los reembolsos) se
 * decide antes, de modo que el consecutivo se tiene el menor tiempo posible.
 *
 * <h2>READ COMMITTED</h2>
 *
 * Como la venta: lo ya devuelto se lee <b>después</b> de bloquear la venta y, en READ COMMITTED, ve lo que
 * la devolución hermana confirmó mientras esta esperaba. Con REPEATABLE READ la instantánea podría ser
 * anterior al bloqueo y dos devoluciones pasarían de lo vendido.
 *
 * <h2>El dinero</h2>
 *
 * <ul>
 *   <li>Venta {@code PAGADA}: los reembolsos suman exactamente lo que valen las unidades y la devolución
 *       se registra en el turno abierto de quien la hace (409 si no tiene), también si todo va al saldo.</li>
 *   <li>Venta {@code PENDIENTE} (contra entrega sin cobrar): no se pagó nada, no hay reembolso, no se exige
 *       turno y {@code cierre_id} queda nulo. Lo devuelto reduce lo que queda por cobrar; si ya no queda
 *       nada, la venta se anula.</li>
 * </ul>
 */
@Service
public class RegistradorDeDevolucion {

    static final String VENTA_NO_EXISTE = "La venta no existe.";
    static final String YA_ESTA_ANULADA = "La venta ya está anulada.";
    static final String NADA_QUE_DEVOLVER = "No queda nada por devolver en esta venta.";
    static final String SIN_DINERO_QUE_REEMBOLSAR = "Una venta por cobrar no tiene dinero que reembolsar.";
    static final String NO_SUMAN_EL_VALOR = "Los reembolsos deben sumar exactamente el valor devuelto.";
    static final String MEDIO_NO_DISPONIBLE = "El medio de pago no está disponible.";
    static final String SIN_REFERENCIA = "El reembolso con tarjeta o transferencia necesita su referencia.";

    private final ServicioTurnos turnos;
    private final RepositorioDevoluciones devoluciones;
    private final RepositorioVentas ventas;
    private final RepositorioConsecutivos consecutivos;
    private final RepositorioInventario inventario;
    private final RepositorioClientes clientes;
    private final ServicioSaldoAFavor saldos;
    private final Clock reloj;

    public RegistradorDeDevolucion(ServicioTurnos turnos, RepositorioDevoluciones devoluciones,
                                   RepositorioVentas ventas, RepositorioConsecutivos consecutivos,
                                   RepositorioInventario inventario, RepositorioClientes clientes,
                                   ServicioSaldoAFavor saldos, Clock reloj) {
        this.turnos = turnos;
        this.devoluciones = devoluciones;
        this.ventas = ventas;
        this.consecutivos = consecutivos;
        this.inventario = inventario;
        this.clientes = clientes;
        this.saldos = saldos;
        this.reloj = reloj;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DevolucionRegistrada registrar(SolicitudDevolucion s, Autorizacion autorizacion) {
        autorizacion.exigir(ExcepcionAutorizable.DEVOLUCION);
        Integer empresa = ContextoEmpresa.empresaActual();
        Integer cajero = cajeroEnSesion();
        LocalDateTime ahora = Hoy.ahora(reloj);

        // 1. El turno del cajero (FOR SHARE), si tiene. 2. La venta (FOR UPDATE).
        Optional<Long> turno = turnos.intentarTurnoAbierto();
        VentaADevolver venta = devoluciones.bloquearVenta(empresa, s.ventaId())
                .orElseThrow(() -> new RecursoNoEncontradoException(VENTA_NO_EXISTE));
        // El 409 de «ya anulada» va antes que el 400 de «más de lo vendido»: contra una venta anulada
        // cualquier cantidad es lo mismo.
        if ("ANULADA".equals(venta.estado())) {
            throw new EstadoNoAdmiteLaOperacionException(YA_ESTA_ANULADA);
        }
        boolean porCobrar = "PENDIENTE".equals(venta.estadoPago());

        // Lo ya devuelto se lee con la venta bloqueada: en READ COMMITTED ve a la hermana que confirmó.
        List<LineaVendida> lineas = devoluciones.lineasDeLaVenta(empresa, venta.ventaId());
        Resultado calculo = CalculoDeDevolucion.calcular(lineas, s.unidades());
        if (calculo.unidades().isEmpty()) {
            throw new EstadoNoAdmiteLaOperacionException(NADA_QUE_DEVOLVER);
        }

        // 3. Los reembolsos contra lo que valen las unidades y contra los medios de la empresa.
        List<Reembolso> reembolsos = s.reembolsos();
        BigDecimal reembolsado = BigDecimal.ZERO;
        BigDecimal aSaldo = BigDecimal.ZERO;
        Cliente cliente = null;
        if (porCobrar) {
            if (!reembolsos.isEmpty()) {
                throw new VentaInvalidaException(SIN_DINERO_QUE_REEMBOLSAR);
            }
        } else {
            Reembolsos validados = validarReembolsos(empresa, reembolsos, calculo.valor());
            reembolsos = validados.guardados();
            reembolsado = validados.total();
            aSaldo = validados.aSaldo();
            if (aSaldo.signum() > 0) {
                cliente = clientes.buscar(empresa, venta.clienteId())
                        .orElseThrow(() -> new RecursoNoEncontradoException(VENTA_NO_EXISTE));
                saldos.exigirIdentificado(cliente);
            }
        }
        if (reembolsado.signum() > 0 && turno.isEmpty()) {
            throw new SinTurnoAbiertoException();
        }

        // 4. Los productos devueltos, en orden ascendente de id.
        List<Integer> ids = calculo.unidades().stream().map(UnidadesDevueltas::productoId).toList();
        Map<Integer, ProductoBloqueado> productos = new HashMap<>();
        for (ProductoBloqueado p : ventas.bloquearProductos(empresa, ids)) {
            productos.put(p.id(), p);
        }
        if (productos.size() != ids.size()) {
            throw new RecursoNoEncontradoException("El producto no existe.");
        }

        // 5. El saldo del cliente: el abono lo suma y deja la fila tomada.
        if (aSaldo.signum() > 0) {
            saldos.abonar(empresa, cliente.id(), aSaldo);
        }

        // 6. El consecutivo (FOR UPDATE), el último bloqueo antes de insertar.
        String consecutivo = "D-%08d".formatted(consecutivos.tomarSiguiente(empresa, TipoConsecutivo.DEVOLUCION));

        // 7. La devolución, sus ítems y reembolsos, el abono, el kárdex y el estado de la venta.
        Long devolucionId = devoluciones.insertarDevolucion(new NuevaDevolucion(empresa, consecutivo, venta.ventaId(),
                s.motivo(), autorizacion.administradorId(), cajero, reembolsado.signum() > 0 ? turno.get() : null,
                ahora));
        devoluciones.insertarItems(empresa, devolucionId, calculo.unidades());
        if (!reembolsos.isEmpty()) {
            devoluciones.insertarReembolsos(empresa, devolucionId, reembolsos);
        }
        if (aSaldo.signum() > 0) {
            saldos.registrarAbono(empresa, cliente.id(), devolucionId, aSaldo, ahora);
        }
        List<Reingreso> reingresos = new ArrayList<>(calculo.unidades().size());
        for (UnidadesDevueltas u : calculo.unidades()) {
            int anterior = productos.get(u.productoId()).existencia();
            reingresos.add(new Reingreso(u.productoId(), u.cantidad(), anterior,
                    TipoMovimiento.DEVOLUCION.saldoTras(anterior, u.cantidad())));
        }
        inventario.registrarReingresos(empresa, cajero, consecutivo, reingresos);

        BigDecimal devueltoAntes = lineas.stream().map(LineaVendida::yaDevuelto).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal porCobrarAhora = porCobrar
                ? ServicioDomicilios.saldoPorCobrar(venta.total(), devueltoAntes.add(calculo.valor()))
                : BigDecimal.ZERO;
        // Sin unidades por devolver, o sin nada por cobrar, la venta queda anulada.
        boolean anulada = calculo.unidadesQueQuedan() == 0 || (porCobrar && porCobrarAhora.signum() == 0);
        String estadoPago = porCobrar && anulada ? "ANULADA" : venta.estadoPago();
        String estado = anulada ? "ANULADA" : "DEVUELTA_PARCIAL";
        devoluciones.actualizarVenta(empresa, venta.ventaId(), estado, estadoPago);

        return new DevolucionRegistrada(devolucionId, consecutivo, calculo.valor(), reembolsado.setScale(2),
                anulada ? BigDecimal.ZERO.setScale(2) : porCobrarAhora.setScale(2, RoundingMode.UNNECESSARY), estado,
                List.copyOf(reembolsos));
    }

    // ------------------------------------------------------------ reembolsos

    /** Los reembolsos como se guardan, lo que suman y lo que de ellos va al saldo del cliente. */
    private record Reembolsos(List<Reembolso> guardados, BigDecimal total, BigDecimal aSaldo) {}

    /**
     * Que cada reembolso vaya a un medio de la empresa que se pueda usar (activo, o el de sistema «Saldo a
     * favor»), que el de tarjeta o transferencia traiga su referencia (se hizo por fuera) y que sumen
     * exactamente lo devuelto. El de saldo se guarda sin referencia.
     */
    private Reembolsos validarReembolsos(Integer empresa, List<Reembolso> pedidos, BigDecimal valor) {
        Map<Integer, MedioDePago> medios = new HashMap<>();
        if (!pedidos.isEmpty()) {
            for (MedioDePago m : ventas.mediosPorId(empresa, pedidos.stream().map(Reembolso::medioId).distinct().toList())) {
                medios.put(m.id(), m);
            }
        }
        List<Reembolso> guardados = new ArrayList<>(pedidos.size());
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal aSaldo = BigDecimal.ZERO;
        for (Reembolso r : pedidos) {
            MedioDePago medio = medios.get(r.medioId());
            if (medio == null || (!medio.activo() && !medio.tipo().esDeSistema())) {
                throw new VentaInvalidaException(MEDIO_NO_DISPONIBLE);
            }
            if ((medio.tipo() == TipoMedio.TARJETA || medio.tipo() == TipoMedio.TRANSFERENCIA) && r.referencia() == null) {
                throw new VentaInvalidaException(SIN_REFERENCIA);
            }
            total = total.add(r.monto());
            if (medio.tipo() == TipoMedio.SALDO_A_FAVOR) {
                aSaldo = aSaldo.add(r.monto());
                guardados.add(new Reembolso(r.medioId(), r.monto(), null));
            } else {
                guardados.add(r);
            }
        }
        if (total.compareTo(valor) != 0) {
            throw new VentaInvalidaException(NO_SUMAN_EL_VALOR);
        }
        return new Reembolsos(List.copyOf(guardados), total.setScale(2, RoundingMode.UNNECESSARY),
                aSaldo.setScale(2, RoundingMode.UNNECESSARY));
    }

    private static Integer cajeroEnSesion() {
        return ContextoEmpresa.actual().map(UsuarioAutenticado::usuarioId).orElseThrow(() -> new IllegalStateException(
                "No hay usuario autenticado en el contexto. Revise la cadena de seguridad."));
    }
}

package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.Cliente;
import co.com.bspstore.sgip.api.dto.SaldoDeCliente;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.datos.RepositorioClientes;
import co.com.bspstore.sgip.datos.RepositorioDevoluciones;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * El saldo a favor de los clientes (RF-68, ADR-0033): dinero que la tienda le debe a un cliente
 * identificado, que nace de una devolución y se gasta en una compra posterior. No vence y no se retira en
 * efectivo, y el Consumidor final no lo tiene.
 *
 * Aquí está lo que comparten la devolución (que abona) y la venta (que usa): consultar, bloquear la fila del
 * cliente, abonar, usar y exigir que el cliente sea uno identificado. Las operaciones que escriben exigen
 * estar dentro de la transacción de la venta o la devolución ({@link Propagation#MANDATORY}): el bloqueo
 * de la fila se soltaría al terminar la sentencia y no protegería nada.
 *
 * <h2>Orden de bloqueos</h2>
 *
 * El saldo del cliente es el cuarto bloqueo de la caja (turno, venta, productos, <b>saldo</b>, consecutivo,
 * kárdex; ADR-0030): después de los productos y antes de cualquier consecutivo.
 */
@Service
public class ServicioSaldoAFavor {

    static final String SOLO_IDENTIFICADOS = "El saldo a favor solo existe para clientes identificados.";
    static final String SALDO_INSUFICIENTE = "No hay saldo a favor suficiente para ese pago.";
    static final String CLIENTE_NO_EXISTE = "El cliente no existe.";

    private final RepositorioDevoluciones saldos;
    private final RepositorioClientes clientes;

    public ServicioSaldoAFavor(RepositorioDevoluciones saldos, RepositorioClientes clientes) {
        this.saldos = saldos;
        this.clientes = clientes;
    }

    /**
     * El saldo del cliente y el medio de sistema con que se paga con él. 404 si el cliente no es de la
     * empresa de la sesión (o no existe: se responde igual); el Consumidor final responde cero.
     */
    @Transactional(readOnly = true)
    public SaldoDeCliente consultar(Integer clienteId) {
        Integer empresa = ContextoEmpresa.empresaActual();
        if (clienteId == null || clienteId <= 0) {
            throw new RecursoNoEncontradoException(CLIENTE_NO_EXISTE);
        }
        clientes.buscar(empresa, clienteId).orElseThrow(() -> new RecursoNoEncontradoException(CLIENTE_NO_EXISTE));
        return new SaldoDeCliente(saldos.saldoDe(empresa, clienteId).setScale(2),
                saldos.medioDeSaldo(empresa).orElse(null));
    }

    /** 400 si el cliente es el Consumidor final: no tiene a quién abonarle ni de quién descontar. */
    public void exigirIdentificado(Cliente cliente) {
        if (cliente.consumidorFinal()) {
            throw new VentaInvalidaException(SOLO_IDENTIFICADOS);
        }
    }

    /**
     * Toma la fila del saldo del cliente y devuelve lo que hay (cero si no tiene fila). Se llama al
     * principio de una venta que va a pagar con saldo, antes de cualquier consecutivo.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal bloquear(Integer empresa, Integer cliente) {
        return saldos.bloquearSaldo(empresa, cliente).orElse(BigDecimal.ZERO);
    }

    /**
     * Gasta {@code monto} del saldo en una venta y deja el movimiento de uso. {@code disponible} es lo que
     * {@link #bloquear} devolvió: con la fila bloqueada nadie lo ha cambiado desde entonces. Pasar de lo
     * que hay es un 400.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void usar(Integer empresa, Integer cliente, Long ventaId, BigDecimal monto, BigDecimal disponible,
                     LocalDateTime ahora) {
        exigirSuficiente(monto, disponible);
        saldos.descontarSaldo(empresa, cliente, monto);
        saldos.insertarUso(empresa, cliente, monto, ventaId, ahora);
    }

    /** 400 si se quiere usar más saldo del que hay. */
    public void exigirSuficiente(BigDecimal monto, BigDecimal disponible) {
        if (monto.compareTo(disponible) > 0) {
            throw new VentaInvalidaException(SALDO_INSUFICIENTE);
        }
    }

    /**
     * Suma {@code monto} al saldo del cliente (crea la fila si es su primer abono) y la deja bloqueada. El
     * movimiento se registra después con {@link #registrarAbono}, cuando la devolución ya tiene su
     * identificador.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void abonar(Integer empresa, Integer cliente, BigDecimal monto) {
        saldos.abonarSaldo(empresa, cliente, monto);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrarAbono(Integer empresa, Integer cliente, Long devolucionId, BigDecimal monto,
                               LocalDateTime ahora) {
        saldos.insertarAbono(empresa, cliente, monto, devolucionId, ahora);
    }
}

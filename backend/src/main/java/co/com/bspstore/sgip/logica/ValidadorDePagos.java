package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.MedioDePago;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.datos.RepositorioVentas;
import co.com.bspstore.sgip.datos.RepositorioVentas.NuevoPago;
import co.com.bspstore.sgip.logica.venta.CalculoDeVenta;
import co.com.bspstore.sgip.modelo.TipoMedio;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Las reglas de los pagos de una venta (RF-43, RF-44), en un solo sitio: las comparten el registro de
 * la venta ({@link RegistradorDeVenta}) y el cobro posterior de un domicilio contra entrega
 * ({@link ServicioDomicilios}), que tienen que decidir lo mismo.
 *
 * Cada pago va a un medio ACTIVO de la empresa (un medio ajeno, inexistente o desactivado es 400), el
 * efectivo calcula el cambio (lo recibido tiene que alcanzar el monto), la tarjeta y la transferencia
 * piden su referencia y no admiten efectivo recibido, y la suma tiene que ser exactamente lo esperado
 * (el total de la venta o el saldo por cobrar): ni parcial ni de más. Los mensajes son fijos y no repiten
 * lo enviado.
 *
 * <h2>El saldo a favor</h2>
 *
 * El medio de sistema «Saldo a favor» (RF-68, ADR-0033) nace inactivo y por eso queda exento de la regla
 * del medio inactivo, pero solo donde quien llama lo admite ({@code admiteSaldo}): la venta lo admite; el
 * cobro posterior de un domicilio contra entrega no, porque no toma el bloqueo del saldo del cliente y
 * dejaría pasar un pago que nadie descuenta. Un pago con saldo no lleva referencia ni efectivo recibido y
 * se marca para guardarse sin turno. Este validador solo suma lo que se usa de saldo
 * ({@link Validados#saldoUsado()}); comprobar que el cliente lo tiene y descontarlo lo hace
 * {@link ServicioSaldoAFavor}, con la fila bloqueada.
 *
 * Solo lee ({@code medios_pago}, por empresa) y no toma ningún bloqueo: el orden de bloqueos de la
 * caja no cambia por usarlo.
 */
@Component
public class ValidadorDePagos {

    static final String SALDO_NO_ADMITIDO = "El saldo a favor no se admite en el cobro contra entrega.";
    static final String SALDO_SIN_REFERENCIA = "El pago con saldo a favor no lleva referencia ni efectivo recibido.";
    static final String MEDIO_NO_DISPONIBLE = "El medio de pago no está disponible.";
    static final String NO_SUMAN_EL_TOTAL = "La suma de los pagos debe ser igual al total de la venta.";
    static final String NO_SUMAN_EL_SALDO = "Los pagos deben sumar exactamente el saldo por cobrar del domicilio: "
            + "no se admiten cobros parciales ni de más.";

    /**
     * Los pagos listos para guardar, el cambio que se devuelve (cero si no hubo efectivo) y lo que se paga
     * con saldo a favor (cero si nada).
     */
    public record Validados(List<NuevoPago> pagos, BigDecimal cambio, BigDecimal saldoUsado) {
        public Validados(List<NuevoPago> pagos, BigDecimal cambio) {
            this(pagos, cambio, BigDecimal.ZERO.setScale(2));
        }
    }

    private final RepositorioVentas ventas;

    public ValidadorDePagos(RepositorioVentas ventas) {
        this.ventas = ventas;
    }

    /**
     * Los medios de la empresa que nombran los pagos, por identificador, activos o no. Una sola consulta;
     * la venta la hace antes para saber si va a usar saldo y se la pasa a {@code validar}.
     */
    public Map<Integer, MedioDePago> medios(Integer empresa, List<PagoSolicitado> pedidos) {
        Map<Integer, MedioDePago> medios = new HashMap<>();
        for (MedioDePago m : ventas.mediosPorId(empresa, pedidos.stream().map(PagoSolicitado::medioId).distinct().toList())) {
            medios.put(m.id(), m);
        }
        return medios;
    }

    /** Si algún pago es del medio de sistema «Saldo a favor». */
    public static boolean usaSaldo(Map<Integer, MedioDePago> medios, List<PagoSolicitado> pedidos) {
        return pedidos.stream().map(p -> medios.get(p.medioId()))
                .anyMatch(m -> m != null && m.tipo() == TipoMedio.SALDO_A_FAVOR);
    }

    /**
     * Valida los pagos ya normalizados ({@code NormalizadorDeVenta.pagos}) contra los medios de la
     * empresa y contra lo que tienen que sumar, sin admitir saldo a favor. {@code mensajeSiNoSuman} es el
     * texto del 400 cuando la suma no es {@code esperado}.
     */
    public Validados validar(Integer empresa, List<PagoSolicitado> pedidos, BigDecimal esperado, String mensajeSiNoSuman) {
        return validar(pedidos, esperado, mensajeSiNoSuman, medios(empresa, pedidos), false);
    }

    /**
     * Lo mismo con los medios ya leídos ({@link #medios}) y diciendo si el saldo a favor se admite.
     */
    public Validados validar(List<PagoSolicitado> pedidos, BigDecimal esperado, String mensajeSiNoSuman,
                             Map<Integer, MedioDePago> medios, boolean admiteSaldo) {
        List<NuevoPago> pagos = new ArrayList<>(pedidos.size());
        BigDecimal suma = BigDecimal.ZERO;
        BigDecimal cambio = BigDecimal.ZERO;
        BigDecimal saldoUsado = BigDecimal.ZERO;
        for (PagoSolicitado p : pedidos) {
            MedioDePago medio = medios.get(p.medioId());
            if (medio == null) {
                throw new VentaInvalidaException(MEDIO_NO_DISPONIBLE);
            }
            BigDecimal monto = p.monto().setScale(2, RoundingMode.UNNECESSARY);
            if (medio.tipo() == TipoMedio.SALDO_A_FAVOR) {
                if (!admiteSaldo) {
                    throw new VentaInvalidaException(SALDO_NO_ADMITIDO);
                }
                if (p.referencia() != null || p.recibido() != null) {
                    throw new VentaInvalidaException(SALDO_SIN_REFERENCIA);
                }
                saldoUsado = saldoUsado.add(monto);
                suma = suma.add(monto);
                pagos.add(new NuevoPago(p.medioId(), monto, null, null, true));
                continue;
            }
            if (!medio.activo()) {
                throw new VentaInvalidaException(MEDIO_NO_DISPONIBLE);
            }
            BigDecimal recibido = null;
            if (medio.tipo() == TipoMedio.EFECTIVO) {
                recibido = (p.recibido() == null ? p.monto() : p.recibido()).setScale(2, RoundingMode.UNNECESSARY);
                cambio = cambio.add(CalculoDeVenta.cambio(monto, recibido));
            } else {
                if (p.referencia() == null) {
                    throw new VentaInvalidaException("El pago con tarjeta o transferencia necesita su referencia.");
                }
                if (p.recibido() != null) {
                    throw new VentaInvalidaException("El efectivo recibido solo se pide en los pagos en efectivo.");
                }
            }
            suma = suma.add(monto);
            pagos.add(new NuevoPago(p.medioId(), monto, p.referencia(), recibido));
        }
        if (suma.compareTo(esperado) != 0) {
            throw new VentaInvalidaException(mensajeSiNoSuman);
        }
        return new Validados(List.copyOf(pagos), cambio.setScale(2, RoundingMode.UNNECESSARY),
                saldoUsado.setScale(2, RoundingMode.UNNECESSARY));
    }
}

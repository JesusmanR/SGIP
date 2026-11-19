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
 * piden su referencia y no admiten efectivo recibido, el saldo a favor aún no está disponible, y la
 * suma tiene que ser exactamente lo esperado (el total de la venta o el saldo por cobrar): ni parcial
 * ni de más. Los mensajes son fijos y no repiten lo enviado.
 *
 * Solo lee ({@code medios_pago}, por empresa) y no toma ningún bloqueo: el orden de bloqueos de la
 * caja no cambia por usarlo.
 */
@Component
public class ValidadorDePagos {

    static final String SALDO_NO_DISPONIBLE = "El saldo a favor aún no está disponible";
    static final String MEDIO_NO_DISPONIBLE = "El medio de pago no está disponible.";
    static final String NO_SUMAN_EL_TOTAL = "La suma de los pagos debe ser igual al total de la venta.";
    static final String NO_SUMAN_EL_SALDO = "Los pagos deben sumar exactamente el saldo por cobrar del domicilio: "
            + "no se admiten cobros parciales ni de más.";

    /** Los pagos listos para guardar y el cambio que se devuelve (cero si no hubo efectivo). */
    public record Validados(List<NuevoPago> pagos, BigDecimal cambio) {}

    private final RepositorioVentas ventas;

    public ValidadorDePagos(RepositorioVentas ventas) {
        this.ventas = ventas;
    }

    /**
     * Valida los pagos ya normalizados ({@code NormalizadorDeVenta.pagos}) contra los medios de la
     * empresa y contra lo que tienen que sumar. {@code mensajeSiNoSuman} es el texto del 400 cuando la
     * suma no es {@code esperado}.
     */
    public Validados validar(Integer empresa, List<PagoSolicitado> pedidos, BigDecimal esperado, String mensajeSiNoSuman) {
        Map<Integer, MedioDePago> medios = new HashMap<>();
        for (MedioDePago m : ventas.mediosPorId(empresa, pedidos.stream().map(PagoSolicitado::medioId).distinct().toList())) {
            medios.put(m.id(), m);
        }
        List<NuevoPago> pagos = new ArrayList<>(pedidos.size());
        BigDecimal suma = BigDecimal.ZERO;
        BigDecimal cambio = BigDecimal.ZERO;
        for (PagoSolicitado p : pedidos) {
            MedioDePago medio = medios.get(p.medioId());
            if (medio == null) {
                throw new VentaInvalidaException(MEDIO_NO_DISPONIBLE);
            }
            if (medio.tipo() == TipoMedio.SALDO_A_FAVOR) {
                throw new VentaInvalidaException(SALDO_NO_DISPONIBLE);
            }
            if (!medio.activo()) {
                throw new VentaInvalidaException(MEDIO_NO_DISPONIBLE);
            }
            BigDecimal monto = p.monto().setScale(2, RoundingMode.UNNECESSARY);
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
        return new Validados(List.copyOf(pagos), cambio.setScale(2, RoundingMode.UNNECESSARY));
    }
}

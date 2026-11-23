package co.com.bspstore.sgip.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * La devolución tal como se le responde al mostrador: su identificador y el consecutivo que se
 * imprime ({@code D-00000001}), lo que valían las unidades devueltas, lo que se le reembolsó, lo que
 * queda por cobrar de una venta contra entrega (cero si la venta estaba pagada) y cómo quedó la venta
 * ({@code DEVUELTA_PARCIAL} o {@code ANULADA}). Nunca lleva costo ni utilidad.
 */
public record DevolucionRegistrada(Long id, String consecutivo, BigDecimal valorDevuelto, BigDecimal reembolsado,
                                   BigDecimal saldoPorCobrar, String estadoVenta, List<Reembolso> reembolsos) {
}

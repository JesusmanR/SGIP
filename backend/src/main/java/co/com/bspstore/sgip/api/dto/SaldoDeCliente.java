package co.com.bspstore.sgip.api.dto;

import java.math.BigDecimal;

/**
 * El saldo a favor de un cliente y el medio de sistema con que se paga con él. La pantalla de la caja
 * necesita el {@code medioId} para ofrecer el pago con saldo: es el de tipo SALDO_A_FAVOR de la empresa,
 * que no aparece entre los medios que el cajero elige.
 */
public record SaldoDeCliente(BigDecimal saldo, Integer medioId) {
}

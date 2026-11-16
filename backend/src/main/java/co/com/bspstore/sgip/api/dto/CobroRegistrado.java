package co.com.bspstore.sgip.api.dto;

import java.math.BigDecimal;

/**
 * El cobro de un domicilio contra entrega: la venta, lo que se cobró (el saldo completo) y el cambio
 * que se devuelve si se pagó en efectivo (cero si no).
 */
public record CobroRegistrado(Long ventaId, String consecutivo, BigDecimal cobrado, BigDecimal cambio) {}

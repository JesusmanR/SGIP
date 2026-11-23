package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.logica.venta.LineaCalculada;

import java.math.BigDecimal;
import java.util.List;

/**
 * La venta tal como se le responde al mostrador: su identificador, el consecutivo que se imprime,
 * el total que se cobró, el cambio que se devuelve (cero si no hubo efectivo) y las líneas con lo
 * que el servidor calculó. <b>Nunca lleva costo ni utilidad</b>, ni para el administrador:
 * {@code LineaCalculada} no los tiene.
 */
public record VentaRegistrada(Long id, String consecutivo, BigDecimal total, BigDecimal cambio,
                              List<LineaCalculada> lineas) {
}

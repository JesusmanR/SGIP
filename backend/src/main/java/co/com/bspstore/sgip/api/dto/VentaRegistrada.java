package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.logica.venta.LineaCalculada;

import java.math.BigDecimal;
import java.util.List;

/**
 * La venta tal como se le responde al mostrador: su identificador, el consecutivo que se imprime,
 * el total que se cobró, el cambio que se devuelve (cero si no hubo efectivo) y las líneas con lo
 * que el servidor calculó. <b>Nunca lleva costo ni utilidad</b>, ni para el administrador:
 * {@code LineaCalculada} no los tiene.
 *
 * {@code empresa} es la copia de los datos de la empresa que la venta guardó (V48), para que el mostrador arme el
 * recibo inmediato con lo mismo que reimprimirá después. Es nula solo en una venta anterior a la V48.
 */
public record VentaRegistrada(Long id, String consecutivo, BigDecimal total, BigDecimal cambio,
                              List<LineaCalculada> lineas, CopiaDeEmpresa empresa) {
}

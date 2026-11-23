package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.VentaRegistrada;

/**
 * Lo que devuelve el registro de una venta: la venta y si ya existía. Una venta que ya existía
 * es la respuesta a un reintento con la misma clave de idempotencia (200); una nueva es un 201.
 */
public record ResultadoDeVenta(VentaRegistrada venta, boolean yaExistia) {
}

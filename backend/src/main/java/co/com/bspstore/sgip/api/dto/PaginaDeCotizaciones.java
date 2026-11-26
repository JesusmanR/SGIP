package co.com.bspstore.sgip.api.dto;

import java.util.List;

/**
 * Una página de la lista de cotizaciones, con la misma forma que la de clientes. {@code pagina} empieza en 0;
 * {@code tamano} es el que se aplicó (el pedido, recortado al máximo); {@code total} cuenta todas las que cumplen
 * el filtro y la búsqueda.
 */
public record PaginaDeCotizaciones(List<CotizacionEnLista> elementos, int pagina, int tamano, long total) {
}

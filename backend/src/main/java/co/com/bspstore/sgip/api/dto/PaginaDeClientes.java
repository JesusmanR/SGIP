package co.com.bspstore.sgip.api.dto;

import java.util.List;

/**
 * Una página de la lista de clientes. {@code pagina} empieza en 0; {@code tamano} es el que se aplicó
 * (el pedido, recortado al máximo); {@code total} cuenta todos los que cumplen la búsqueda.
 */
public record PaginaDeClientes(List<ClienteEnLista> elementos, int pagina, int tamano, long total) {}

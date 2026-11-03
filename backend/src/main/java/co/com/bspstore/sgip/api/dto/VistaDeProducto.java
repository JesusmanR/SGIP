package co.com.bspstore.sgip.api.dto;

/**
 * Lo que el catálogo devuelve de un producto, según quién pregunta.
 *
 * Es sellada a propósito: las únicas formas posibles son la completa, que
 * solo recibe el administrador, y la de venta, sin costo ni proveedor. Una
 * tercera forma tendría que declararse aquí y revisarse, en lugar de
 * aparecer por accidente en un controlador.
 */
public sealed interface VistaDeProducto permits ProductoEnCatalogo, ProductoParaVenta {
}

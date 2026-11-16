package co.com.bspstore.sgip.api.dto;

import java.math.BigDecimal;

/**
 * Un producto que no está en el catálogo y se crea dentro de la venta (RF-42). {@code precioVenta} es
 * SIN IVA, un entero positivo de hasta 12 cifras; {@code nombre} cabe en {@code productos.nombre} (160) y
 * {@code codigoBarras}, si viene, en {@code productos.codigo_barras} (60). Nace con existencia 0 y costo
 * provisional, y toda su venta es un encargo: la línea trae la fecha estimada de entrega y la venta, el motivo.
 * Pide la autorización de producto nuevo y la de encargo, con un solo PIN.
 */
public record ProductoNuevo(String nombre, BigDecimal precioVenta, String codigoBarras) {
}

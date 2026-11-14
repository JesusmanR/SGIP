package co.com.bspstore.sgip.api.dto;

import java.math.BigDecimal;

/**
 * Un producto que no está en el catálogo y se crea dentro de la venta. {@code precioVenta} es
 * SIN IVA. Hasta la tarea 8 solo existe el registro: una línea con producto nuevo responde 400.
 */
public record ProductoNuevo(String nombre, BigDecimal precioVenta, String codigoBarras) {
}

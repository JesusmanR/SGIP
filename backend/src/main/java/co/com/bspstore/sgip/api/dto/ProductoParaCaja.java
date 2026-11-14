package co.com.bspstore.sgip.api.dto;

import java.math.BigDecimal;

/**
 * El producto tal como lo ve la caja: lo justo para poner una línea en la venta.
 *
 * <b>No lleva costo, margen, utilidad ni proveedor, para nadie</b>: la caja no enseña el costo
 * ni al administrador (decisión del 2026-11-09), y quien necesite verlo lo tiene en el
 * catálogo. {@code ServicioCajaProductos} construye este registro desde
 * {@link ProductoEnCatalogo}, que sí trae el costo, listando los campos uno a uno y sin
 * copiar el registro completo: un campo nuevo en el catálogo no llega a la caja hasta que
 * alguien lo añada aquí a conciencia.
 *
 * {@code precio} es el de contado <b>sin IVA</b>, en pesos enteros, y lo calcula el servidor
 * con la vista de precios: el cliente nunca envía un precio. El IVA se suma encima con
 * {@code tarifaIva} (una fracción: 0,19 es el 19 %) al armar la venta.
 *
 * {@code vendible} dice si la caja puede poner el producto en una venta. Un producto sin
 * precio (o con uno que no cabe en la venta) se muestra igual, para que la persona vea que
 * existe, pero con {@code vendible = false} y el porqué en {@code motivoNoVendible}.
 */
public record ProductoParaCaja(
        Integer id,
        String codigo,
        String nombre,
        String presentacion,
        String codigoBarras,
        int existencia,
        BigDecimal precio,
        BigDecimal tarifaIva,
        String tratamientoIva,
        boolean vendible,
        String motivoNoVendible
) {}

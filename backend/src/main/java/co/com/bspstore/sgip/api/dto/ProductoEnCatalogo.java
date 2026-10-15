package co.com.bspstore.sgip.api.dto;

import java.math.BigDecimal;

/**
 * Una fila de v_precios_producto, tal como la ve el catálogo.
 *
 * Los nombres siguen los de la vista para que el mapeo sea directo. No
 * incluye empresa_id: el cliente no necesita saber a qué empresa
 * pertenece lo que pidió, porque sale de su propia sesión.
 *
 * Tres campos merecen explicación, porque no son datos del producto sino
 * avisos que la vista calcula:
 *
 * tributacionSinConfigurar · no hay regla de impuesto que le aplique, de
 * modo que el precio mostrado todavía no es el que se cobrará.
 *
 * cercaUmbral · el precio quedó en el último diez por ciento antes del
 * umbral del artículo 424. Un ajuste pequeño de costo lo vuelve gravado.
 *
 * estadoStock · lo clasifica el motor de base de datos como columna
 * generada, no la aplicación, de modo que no puede desincronizarse de la
 * existencia real.
 */
public record ProductoEnCatalogo(
        Integer productoId,
        String codigo,
        String sku,
        String nombre,
        String presentacion,
        String categoria,
        String proveedor,
        BigDecimal costo,
        Integer existencia,
        Integer existenciaMinima,
        String estadoStock,
        BigDecimal precioContado,
        BigDecimal precioCredito,
        String tratamientoIva,
        BigDecimal tarifaContado,
        BigDecimal totalContado,
        Integer tributacionSinConfigurar,
        Integer cercaUmbral,
        String imagenPrincipal
) {}

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
 *
 * imagenPrincipalId · cuál es la imagen principal, para que el cliente
 * pida su miniatura por una dirección que no cambia de contenido a
 * espaldas de nadie. Es el identificador, nunca la ruta: la organización
 * interna de las carpetas no sale al exterior y no hay ninguna cadena
 * del cliente que llegue al sistema de archivos.
 *
 * tieneImagen · lo mismo en forma de sí o no, que es lo que la tabla
 * necesita para decidir si ofrece «ver» o «añadir» imágenes.
 */
public record ProductoEnCatalogo(
        Integer productoId,
        String codigo,
        String sku,
        String nombre,
        String presentacion,
        Integer categoriaId,
        String categoria,
        Integer proveedorId,
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
        Integer imagenPrincipalId,
        Integer tieneImagen
) {}

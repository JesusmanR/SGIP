package co.com.bspstore.sgip.logica;

import java.math.BigDecimal;

/**
 * Lo mínimo del catálogo que la validación de una carga necesita.
 *
 * No es el producto entero a propósito. Validar veinte mil filas carga
 * el catálogo completo en memoria, y traer de cada referencia su
 * descripción, su imagen y sus fechas para no mirarlas es pagar por
 * nada. Con el identificador se escribe el detalle, y con el costo se
 * muestra qué cambia.
 */
public record ProductoDelCatalogo(
        Integer productoId,
        String codigo,
        String sku,
        BigDecimal costo
) {}

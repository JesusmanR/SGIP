package co.com.bspstore.sgip.logica;

import java.math.BigDecimal;

/**
 * Una fila de la plantilla de precios: el código del producto, su nombre y el
 * costo que rige hoy. El costo puede venir nulo o en cero; la plantilla lo deja
 * en blanco para que alguien lo llene.
 */
public record ProductoDePlantilla(String codigo, String nombre, BigDecimal costo) {}

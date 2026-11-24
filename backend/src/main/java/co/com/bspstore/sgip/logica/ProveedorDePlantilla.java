package co.com.bspstore.sgip.logica;

/**
 * Lo mínimo de un proveedor que la plantilla de precios necesita: su nombre,
 * para el archivo, y su NIT, para reconocer al proveedor reservado.
 */
public record ProveedorDePlantilla(String nombre, String nit) {}

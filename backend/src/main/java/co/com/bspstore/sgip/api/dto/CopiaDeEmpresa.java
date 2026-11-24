package co.com.bspstore.sgip.api.dto;

/**
 * Los datos de la empresa tal como estaban el día de una venta (V48): lo que el recibo imprime aunque la empresa
 * los cambie después. {@code nombre} es el nombre comercial o, si no lo hay, la razón social. {@code nit} es el
 * número tal como estaba guardado, sin normalizar: es una foto. Dirección y teléfono son nulos si la empresa no
 * los había llenado ese día.
 */
public record CopiaDeEmpresa(String nombre, String tipoDocumento, String nit, String direccion, String telefono) {
}

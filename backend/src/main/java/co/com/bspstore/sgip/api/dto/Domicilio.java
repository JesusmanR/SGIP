package co.com.bspstore.sgip.api.dto;

/**
 * La entrega a domicilio de una venta. Hasta la tarea 8 solo existe el registro: una venta con
 * domicilio responde 400.
 */
public record Domicilio(String direccion, String ciudad, String telefono, String recibe,
                        boolean contraEntrega) {
}

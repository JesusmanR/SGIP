package co.com.bspstore.sgip.api.dto;

import jakarta.validation.constraints.Size;

/**
 * La entrega a domicilio de una venta (RF-44, RF-69): a dónde, a quién y si se cobra al entregarla.
 * Los domicilios los hace un tercero; la venta solo guarda estos datos.
 *
 * {@code direccion}, {@code telefono} y {@code recibe} son obligatorios; {@code ciudad} vacía toma la
 * de la empresa (el diseño pide la ciudad «cuando no sea local»), y si la empresa tampoco tiene una,
 * es un 400. Cada texto cabe en su columna de {@code domicilios_venta}. Son datos personales: solo
 * salen en las listas de domicilios y nunca en un registro del servidor.
 *
 * {@code contraEntrega} es el pago contra entrega: la venta nace por cobrar y sin pagos, y el dinero se
 * cobra después, al entregarla. Un domicilio sin esa marca se paga por adelantado, como cualquier venta.
 */
public record Domicilio(

        @Size(max = 200, message = "La dirección admite hasta 200 caracteres")
        String direccion,

        @Size(max = 80, message = "La ciudad admite hasta 80 caracteres")
        String ciudad,

        @Size(max = 20, message = "El teléfono admite hasta 20 caracteres")
        String telefono,

        @Size(max = 120, message = "El nombre de quien recibe admite hasta 120 caracteres")
        String recibe,

        boolean contraEntrega
) {
}

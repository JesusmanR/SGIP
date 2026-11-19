package co.com.bspstore.sgip.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Un domicilio de las listas de la caja (RF-44, RF-69): lo que la tienda necesita para despacharlo y
 * para cobrarlo. {@code valorPorCobrar} es el saldo que falta por cobrar (cero si la venta ya está
 * pagada) y {@code antiguedadDias}, los días desde que se vendió, contados con el día de Bogotá de la
 * aplicación.
 *
 * Trae datos personales (el nombre del cliente, la dirección, el teléfono y quien recibe): salen solo
 * en estas rutas, que son del administrador y del vendedor, y nunca en un registro del servidor. No
 * lleva costo ni utilidad.
 */
public record DomicilioPendiente(
        Long ventaId,
        String consecutivo,
        OffsetDateTime fecha,
        String cliente,
        String direccion,
        String ciudad,
        String telefono,
        String recibe,
        boolean contraEntrega,
        String estadoPago,
        BigDecimal total,
        BigDecimal valorPorCobrar,
        long antiguedadDias,
        Long clienteId,
        boolean entregado
) {}

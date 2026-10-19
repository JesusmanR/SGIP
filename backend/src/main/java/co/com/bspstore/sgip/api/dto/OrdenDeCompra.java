package co.com.bspstore.sgip.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Una orden de compra como la ve la pantalla (RF-22, RF-23).
 *
 * `pendiente` por línea es lo que falta por recibir, y viene calculado
 * en lugar de dejar que la pantalla reste: es la cifra con la que se
 * decide si una entrega cabe, y el requisito la vigila —«recibir más de
 * lo ordenado devuelve 400»—. Que la calcule el mismo sitio que la hace
 * cumplir evita que las dos cuentas discrepen.
 */
public record OrdenDeCompra(
        Long ordenId,
        String consecutivo,
        Integer proveedorId,
        String proveedor,
        Integer usuarioId,
        String usuario,
        String estado,
        LocalDateTime fecha,
        String observacion,
        BigDecimal total,
        List<LineaDeOrden> items
) {

    public record LineaDeOrden(
            Long itemId,
            Integer productoId,
            String codigo,
            String producto,
            Integer cantidad,
            Integer cantidadRecibida,
            Integer pendiente,
            BigDecimal costoUnitario,
            BigDecimal subtotal
    ) {}
}

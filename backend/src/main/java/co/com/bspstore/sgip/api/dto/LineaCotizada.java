package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.logica.venta.LimitesDeVenta;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Una línea de lo que se cotiza: un producto de la empresa y la cantidad. Nunca trae precio: lo pone el servidor
 * con la misma regla que la caja. La cantidad llega al mismo tope de unidades que una línea de venta.
 */
public record LineaCotizada(

        @NotNull(message = "El producto es obligatorio")
        @Positive(message = "El producto no es válido")
        Integer productoId,

        @NotNull(message = "La cantidad es obligatoria")
        @Positive(message = "La cantidad debe ser de al menos una unidad")
        @Max(value = LimitesDeVenta.CANTIDAD, message = "La cantidad de una línea admite hasta 99999 unidades")
        Integer cantidad
) {
}

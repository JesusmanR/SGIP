package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.logica.venta.LimitesDeVenta;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Las unidades que se devuelven de un producto de la venta. Una venta tiene una sola línea por
 * producto, así que el producto identifica la línea.
 */
public record UnidadDevuelta(

        @NotNull(message = "La unidad devuelta necesita un producto")
        @Positive(message = "El producto no es válido")
        Integer productoId,

        @Min(value = 1, message = "La cantidad devuelta debe ser de al menos una unidad")
        @Max(value = LimitesDeVenta.CANTIDAD, message = "La cantidad devuelta admite hasta 99999 unidades")
        int cantidad
) {
}

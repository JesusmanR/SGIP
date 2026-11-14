package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.logica.venta.Descuento;
import co.com.bspstore.sgip.logica.venta.LimitesDeVenta;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

/**
 * Una línea de la venta: un producto de la empresa o un producto nuevo (exactamente uno de los
 * dos), la cantidad y, si hace falta, su descuento y la fecha en que llegaría lo que no hay.
 *
 * {@code entregaEstimada} solo la trae quien pide un encargo, y es de hoy en adelante.
 */
public record LineaSolicitada(

        @Positive(message = "El producto no es válido")
        Integer productoId,

        @Valid
        ProductoNuevo productoNuevo,

        @Min(value = 1, message = "La cantidad debe ser de al menos una unidad")
        @Max(value = LimitesDeVenta.CANTIDAD, message = "La cantidad de una línea admite hasta 99999 unidades")
        int cantidad,

        @Valid
        Descuento descuento,

        LocalDate entregaEstimada
) {
}

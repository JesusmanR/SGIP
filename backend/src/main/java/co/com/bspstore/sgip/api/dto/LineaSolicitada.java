package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.logica.venta.Descuento;
import co.com.bspstore.sgip.logica.venta.LimitesDeVenta;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

/**
 * Una línea de la venta: un producto de la empresa, la cantidad y, si hace falta, su descuento y la
 * fecha en que llegaría lo que no hay.
 *
 * {@code entregaEstimada} solo la trae quien pide un encargo, y es de hoy en adelante.
 *
 * {@code productoNuevo} ya no existe: los productos los crea el administrador en el catálogo y la caja
 * no crea ninguno. El componente se conserva solo para <b>rechazar</b> al cliente que aún lo envíe
 * (400, «Los productos se crean en el catálogo.»): sin él, Jackson ignoraría el campo y la línea
 * llegaría sin producto. Nunca trae un valor útil y {@code NormalizadorDeVenta} lo rechaza.
 */
public record LineaSolicitada(

        @Positive(message = "El producto no es válido")
        Integer productoId,

        Object productoNuevo,

        @Min(value = 1, message = "La cantidad debe ser de al menos una unidad")
        @Max(value = LimitesDeVenta.CANTIDAD, message = "La cantidad de una línea admite hasta 99999 unidades")
        int cantidad,

        @Valid
        Descuento descuento,

        LocalDate entregaEstimada
) {
}

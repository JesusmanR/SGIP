package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.logica.venta.LimitesDeVenta;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Lo que el mostrador envía para crear una cotización (RF-37). {@code clienteId} nulo es el Consumidor final de
 * la empresa. No trae precios, ni vigencia, ni fechas: el servidor toma los precios de la vista de precios y la
 * vigencia de la configuración de la empresa. Un campo de más en el JSON se ignora.
 *
 * Los mensajes de validación son fijos y no repiten lo enviado.
 */
public record SolicitudCotizacion(

        @Positive(message = "El cliente no es válido")
        Integer clienteId,

        @NotEmpty(message = "La cotización necesita al menos una línea")
        @Size(max = LimitesDeVenta.LINEAS, message = "La cotización admite como máximo 200 líneas")
        List<@NotNull(message = "Una línea de la cotización está vacía") @Valid LineaCotizada> lineas,

        @Size(max = 400, message = "Las observaciones admiten hasta 400 caracteres")
        String observaciones
) {
}

package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.logica.venta.Descuento;
import co.com.bspstore.sgip.logica.venta.LimitesDeVenta;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Lo que el mostrador envía para registrar una venta (RF-41).
 *
 * El cliente nunca envía un precio: el servidor lo calcula con la vista de precios, y un campo
 * de más en el JSON se ignora. {@code clienteId} nulo es el Consumidor final de la empresa.
 * {@code claveIdempotencia} la inventa el cliente una vez por venta y la repite si reintenta
 * (doble clic, red lenta): una sola venta queda registrada.
 *
 * {@code descuentoTotal}, las fechas de entrega de las líneas, {@code motivoEncargo} y el
 * producto nuevo son cosas que un vendedor no hace solo: piden la {@code autorizacion} de un
 * administrador (ADR-0032). {@code domicilio} es nulo en el mostrador. Un domicilio con
 * {@code contraEntrega} se cobra al entregarlo: no lleva pagos (mandarlos es un error); toda otra venta
 * los lleva por el total.
 *
 * Los mensajes de validación son fijos y no repiten lo enviado.
 */
public record SolicitudVenta(

        @NotNull(message = "La clave de idempotencia es obligatoria")
        UUID claveIdempotencia,

        @Positive(message = "El cliente no es válido")
        Integer clienteId,

        @NotEmpty(message = "La venta necesita al menos una línea")
        @Size(max = LimitesDeVenta.LINEAS, message = "La venta admite como máximo 200 líneas")
        List<@NotNull(message = "Una línea de la venta está vacía") @Valid LineaSolicitada> lineas,

        @Valid
        Descuento descuentoTotal,

        @Size(max = LimitesDeVenta.PAGOS, message = "La venta admite como máximo 10 pagos")
        List<@NotNull(message = "Un pago de la venta está vacío") @Valid PagoSolicitado> pagos,

        @Size(max = LimitesDeVenta.MOTIVO_DEL_ENCARGO,
                message = "El motivo del encargo admite hasta 200 caracteres")
        String motivoEncargo,

        SolicitudAutorizacion autorizacion,

        @Valid
        Domicilio domicilio
) {

    /**
     * Los pagos son obligatorios salvo en un domicilio contra entrega, que se cobra después. Va aquí y
     * no como {@code @NotEmpty} en el campo porque depende de otro campo; que un contra entrega no los
     * traiga lo comprueba {@code NormalizadorDeVenta}, que es quien conoce la regla completa.
     */
    @JsonIgnore
    @AssertTrue(message = "La venta necesita al menos un pago")
    public boolean isConPagos() {
        boolean contraEntrega = domicilio != null && domicilio.contraEntrega();
        return contraEntrega || (pagos != null && !pagos.isEmpty());
    }
}

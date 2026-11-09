package co.com.bspstore.sgip.api.dto;

import jakarta.validation.constraints.NotNull;

/** Cuerpo del PATCH que activa o desactiva un medio de pago. */
public record SolicitudActivoMedioPago(

        @NotNull(message = "Indica si el medio queda activo")
        Boolean activo
) {}

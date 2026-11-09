package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.modelo.TipoMedio;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Alta de un medio de pago. El tipo es el de la lista cerrada de
 * {@link TipoMedio}; que {@code SALDO_A_FAVOR} no se pueda crear a mano lo dice el
 * servicio, porque ahí está la regla.
 */
public record SolicitudMedioPago(

        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 60, message = "El nombre no puede pasar de 60 caracteres")
        String nombre,

        @NotNull(message = "El tipo es obligatorio")
        TipoMedio tipo
) {}

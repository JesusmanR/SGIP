package co.com.bspstore.sgip.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Ajuste del saldo contra un conteo físico (RF-49).
 *
 * Pide el saldo contado y no una diferencia, porque es lo que de verdad
 * ocurre: alguien cuenta el estante y encuentra nueve. Pedir la
 * diferencia obligaría a quien cuenta a restar de cabeza contra la cifra
 * que el sistema cree tener, que es justamente la que está en duda.
 *
 * El mínimo es cero y no uno: un conteo puede encontrar el estante
 * vacío, y eso es un ajuste legítimo. Lo que no puede es ser negativo:
 * la existencia negativa significa deuda de reposición (ADR-0014) y se
 * origina en una venta autorizada, no en un conteo.
 *
 * El motivo es obligatorio, y es el criterio del requisito —«un ajuste
 * sin motivo devuelve 400»—. Un ajuste es la única operación en la que
 * una persona reescribe el saldo sin que haya mercancía moviéndose, de
 * modo que la razón es la mitad del registro.
 */
public record SolicitudAjuste(

        @NotNull(message = "El producto es obligatorio")
        Integer productoId,

        @NotNull(message = "El saldo contado es obligatorio")
        @Min(value = 0, message = "El saldo contado no puede ser negativo")
        Integer existenciaContada,

        @NotBlank(message = "El ajuste exige motivo: es quien explica por qué cambió el saldo")
        @Size(max = 200, message = "El motivo no puede pasar de 200 caracteres")
        String motivo,

        @Size(max = 60, message = "El documento no puede pasar de 60 caracteres")
        String documento
) {}

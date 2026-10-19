package co.com.bspstore.sgip.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Lo que llegó de una orden de compra (RF-23).
 *
 * La orden va en la ruta. Las líneas traen lo recibido **en esta
 * entrega**, no el acumulado: es lo que quien descarga el camión puede
 * contar sin consultar nada. El acumulado lo lleva el sistema en
 * `cantidad_recibida`, y mezclar las dos lecturas en el mismo campo es
 * la clase de ambigüedad que acaba duplicando una recepción.
 *
 * Una entrega puede traer solo parte de las líneas de la orden: el
 * requisito contempla la recepción parcial, y una entrega que no
 * mencione una línea simplemente no la toca.
 */
public record SolicitudRecepcion(

        @NotEmpty(message = "Una recepción sin líneas no registra nada")
        @Valid
        List<LineaRecepcion> items,

        @Size(max = 200, message = "La observación no puede pasar de 200 caracteres")
        String observacion,

        @Size(max = 60, message = "El documento no puede pasar de 60 caracteres")
        String documento
) {

    public record LineaRecepcion(

            @NotNull(message = "El producto de la línea es obligatorio")
            Integer productoId,

            @NotNull(message = "La cantidad recibida es obligatoria")
            @Min(value = 1, message = "Recibir cero unidades no es una recepción")
            Integer cantidadRecibida
    ) {}
}

package co.com.bspstore.sgip.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Una orden de compra con sus líneas (RF-22).
 *
 * El consecutivo lo trae quien registra y no lo genera el sistema. La
 * orden de compra es un documento que la empresa ya numera —o que trae el
 * número del proveedor—, y una numeración automática obligaría a
 * reservarla con un bloqueo o a arriesgar colisiones bajo concurrencia.
 * La clave única `(empresa_id, consecutivo)` ya impide repetirlo, y un
 * repetido se responde con 409 y su mensaje. Si Black Steel Parts quiere
 * numeración automática, es una función con su propia tabla de
 * secuencias y no un efecto secundario de este alta.
 *
 * No hay campo de estado. La orden nace ABIERTA —«pendiente», en el
 * lenguaje del requisito— y aceptarlo desde fuera permitiría registrar
 * una ya recibida sin que hubiera entrado un solo producto.
 */
public record SolicitudOrdenCompra(

        @NotNull(message = "El proveedor es obligatorio")
        Integer proveedorId,

        @NotBlank(message = "El consecutivo es obligatorio")
        @Size(max = 20, message = "El consecutivo no puede pasar de 20 caracteres")
        String consecutivo,

        @Size(max = 200, message = "La observación no puede pasar de 200 caracteres")
        String observacion,

        @NotEmpty(message = "Una orden sin líneas no es una orden")
        @Valid
        List<LineaOrden> items
) {

    /**
     * Una línea de la orden.
     *
     * Los mínimos repiten lo que ya exigen `ck_oci_cantidad` y
     * `ck_oci_costo`. Puestos aquí, el rechazo llega como un 400 que dice
     * qué línea está mal; sin ellos, llegaría como un error de
     * integridad que no distingue una línea de otra.
     */
    public record LineaOrden(

            @NotNull(message = "El producto de la línea es obligatorio")
            Integer productoId,

            @NotNull(message = "La cantidad de la línea es obligatoria")
            @Min(value = 1, message = "La cantidad tiene que ser al menos una unidad")
            Integer cantidad,

            @NotNull(message = "El costo unitario es obligatorio")
            @DecimalMin(value = "0.01", message = "El costo unitario tiene que ser mayor que cero")
            @Digits(integer = 12, fraction = 2,
                    message = "El costo admite hasta doce enteros y dos decimales")
            BigDecimal costoUnitario
    ) {}
}

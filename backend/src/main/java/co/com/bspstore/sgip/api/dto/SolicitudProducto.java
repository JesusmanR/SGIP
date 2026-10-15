package co.com.bspstore.sgip.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record SolicitudProducto(

        @NotNull(message = "La categoría es obligatoria")
        Integer categoriaId,

        @NotNull(message = "El proveedor es obligatorio")
        Integer proveedorId,

        @NotBlank(message = "El código es obligatorio")
        @Size(max = 60)
        String codigo,

        @Size(max = 40, message = "El SKU no puede pasar de 40 caracteres")
        String sku,

        @Size(max = 60)
        String codigoBarras,

        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 160)
        String nombre,

        @Size(max = 60)
        String presentacion,

        @Size(max = 400)
        String descripcion,

        // El esquema exige costo > 0: un producto con costo cero haría que
        // la regla de margen calculara un precio de venta de cero.
        @NotNull(message = "El costo es obligatorio")
        @DecimalMin(value = "0.01", message = "El costo debe ser mayor que cero")
        BigDecimal costo,

        @PositiveOrZero(message = "La existencia inicial no puede ser negativa")
        Integer existencia,

        @PositiveOrZero(message = "La existencia mínima no puede ser negativa")
        Integer existenciaMinima
) {}

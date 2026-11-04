package co.com.bspstore.sgip.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Lo que la pantalla de edición puede cambiar de un producto (RF-15).
 *
 * Es casi la misma forma que {@link SolicitudProducto} y aun así es un
 * registro aparte, por una sola diferencia que importa: aquí no hay
 * existencia. Reutilizar el de creación habría dejado en el contrato un
 * campo que el servidor tendría que ignorar en silencio, y un campo que
 * se envía y no hace nada es peor que no tenerlo. La existencia cambia
 * por movimientos de inventario, que dejan rastro.
 *
 * Los tres campos del precio son opcionales porque solo se manda lo que
 * alguien escribió: quien corrige el nombre de un producto que sigue la regla
 * general no ha decidido fijarle un precio propio, y mandárselo lo dejaría
 * fuera de esa regla sin que nadie lo pidiera.
 *
 * {@code creditoIgualAlContado} es la casilla «Usar otro precio a crédito»
 * vista al revés:
 *
 *   sin decir nada, con contado   el crédito se guarda igual que el contado
 *   sin decir nada, sin contado   el crédito no cambia
 *   true                          el crédito pasa a ser igual al contado
 *   false                         el crédito no cambia aunque cambie el contado
 *
 * Con {@code credito} escrito se usa ese y la marca no puede venir en true.
 */
public record SolicitudEdicionProducto(

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

        @NotNull(message = "El costo es obligatorio")
        @DecimalMin(value = "0.01", message = "El costo debe ser mayor que cero")
        BigDecimal costo,

        @PositiveOrZero(message = "La existencia mínima no puede ser negativa")
        Integer existenciaMinima,

        SolicitudPrecio contado,

        SolicitudPrecio credito,

        Boolean creditoIgualAlContado
) {}

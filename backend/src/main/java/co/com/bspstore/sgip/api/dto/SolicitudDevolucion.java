package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.logica.venta.LimitesDeVenta;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Lo que el mostrador envía para devolver o anular (RF-68).
 *
 * {@code unidades} vacía anula la venta entera: devuelve todo lo que aún no se había devuelto.
 * Con unidades, devuelve solo esas. Falta del campo no es lo mismo que lista vacía: anular es una
 * decisión que se toma a propósito. {@code reembolsos} dice cómo se le devuelve el dinero al cliente y
 * tiene que sumar lo que valen las unidades (una venta por cobrar no devolvió nada y no lleva ninguno).
 * Una devolución siempre pide la {@code autorizacion} de un administrador (ADR-0032).
 *
 * {@code clave} la inventa el cliente una vez por devolución y la repite si reintenta (doble clic, red
 * lenta): una sola devolución queda registrada y el reintento recibe la misma respuesta.
 *
 * Los mensajes de validación son fijos y no repiten lo enviado.
 */
public record SolicitudDevolucion(

        @NotNull(message = "La clave de idempotencia es obligatoria")
        UUID clave,

        @NotNull(message = "La devolución necesita la venta")
        @Positive(message = "La venta no es válida")
        Long ventaId,

        @NotNull(message = "Indica las unidades devueltas; una lista vacía anula la venta entera")
        @Size(max = LimitesDeVenta.LINEAS, message = "La devolución admite como máximo 200 productos")
        List<@NotNull(message = "Una unidad devuelta está vacía") @Valid UnidadDevuelta> unidades,

        @Size(max = LimitesDeVenta.MOTIVO_DE_LA_DEVOLUCION, message = "El motivo admite hasta 200 caracteres")
        String motivo,

        @Size(max = LimitesDeVenta.PAGOS, message = "La devolución admite como máximo 10 reembolsos")
        List<@NotNull(message = "Un reembolso está vacío") @Valid Reembolso> reembolsos,

        SolicitudAutorizacion autorizacion
) {
}

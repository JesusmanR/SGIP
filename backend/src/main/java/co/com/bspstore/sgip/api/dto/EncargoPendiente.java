package co.com.bspstore.sgip.api.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Una línea de encargo de la lista del administrador (RF-42, RF-69). {@code estado} es
 * {@code PENDIENTE} mientras la existencia del producto siga en negativo y {@code LLEGO} cuando ya
 * es cero o más: se calcula al consultar, no se guarda. Trae datos personales (nombre y teléfono del
 * cliente): salen solo en esta ruta, que es del administrador, y nunca en un registro del servidor.
 */
public record EncargoPendiente(
        Long itemId,
        Long ventaId,
        String consecutivo,
        OffsetDateTime fecha,
        String cliente,
        String telefono,
        Integer productoId,
        String codigo,
        String producto,
        int cantidad,
        int cantidadEncargo,
        LocalDate entregaEstimada,
        int existencia,
        String estado,
        long antiguedadDias
) {}

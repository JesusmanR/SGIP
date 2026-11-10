package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.modelo.TipoDocumento;

/**
 * Un cliente de la caja (RF-66).
 *
 * {@code nombre} e {@code identificacion} son la razón social y el NIT de la tabla
 * {@code clientes}: la tabla nació para empresas y el mostrador vende sobre todo a
 * personas, así que el nombre del campo es el del uso. El identificador es nulo
 * mientras el cliente no se ha escrito.
 *
 * {@code consumidorFinal} marca al cliente genérico que toda empresa tiene para las
 * ventas sin datos del comprador; lo gestiona el sistema y no se edita.
 */
public record Cliente(
        Integer id,
        String nombre,
        TipoDocumento tipoDocumento,
        String identificacion,
        String correo,
        String telefono,
        String direccion,
        String ciudad,
        boolean consumidorFinal
) {}

package co.com.bspstore.sgip.api.dto;

import java.time.LocalDateTime;

/**
 * Una fila del kárdex (RF-49).
 *
 * Lleva las dos existencias y no solo la cantidad, porque es lo que
 * permite leer la historia de una referencia sin ir sumando: cada fila
 * dice de dónde salió el saldo y dónde quedó. Es también lo que hace
 * detectable un hueco en la cadena —el posterior de una fila tiene que
 * ser el anterior de la siguiente— que es la única incoherencia que las
 * restricciones de la V23 no pueden ver, porque miran una fila a la vez.
 *
 * `usuario` es el nombre y no el identificador: el kárdex se lee para
 * saber quién movió qué, y un número obliga a otra consulta.
 */
public record MovimientoDeInventario(
        Long movimientoId,
        Integer productoId,
        String codigo,
        String producto,
        String tipo,
        Integer cantidad,
        Integer existenciaAnterior,
        Integer existenciaPosterior,
        String documento,
        String motivo,
        Long recepcionId,
        Integer usuarioId,
        String usuario,
        LocalDateTime fecha
) {}

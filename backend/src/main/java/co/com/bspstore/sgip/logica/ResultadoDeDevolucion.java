package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.DevolucionRegistrada;

/**
 * Una devolución y si ya existía: un reintento con la misma clave de idempotencia devuelve la devolución
 * registrada (200) en lugar de crear otra (201).
 */
public record ResultadoDeDevolucion(DevolucionRegistrada devolucion, boolean yaExistia) {
}

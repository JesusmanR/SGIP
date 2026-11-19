/**
 * La venta de la caja que quedó sin saber si se registró (resultado ambiguo).
 *
 * La clave de idempotencia y el cuerpo exacto que se envió viven en memoria; si el cajero
 * recarga la pestaña, se perderían y la venta quedaría a medias (cobrada o no, sin remedio).
 * Por eso se guardan en sessionStorage, que muere con la pestaña como la sesión:
 * `{ clave, cuerpo, motivos }`. Los `motivos` son lo que un vendedor necesita que autorice un
 * administrador al reintentar; el PIN NUNCA se guarda: el reintento lo pide de nuevo.
 *
 * Todo va en try/catch: el almacenamiento puede estar bloqueado o lleno, y entonces la caja
 * sigue funcionando solo en memoria.
 */
export const CLAVE_VENTA_PENDIENTE = 'sgip.venta-pendiente'

export function guardarVentaPendiente(pendiente) {
  try {
    sessionStorage.setItem(CLAVE_VENTA_PENDIENTE, JSON.stringify(pendiente))
  } catch {
    // Sin almacenamiento no hay recuperación tras recargar, pero la venta en curso sigue.
  }
}

export function borrarVentaPendiente() {
  try {
    sessionStorage.removeItem(CLAVE_VENTA_PENDIENTE)
  } catch {
    // Nada que borrar si no se puede escribir.
  }
}

/** Lo guardado, o null si no hay nada o no tiene la forma esperada (un valor ajeno no se confía). */
export function leerVentaPendiente() {
  try {
    const crudo = sessionStorage.getItem(CLAVE_VENTA_PENDIENTE)
    if (!crudo) return null
    const { clave, cuerpo, motivos } = JSON.parse(crudo)
    const valido = typeof clave === 'string' && clave !== ''
      && cuerpo && typeof cuerpo === 'object' && cuerpo.claveIdempotencia === clave
      && Array.isArray(motivos) && motivos.every((m) => typeof m === 'string')
    return valido ? { clave, cuerpo, motivos } : null
  } catch {
    return null
  }
}

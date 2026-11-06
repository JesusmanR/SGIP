/**
 * Lo que escribe una persona en un campo numérico, leído sin perder lo que el
 * servidor va a revisar: cuántas cifras enteras y cuántos decimales trae.
 * Acepta coma o punto como separador decimal y no acepta signo ni miles.
 * Devuelve null si no es un número escrito así.
 */
export function numeroEscrito(texto) {
  const limpio = String(texto ?? '').trim().replace(',', '.')
  const partes = /^(\d+)(?:\.(\d+))?$/.exec(limpio)
  if (!partes) return null
  return { valor: Number(limpio), enteros: partes[1].length, decimales: (partes[2] ?? '').length }
}

/**
 * Los 400 de validación del servidor llegan en un solo texto, «campo: mensaje;
 * campo: mensaje» (ManejadorErrores). Si cada parte nombra un campo de este
 * formulario, devuelve el mensaje de cada uno para ponerlo junto a su campo.
 * Si alguna parte no encaja, devuelve null y el texto entero va arriba: es
 * mejor un aviso general que un mensaje junto al campo equivocado.
 */
export function erroresPorCampo(detalle, campos) {
  const resultado = {}
  for (const parte of String(detalle ?? '').split('; ')) {
    const encontrada = /^(\w+): (.+)$/.exec(parte)
    if (!encontrada || !campos.includes(encontrada[1])) return null
    const [, campo, mensaje] = encontrada
    resultado[campo] = resultado[campo] ? `${resultado[campo]} ${mensaje}` : mensaje
  }
  return Object.keys(resultado).length > 0 ? resultado : null
}

/** Quita un campo de un conjunto de errores sin tocar los demás. */
export function sinCampo(errores, campo) {
  if (!(campo in errores)) return errores
  const resto = { ...errores }
  delete resto[campo]
  return resto
}

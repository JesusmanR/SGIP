import { NUMERO } from '../../servicios/formato.js'

export const LIMITES = [50, 100, 250, 500]
export const LIMITE_INICIAL = 100
export const LIMITE_MAXIMO = 500

const TIPOS = {
  INGRESO: { texto: 'Ingreso', clase: 'ingreso' },
  EGRESO: { texto: 'Salida', clase: 'salida' },
  DEVOLUCION: { texto: 'Devolución', clase: 'devolucion' },
  AJUSTE: { texto: 'Ajuste', clase: 'ajuste' },
}

export function tipoDe(tipo) {
  return TIPOS[tipo] ?? { texto: tipo, clase: 'ajuste' }
}

/**
 * Cuánto cambió la existencia. Sale de las dos existencias y no de
 * `cantidad`: en un ajuste la cantidad es absoluta y en una salida es
 * positiva aunque la mercancía salga.
 */
export function variacion(m) {
  return m.existenciaPosterior - m.existenciaAnterior
}

/** `+24`, `-3` o `0`: el signo va siempre en texto, el color solo acompaña. */
export function conSigno(n) {
  if (n > 0) return `+${NUMERO.format(n)}`
  if (n < 0) return `-${NUMERO.format(-n)}`
  return '0'
}

/** `1 unidad`, `30 unidades`. */
export function unidades(n) {
  return `${NUMERO.format(n)} ${Math.abs(n) === 1 ? 'unidad' : 'unidades'}`
}

export const NOTA_SIN_BORRAR =
  'Los movimientos no se editan ni se borran. Si algo quedó mal, corrígelo con otro ajuste.'

/** Cuántos resultados pide la búsqueda de producto; es el tope de `buscarProductos`. */
export const TOPE_BUSQUEDA = 10

/** Un identificador de producto válido: entero positivo de hasta 9 cifras, sin ceros a la izquierda. */
export function idValido(texto) {
  return /^[1-9]\d{0,8}$/.test(texto ?? '')
}

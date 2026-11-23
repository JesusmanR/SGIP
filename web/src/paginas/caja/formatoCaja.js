import { PESOS } from '../../servicios/formato.js'

const MEDIOS = {
  EFECTIVO: 'Efectivo', TARJETA: 'Tarjeta', TRANSFERENCIA: 'Transferencia', SALDO_A_FAVOR: 'Saldo a favor',
}

/** El nombre de un tipo de medio de pago como lo lee el cajero. */
export const nombreDeMedio = (tipo) => MEDIOS[tipo] ?? tipo

/**
 * La diferencia de un cierre dicha con palabras: sobra, falta o cuadra.
 * `diferencia` es contado menos esperado: positiva sobra, negativa falta.
 */
export function textoDeDiferencia(diferencia, { cuadra = 'Cuadra' } = {}) {
  const n = Number(diferencia)
  if (n > 0) return `Sobran ${PESOS.format(n)}`
  if (n < 0) return `Faltan ${PESOS.format(-n)}`
  return cuadra
}

/** Fecha y hora cortas, dd/mm/aaaa hh:mm (RNF-16); «—» si no hay. */
export function fechaHora(iso) {
  if (!iso) return '—'
  const f = new Date(iso)
  if (Number.isNaN(f.getTime())) return '—'
  return f.toLocaleString('es-CO', {
    day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false,
  })
}

/** Una fecha de documento, dd/mm/aaaa (RNF-16), a partir de «aaaa-mm-dd»; «—» si no hay. */
export function fechaDocumento(iso) {
  const partes = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso ?? '')
  return partes ? `${partes[3]}/${partes[2]}/${partes[1]}` : '—'
}

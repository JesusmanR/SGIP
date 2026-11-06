import { PESOS } from '../../servicios/formato.js'

/** El estado que calcula el servidor, dicho como lo dice la gente de tienda. */
const ESTADOS = {
  ABIERTA: 'Pendiente',
  PARCIAL: 'Recibida en parte',
  RECIBIDA: 'Recibida',
  ANULADA: 'Anulada',
}

export const nombreDeEstado = (estado) => ESTADOS[estado] ?? estado

/** Siempre con texto: los colores de estado no bastan por sí solos (ADR-0009). */
export function InsigniaEstado({ estado }) {
  return <span className={`insignia estado-${String(estado).toLowerCase()}`}>{nombreDeEstado(estado)}</span>
}

const CON_CENTAVOS = new Intl.NumberFormat('es-CO', {
  style: 'currency', currency: 'COP', minimumFractionDigits: 2, maximumFractionDigits: 2,
})

/**
 * Un importe de compras, en pesos. El costo de una compra admite centavos
 * (el servidor guarda dos decimales), así que uno entero se escribe sin
 * decimales y uno con centavos los conserva en lugar de redondearlos.
 */
export function pesos(valor) {
  const n = Number(valor)
  return Number.isInteger(n) ? PESOS.format(n) : CON_CENTAVOS.format(n)
}

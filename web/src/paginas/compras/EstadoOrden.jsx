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

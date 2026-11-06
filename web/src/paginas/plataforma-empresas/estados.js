/** Cómo se llama cada estado de una empresa. Siempre se pinta con texto. */
export const ESTADOS = {
  PRUEBA: { rotulo: 'En prueba', clase: 'pendiente' },
  ACTIVA: { rotulo: 'Activa', clase: 'activo' },
  DESACTIVADA: { rotulo: 'Desactivada', clase: 'inactivo' },
}

export function estadoDe(estado) {
  return ESTADOS[estado] ?? { rotulo: estado ?? 'Sin estado', clase: 'inactivo' }
}

import { estadoDe } from './estados.js'

/** El estado de una empresa en texto: los colores de estado se confunden en escala de grises (ADR-0009). */
export default function EstadoEmpresa({ estado }) {
  const { rotulo, clase } = estadoDe(estado)
  return <span className={`estado-empresa ${clase}`}>{rotulo}</span>
}

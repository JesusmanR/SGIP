import '../../estilos/comun.css'

const TIPOS = {
  exito: { rol: 'status', rotulo: 'Listo' },
  info: { rol: 'status', rotulo: 'Información' },
  aviso: { rol: 'status', rotulo: 'Atención' },
  peligro: { rol: 'alert', rotulo: 'Error' },
}

/**
 * Los colores de estado son casi el mismo gris en escala de grises
 * (ADR-0009): por eso el rótulo va siempre en texto, además del color.
 */
export default function Aviso({ tipo = 'info', children }) {
  const { rol, rotulo } = TIPOS[tipo] ?? TIPOS.info
  return (
    <div className={`aviso-estado aviso-estado--${tipo}`} role={rol}>
      <strong>{rotulo}: </strong>{children}
    </div>
  )
}

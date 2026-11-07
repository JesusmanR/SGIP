import '../../estilos/comun.css'
import IconoEstado from './IconoEstado.jsx'

export default function EstadoVacio({ titulo, detalle, accion }) {
  return (
    <div className="estado-comun">
      <IconoEstado tipo="vacio" />
      <p className="estado-titulo">{titulo}</p>
      {detalle && <p>{detalle}</p>}
      {accion}
    </div>
  )
}

import '../../estilos/comun.css'

export default function EstadoVacio({ titulo, detalle, accion }) {
  return (
    <div className="estado-comun">
      <p className="estado-titulo">{titulo}</p>
      {detalle && <p>{detalle}</p>}
      {accion}
    </div>
  )
}

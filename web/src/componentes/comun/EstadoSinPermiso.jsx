import '../../estilos/comun.css'

export default function EstadoSinPermiso() {
  return (
    <div className="estado-comun">
      <p className="estado-titulo">No tienes permiso para ver esto</p>
      <p>Si lo necesitas, pídele acceso al administrador de tu empresa.</p>
    </div>
  )
}

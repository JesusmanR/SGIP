import '../../estilos/comun.css'

export default function EstadoCarga({ mensaje = 'Cargando…' }) {
  return (
    <div className="estado-comun" role="status">
      <p>{mensaje}</p>
    </div>
  )
}

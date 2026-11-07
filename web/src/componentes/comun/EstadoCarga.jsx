import '../../estilos/comun.css'

export default function EstadoCarga({ mensaje = 'Cargando…' }) {
  return (
    <div className="estado-comun" role="status">
      <div className="estado-esqueleto" aria-hidden="true">
        <span className="estado-barra estado-barra--titulo" />
        <span className="estado-barra estado-barra--fila" />
        <span className="estado-barra estado-barra--fila" />
        <span className="estado-barra estado-barra--fila" />
      </div>
      <p><span className="estado-giro" aria-hidden="true" />{mensaje}</p>
    </div>
  )
}

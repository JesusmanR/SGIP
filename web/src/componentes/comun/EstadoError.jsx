import '../../estilos/comun.css'
import IconoEstado from './IconoEstado.jsx'

/** error puede ser un Error (el mensaje de la API) o un texto. */
export default function EstadoError({ error, alReintentar }) {
  const mensaje = typeof error === 'string' ? error : error?.message
  return (
    <div className="estado-comun estado-error" role="alert">
      <IconoEstado tipo="error" />
      <p className="estado-titulo">No se pudo cargar</p>
      <p>{mensaje || 'Ocurrió un error inesperado.'}</p>
      {alReintentar && (
        <button type="button" className="boton secundario" onClick={alReintentar}>Reintentar</button>
      )}
    </div>
  )
}

import { Link } from 'react-router-dom'
import EncabezadoPagina from '../../componentes/comun/EncabezadoPagina.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import EstadoVacio from '../../componentes/comun/EstadoVacio.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { apiUsuarios } from '../../servicios/api/usuarios.js'

const VOLVER = <Link to="/usuarios">Volver a usuarios</Link>

/**
 * Carga un usuario y pinta los estados que no son «tenerlo»: cargando,
 * sin permiso, no existe y error con reintento. Cuando lo hay, llama a
 * `hijos(usuario, recargar)`.
 */
export default function ConUsuario({ usuarioId, hijos }) {
  const { datos, cargando, error, sinPermiso, recargar } = useDatos(
    () => apiUsuarios.ver(usuarioId),
    [usuarioId],
  )

  if (datos) return hijos(datos, recargar)

  let cuerpo
  if (sinPermiso) cuerpo = <EstadoSinPermiso />
  else if (error?.estado === 404) {
    cuerpo = <EstadoVacio titulo="No encontramos a ese usuario" detalle="Puede que ya no exista." accion={VOLVER} />
  } else if (error) cuerpo = <EstadoError error={error} alReintentar={recargar} />
  else cuerpo = <EstadoCarga mensaje={cargando ? 'Cargando usuario…' : undefined} />

  return (
    <div className="contenido usuarios">
      <EncabezadoPagina titulo="Usuario" />
      {cuerpo}
    </div>
  )
}

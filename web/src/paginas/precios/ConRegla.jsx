import { Link } from 'react-router-dom'
import EncabezadoPagina from '../../componentes/comun/EncabezadoPagina.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import EstadoVacio from '../../componentes/comun/EstadoVacio.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { apiPrecios } from '../../servicios/api/precios.js'
import { estadoDeVigencia, hoyISO } from '../../servicios/fechas.js'

const VOLVER = <Link to="/precios">Volver a las reglas</Link>

// Un identificador de regla es un entero positivo. Lo demás no se manda al servidor.
const ES_ID = /^[1-9]\d{0,9}$/

/**
 * Carga la regla de la ruta y decide si el modo pedido le corresponde:
 * corregir en el sitio solo vale para una que aún no empieza, y reemplazar,
 * para una que rige. Cuando lo hay, llama a `hijos(regla)`.
 */
export default function ConRegla({ reglaId, modo, hijos }) {
  const valido = ES_ID.test(reglaId ?? '')
  const { datos, cargando, error, sinPermiso, recargar } = useDatos(
    () => (valido ? apiPrecios.ver(reglaId) : Promise.resolve(null)),
    [reglaId],
  )

  let cuerpo
  if (!valido || error?.estado === 404) {
    cuerpo = <EstadoVacio titulo="No encontramos esa regla" detalle="Puede que ya no exista." accion={VOLVER} />
  } else if (sinPermiso) cuerpo = <EstadoSinPermiso />
  else if (error) cuerpo = <EstadoError error={error} alReintentar={recargar} />
  else if (cargando || !datos) cuerpo = <EstadoCarga mensaje="Cargando regla…" />
  else {
    const estado = estadoDeVigencia(datos, hoyISO())
    if (modo === 'corregir' && estado === 'rige') {
      cuerpo = (
        <EstadoVacio
          titulo="Esta regla ya rige"
          detalle="Una regla vigente no se corrige hacia atrás, porque las ventas se explican con ella. Para cambiarla, reemplázala: se cierra y empieza otra."
          accion={<Link to={`/precios/${datos.reglaId}/reemplazar`}>Reemplazarla</Link>}
        />
      )
    } else if (modo === 'reemplazar' && estado === 'proxima') {
      cuerpo = (
        <EstadoVacio
          titulo="Esta regla todavía no empieza"
          detalle="Como aún no explica ninguna venta, se corrige en el sitio, sin cerrar nada."
          accion={<Link to={`/precios/${datos.reglaId}/corregir`}>Corregirla</Link>}
        />
      )
    } else if (estado === 'cerrada') {
      cuerpo = (
        <EstadoVacio
          titulo="Esta regla ya está cerrada"
          detalle="Una regla cerrada se conserva en el historial y no se cambia. Si hace falta otra, crea una nueva."
          accion={VOLVER}
        />
      )
    } else {
      return hijos(datos)
    }
  }

  return (
    <div className="contenido seccion-precios">
      <EncabezadoPagina titulo="Regla de ganancia" />
      {cuerpo}
    </div>
  )
}

import { Link, useLocation, useParams } from 'react-router-dom'
import Aviso from '../../componentes/comun/Aviso.jsx'
import EncabezadoPagina from '../../componentes/comun/EncabezadoPagina.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import EstadoVacio from '../../componentes/comun/EstadoVacio.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { verProveedor } from '../../servicios/api/proveedores.js'
import { textoDePlazo } from './plazo.js'

const SIN_DATO = 'No registrado'

const volver = <Link to="/proveedores">Volver a proveedores</Link>

export default function DetalleProveedor() {
  const { proveedorId } = useParams()
  const { state } = useLocation()
  const { datos, cargando, error, sinPermiso, recargar } = useDatos(
    () => verProveedor(proveedorId), [proveedorId],
  )

  if (sinPermiso) return <div className="contenido"><EstadoSinPermiso /></div>
  if (error?.estado === 404) {
    return (
      <div className="contenido">
        <EstadoVacio titulo="Ese proveedor no existe" detalle="Quizá el enlace está mal escrito." accion={volver} />
      </div>
    )
  }
  if (error) return <div className="contenido"><EstadoError error={error} alReintentar={recargar} /></div>
  if (cargando && !datos) return <div className="contenido"><EstadoCarga mensaje="Cargando proveedor…" /></div>

  return (
    <div className="contenido">
      <EncabezadoPagina titulo={datos.razonSocial} acciones={volver} />
      {state?.creado && (
        <Aviso tipo="exito">Proveedor creado. Ya puedes elegirlo al crear productos y órdenes de compra.</Aviso>
      )}
      <dl className="ficha">
        <div><dt>NIT</dt><dd>{datos.nit}</dd></div>
        <div><dt>Persona de contacto</dt><dd>{datos.contacto || SIN_DATO}</dd></div>
        <div><dt>Correo</dt><dd>{datos.correo || SIN_DATO}</dd></div>
        <div><dt>Teléfono</dt><dd>{datos.telefono || SIN_DATO}</dd></div>
        <div><dt>Plazo de pago</dt><dd>{textoDePlazo(datos.plazoDias)}</dd></div>
      </dl>
    </div>
  )
}

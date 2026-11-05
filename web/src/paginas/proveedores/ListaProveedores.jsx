import { Link } from 'react-router-dom'
import EncabezadoPagina from '../../componentes/comun/EncabezadoPagina.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import EstadoVacio from '../../componentes/comun/EstadoVacio.jsx'
import TablaOTarjetas from '../../componentes/comun/TablaOTarjetas.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { listarProveedores } from '../../servicios/api/proveedores.js'
import { textoDePlazo } from './plazo.js'

const SIN_DATO = 'No registrado'

export default function ListaProveedores() {
  const { datos, cargando, error, sinPermiso, recargar } = useDatos(() => listarProveedores(), [])

  const nuevo = <Link className="boton" to="/proveedores/nuevo">Nuevo proveedor</Link>

  let cuerpo
  if (sinPermiso) cuerpo = <EstadoSinPermiso />
  else if (error) cuerpo = <EstadoError error={error} alReintentar={recargar} />
  else if (cargando && !datos) cuerpo = <EstadoCarga mensaje="Cargando proveedores…" />
  else if (datos.length === 0) {
    cuerpo = (
      <EstadoVacio
        titulo="Todavía no hay proveedores"
        detalle="Para crear productos primero necesitas al menos un proveedor."
        accion={<Link className="boton" to="/proveedores/nuevo">Crear el primer proveedor</Link>}
      />
    )
  } else {
    cuerpo = (
      <TablaOTarjetas
        etiqueta="Proveedores"
        filas={datos}
        claveFila={(p) => p.id}
        columnas={[
          {
            clave: 'razonSocial', titulo: 'Razón social', principal: true,
            render: (p) => <Link to={`/proveedores/${p.id}`}>{p.razonSocial}</Link>,
          },
          { clave: 'nit', titulo: 'NIT' },
          { clave: 'contacto', titulo: 'Contacto', render: (p) => p.contacto || SIN_DATO },
          { clave: 'telefono', titulo: 'Teléfono', render: (p) => p.telefono || SIN_DATO },
          { clave: 'plazoDias', titulo: 'Plazo de pago', render: (p) => textoDePlazo(p.plazoDias) },
        ]}
      />
    )
  }

  return (
    <div className="contenido">
      <EncabezadoPagina
        titulo="Proveedores"
        descripcion="A quién le compras. Cada producto nuevo necesita un proveedor."
        acciones={datos?.length ? nuevo : undefined}
      />
      {cuerpo}
    </div>
  )
}

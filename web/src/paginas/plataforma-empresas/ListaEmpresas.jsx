import { Link } from 'react-router-dom'
import EncabezadoPagina from '../../componentes/comun/EncabezadoPagina.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import EstadoVacio from '../../componentes/comun/EstadoVacio.jsx'
import TablaOTarjetas from '../../componentes/comun/TablaOTarjetas.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { apiPlataformaEmpresas } from '../../servicios/api/plataforma-empresas.js'
import { NUMERO } from '../../servicios/formato.js'
import EstadoEmpresa from './EstadoEmpresa.jsx'

const COLUMNAS = [
  {
    clave: 'razonSocial', titulo: 'Empresa', principal: true,
    render: (e) => <Link to={`/plataforma/empresas/${e.empresaId}`}>{e.razonSocial}</Link>,
  },
  { clave: 'nit', titulo: 'NIT' },
  { clave: 'ciudad', titulo: 'Ciudad', render: (e) => e.ciudad || '—' },
  { clave: 'usuarios', titulo: 'Usuarios', numerica: true, render: (e) => NUMERO.format(e.usuarios) },
  { clave: 'estado', titulo: 'Estado', render: (e) => <EstadoEmpresa estado={e.estado} /> },
]

const NUEVA = <Link to="/plataforma/empresas/nueva" className="boton enlace-boton">Nueva empresa</Link>

/**
 * Cuenta las empresas por estado. Es lo único que el panel puede decir con
 * datos reales: no hay una ruta de resumen, así que sale de la propia lista.
 */
function Resumen({ empresas }) {
  const de = (estado) => empresas.filter((e) => e.estado === estado).length
  return (
    <ul className="resumen-empresas" aria-label="Resumen de empresas">
      <li>Empresas: {NUMERO.format(empresas.length)}</li>
      <li>Activas: {NUMERO.format(de('ACTIVA'))}</li>
      <li>En prueba: {NUMERO.format(de('PRUEBA'))}</li>
      <li>Desactivadas: {NUMERO.format(de('DESACTIVADA'))}</li>
    </ul>
  )
}

/** Pantallas 70 y 71 · las empresas clientes de la plataforma. */
export default function ListaEmpresas() {
  const { datos, cargando, error, sinPermiso, recargar } = useDatos(() => apiPlataformaEmpresas.listar(), [])

  let cuerpo
  if (sinPermiso) cuerpo = <EstadoSinPermiso />
  else if (error) cuerpo = <EstadoError error={error} alReintentar={recargar} />
  else if (cargando && !datos) cuerpo = <EstadoCarga mensaje="Cargando empresas…" />
  else if (datos.length === 0) {
    cuerpo = (
      <EstadoVacio
        titulo="Todavía no hay empresas"
        detalle="Da de alta la primera, con la persona que la va a administrar."
      />
    )
  } else {
    cuerpo = (
      <>
        <Resumen empresas={datos} />
        <TablaOTarjetas etiqueta="Empresas" columnas={COLUMNAS} filas={datos} claveFila={(e) => e.empresaId} />
      </>
    )
  }

  return (
    <div className="contenido plataforma-empresas">
      <EncabezadoPagina
        titulo="Empresas"
        descripcion="Las tiendas que usan SGIP. Aquí se administran sus cuentas; sus productos, ventas e inventario no se ven."
        acciones={sinPermiso ? null : NUEVA}
      />
      {cuerpo}
    </div>
  )
}

import { Link, useSearchParams } from 'react-router-dom'
import EncabezadoPagina from '../../componentes/comun/EncabezadoPagina.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { apiInventario } from '../../servicios/api/inventario.js'
import FlujoMovimiento from './FlujoMovimiento.jsx'
import ProductoNoEncontrado from './ProductoNoEncontrado.jsx'

function Cargado({ tipo, productoId }) {
  const { datos, cargando, error, sinPermiso, recargar } = useDatos(
    () => apiInventario.producto(productoId),
    [productoId],
  )
  if (datos) return <FlujoMovimiento tipo={tipo} productoInicial={datos} />

  let cuerpo
  if (sinPermiso) cuerpo = <EstadoSinPermiso />
  else if (error?.estado === 404) cuerpo = <ProductoNoEncontrado />
  else if (error) cuerpo = <EstadoError error={error} alReintentar={recargar} />
  else cuerpo = <EstadoCarga mensaje={cargando ? 'Cargando producto…' : undefined} />
  return (
    <div className="contenido inventario">
      <p className="volver"><Link to="/inventario">Volver a movimientos</Link></p>
      <EncabezadoPagina titulo={tipo === 'ajuste' ? 'Ajustar existencia' : 'Registrar ingreso'} />
      {cuerpo}
    </div>
  )
}

/**
 * Con `?producto=ID` el formulario parte con ese producto elegido: se pide
 * al servidor su nombre y su existencia. Sin él, se elige buscando.
 */
export default function ConProductoInicial({ tipo }) {
  const [params] = useSearchParams()
  const productoId = params.get('producto')
  if (!productoId) return <FlujoMovimiento tipo={tipo} />
  return <Cargado key={productoId} tipo={tipo} productoId={productoId} />
}

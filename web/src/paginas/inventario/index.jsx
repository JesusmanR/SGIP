import { Link, Navigate, Route, Routes, useParams } from 'react-router-dom'
import EncabezadoPagina from '../../componentes/comun/EncabezadoPagina.jsx'
import '../../estilos/inventario.css'
import ConProductoInicial from './ConProductoInicial.jsx'
import HistorialProducto from './HistorialProducto.jsx'
import ListaMovimientos from './ListaMovimientos.jsx'
import { idValido } from './movimientos.js'
import ProductoNoEncontrado from './ProductoNoEncontrado.jsx'

// Con `key` por producto, ir de un historial a otro no arrastra el estado del anterior.
function Historial() {
  const { productoId } = useParams()
  if (!idValido(productoId)) {
    return (
      <div className="contenido inventario">
        <p className="volver"><Link to="/inventario">Volver a movimientos</Link></p>
        <EncabezadoPagina titulo="Historial del producto" />
        <ProductoNoEncontrado />
      </div>
    )
  }
  return <HistorialProducto key={productoId} productoId={productoId} />
}

/**
 * Sección Inventario (P-18, 42–45, 78), solo del administrador. App.jsx la
 * registra una sola vez como «/inventario/*»; las subrutas se declaran aquí.
 */
export default function Seccion() {
  return (
    <Routes>
      <Route index element={<ListaMovimientos />} />
      <Route path="productos/:productoId" element={<Historial />} />
      <Route path="ajustes/nuevo" element={<ConProductoInicial tipo="ajuste" />} />
      <Route path="ingresos/nuevo" element={<ConProductoInicial tipo="ingreso" />} />
      <Route path="*" element={<Navigate to="/inventario" replace />} />
    </Routes>
  )
}

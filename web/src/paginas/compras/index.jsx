import { Link, Route, Routes } from 'react-router-dom'
import EstadoVacio from '../../componentes/comun/EstadoVacio.jsx'
import '../../estilos/compras.css'
import DetalleOrden from './DetalleOrden.jsx'
import ListaOrdenes from './ListaOrdenes.jsx'
import NuevaOrden from './NuevaOrden.jsx'

/**
 * Punto de entrada de la sección. App.jsx la registra una sola vez como
 * «<ruta>/*»; las subrutas se declaran aquí, con este mismo <Routes>.
 */
export default function Seccion() {
  return (
    <Routes>
      <Route index element={<ListaOrdenes />} />
      <Route path="nueva" element={<NuevaOrden />} />
      <Route path=":ordenId" element={<DetalleOrden />} />
      <Route
        path="*"
        element={(
          <div className="contenido seccion-compras">
            <EstadoVacio
              titulo="Esta página no existe"
              accion={<Link to="/compras">Volver a órdenes de compra</Link>}
            />
          </div>
        )}
      />
    </Routes>
  )
}

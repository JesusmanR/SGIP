import { Link, Route, Routes } from 'react-router-dom'
import EstadoVacio from '../../componentes/comun/EstadoVacio.jsx'
import '../../estilos/proveedores.css'
import DetalleProveedor from './DetalleProveedor.jsx'
import ListaProveedores from './ListaProveedores.jsx'
import NuevoProveedor from './NuevoProveedor.jsx'

/**
 * Punto de entrada de la sección. App.jsx la registra una sola vez como
 * «<ruta>/*»; las subrutas se declaran aquí, con este mismo <Routes>.
 */
export default function Seccion() {
  return (
    <Routes>
      <Route index element={<ListaProveedores />} />
      <Route path="nuevo" element={<NuevoProveedor />} />
      <Route path=":proveedorId" element={<DetalleProveedor />} />
      <Route
        path="*"
        element={(
          <div className="contenido seccion-proveedores">
            <EstadoVacio
              titulo="Esta página no existe"
              accion={<Link to="/proveedores">Volver a proveedores</Link>}
            />
          </div>
        )}
      />
    </Routes>
  )
}

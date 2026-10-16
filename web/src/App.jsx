import { Navigate, Route, Routes } from 'react-router-dom'
import Acceso from './paginas/Acceso.jsx'
import Catalogo from './paginas/Catalogo.jsx'
import Empresa from './paginas/Empresa.jsx'
import { haySesion } from './servicios/sesion.js'

/**
 * Esta guarda es comodidad, no seguridad: evita mostrar una pantalla
 * vacía a quien no entró. Quien decide de verdad es el backend, que
 * responde 401 a cualquier petición sin sesión válida. Un front nunca
 * protege datos; solo evita pedirlos en vano.
 */
function Privada({ children }) {
  return haySesion() ? children : <Navigate to="/acceso" replace />
}

export default function App() {
  return (
    <Routes>
      <Route path="/acceso" element={<Acceso />} />
      <Route path="/catalogo" element={<Privada><Catalogo /></Privada>} />
      <Route path="/empresa" element={<Privada><Empresa /></Privada>} />
      <Route path="*" element={<Navigate to={haySesion() ? '/catalogo' : '/acceso'} replace />} />
    </Routes>
  )
}

import { Navigate, Route, Routes } from 'react-router-dom'
import Acceso from './paginas/Acceso.jsx'
import CambiarClave from './paginas/CambiarClave.jsx'
import Catalogo from './paginas/Catalogo.jsx'
import Empresa from './paginas/Empresa.jsx'
import Importacion from './paginas/Importacion.jsx'
import Panel from './paginas/Panel.jsx'
import { debeCambiarClave, haySesion, inicioSegunRol } from './servicios/sesion.js'

/**
 * Esta guarda es comodidad, no seguridad: evita mostrar una pantalla
 * vacía a quien no entró. Quien decide de verdad es el backend, que
 * responde 401 a cualquier petición sin sesión válida. Un front nunca
 * protege datos; solo evita pedirlos en vano.
 */
function Privada({ children }) {
  if (!haySesion()) return <Navigate to="/acceso" replace />
  // Quien aún tiene la contraseña inicial no ve ninguna otra pantalla:
  // el backend le responde 403 a todo lo demás, y esto le ahorra el muro.
  if (debeCambiarClave()) return <Navigate to="/cambiar-clave" replace />
  return children
}

/** La pantalla de cambiar la contraseña: exige sesión, pero no la bloquea. */
function SoloConSesion({ children }) {
  return haySesion() ? children : <Navigate to="/acceso" replace />
}

export default function App() {
  return (
    <Routes>
      <Route path="/acceso" element={<Acceso />} />
      <Route path="/cambiar-clave" element={<SoloConSesion><CambiarClave /></SoloConSesion>} />
      <Route path="/panel" element={<Privada><Panel /></Privada>} />
      <Route path="/catalogo" element={<Privada><Catalogo /></Privada>} />
      <Route path="/empresa" element={<Privada><Empresa /></Privada>} />
      <Route path="/importar" element={<Privada><Importacion /></Privada>} />
      <Route path="*" element={<Navigate to={haySesion() ? inicioSegunRol() : '/acceso'} replace />} />
    </Routes>
  )
}

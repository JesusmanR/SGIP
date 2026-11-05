import { Route, Routes, useParams } from 'react-router-dom'
import '../../estilos/usuarios.css'
import ConUsuario from './ConUsuario.jsx'
import DetalleUsuario from './DetalleUsuario.jsx'
import FormularioUsuario from './FormularioUsuario.jsx'
import ListaUsuarios from './ListaUsuarios.jsx'

// Con `key` por usuario, ir de una ficha a otra no arrastra avisos ni
// confirmaciones de la anterior.
function Ficha() {
  const { usuarioId } = useParams()
  return <DetalleUsuario key={usuarioId} usuarioId={usuarioId} />
}

function Edicion() {
  const { usuarioId } = useParams()
  return (
    <ConUsuario
      key={usuarioId}
      usuarioId={usuarioId}
      hijos={(usuario) => <FormularioUsuario modo="editar" usuario={usuario} />}
    />
  )
}

/**
 * Sección Usuarios (P-20), solo del administrador. App.jsx la registra una
 * sola vez como «/usuarios/*»; las subrutas se declaran aquí.
 */
export default function Seccion() {
  return (
    <Routes>
      <Route index element={<ListaUsuarios />} />
      <Route path="nuevo" element={<FormularioUsuario modo="crear" />} />
      <Route path=":usuarioId" element={<Ficha />} />
      <Route path=":usuarioId/editar" element={<Edicion />} />
      <Route path="*" element={<ListaUsuarios />} />
    </Routes>
  )
}

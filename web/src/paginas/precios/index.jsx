import { Route, Routes, useParams } from 'react-router-dom'
import '../../estilos/precios.css'
import ConRegla from './ConRegla.jsx'
import FormularioRegla from './FormularioRegla.jsx'
import ListaReglas from './ListaReglas.jsx'

// Con `key` por regla, ir de una a otra no arrastra lo escrito en la anterior.
function Edicion({ modo }) {
  const { reglaId } = useParams()
  return (
    <ConRegla
      key={`${modo}-${reglaId}`}
      reglaId={reglaId}
      modo={modo}
      hijos={(regla) => <FormularioRegla modo={modo} regla={regla} />}
    />
  )
}

/**
 * Sección Reglas de ganancia (P-47), solo del administrador. App.jsx la
 * registra una sola vez como «/precios/*»; las subrutas se declaran aquí.
 * Solo hay las rutas del backend: `/precios/reglas`.
 */
export default function Seccion() {
  return (
    <Routes>
      <Route index element={<ListaReglas />} />
      <Route path="nueva" element={<FormularioRegla modo="crear" />} />
      <Route path=":reglaId/corregir" element={<Edicion modo="corregir" />} />
      <Route path=":reglaId/reemplazar" element={<Edicion modo="reemplazar" />} />
      <Route path="*" element={<ListaReglas />} />
    </Routes>
  )
}

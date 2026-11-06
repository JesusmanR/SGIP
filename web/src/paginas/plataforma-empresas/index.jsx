import { Route, Routes, useParams } from 'react-router-dom'
import '../../estilos/plataforma-empresas.css'
import FichaEmpresa from './FichaEmpresa.jsx'
import FormularioEmpresa from './FormularioEmpresa.jsx'
import ListaEmpresas from './ListaEmpresas.jsx'

// Con `key` por empresa, pasar de una ficha a otra no arrastra avisos ni confirmaciones.
function Ficha() {
  const { empresaId } = useParams()
  return <FichaEmpresa key={empresaId} empresaId={empresaId} />
}

/**
 * Sección Empresas de la plataforma (pantallas 70, 71, 74 y 75), solo del
 * super administrador. App.jsx la registra una sola vez como
 * «/plataforma/empresas/*»; las subrutas se declaran aquí.
 */
export default function Seccion() {
  return (
    <Routes>
      <Route index element={<ListaEmpresas />} />
      <Route path="nueva" element={<FormularioEmpresa />} />
      <Route path=":empresaId" element={<Ficha />} />
      <Route path="*" element={<ListaEmpresas />} />
    </Routes>
  )
}

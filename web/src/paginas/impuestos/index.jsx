import { Route, Routes } from 'react-router-dom'
import '../../estilos/impuestos.css'
import FormularioReglaIva from './FormularioReglaIva.jsx'
import Pantalla from './Pantalla.jsx'

/**
 * Sección Impuestos (P-46), solo del administrador. App.jsx la registra una
 * sola vez como «/impuestos/*»; las subrutas se declaran aquí. Solo hay las
 * rutas del backend: `/empresa/tributacion`.
 */
export default function Seccion() {
  return (
    <Routes>
      <Route index element={<Pantalla />} />
      <Route path="reglas/nueva" element={<FormularioReglaIva />} />
      <Route path="*" element={<Pantalla />} />
    </Routes>
  )
}

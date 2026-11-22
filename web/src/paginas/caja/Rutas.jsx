import { lazy, Suspense } from 'react'
import { Route, Routes } from 'react-router-dom'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import Caja from './Caja.jsx'
import CierreTurno from './CierreTurno.jsx'

const Turnos = lazy(() => import('./Turnos.jsx'))

/**
 * Lo que cuelga de `/caja` para quienes venden: la venta, el cierre del turno y los cierres propios.
 * Ninguna tiene entrada de menú propia. Las demás subpantallas de la caja (turnos de todos, etc.)
 * son secciones aparte con su propio permiso.
 */
export default function Rutas() {
  return (
    <Routes>
      <Route path="cierre" element={<CierreTurno />} />
      <Route path="mis-cierres" element={<Suspense fallback={<EstadoCarga />}><Turnos /></Suspense>} />
      <Route path="*" element={<Caja />} />
    </Routes>
  )
}

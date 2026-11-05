import { render } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import Compras from './index.jsx'

export const PRODUCTO_A = { productoId: 30, codigo: 'TOR-6', nombre: 'Tornillo M6', costo: 1500 }
export const PRODUCTO_B = { productoId: 31, codigo: 'TUE-6', nombre: 'Tuerca M6', costo: 3600 }

export const PROVEEDORES = [
  { id: 4, razonSocial: 'Aceros del Valle S.A.S.', nit: '900123456-7', plazoDias: 30 },
  { id: 5, razonSocial: 'Pinturas Sol', nit: '800555111-2', plazoDias: 0 },
]

export const ORDEN = {
  ordenId: 12,
  consecutivo: 'OC-0012',
  proveedorId: 4,
  proveedor: 'Aceros del Valle S.A.S.',
  usuarioId: 1,
  usuario: 'Laura Pérez',
  estado: 'ABIERTA',
  fecha: '2026-11-03T09:14:00',
  observacion: 'Entrega en bodega 2',
  total: 73200,
  items: [
    {
      itemId: 1, productoId: 30, codigo: 'TOR-6', producto: 'Tornillo M6',
      cantidad: 20, cantidadRecibida: 0, pendiente: 20, costoUnitario: 1500, subtotal: 30000,
    },
    {
      itemId: 2, productoId: 31, codigo: 'TUE-6', producto: 'Tuerca M6',
      cantidad: 12, cantidadRecibida: 2, pendiente: 10, costoUnitario: 3600, subtotal: 43200,
    },
  ],
}

/** `ruta` puede ser un texto o `{ pathname, state }`. */
export function montar(ruta = '/compras') {
  return render(
    <MemoryRouter initialEntries={[ruta]}>
      <Routes>
        <Route path="/compras/*" element={<Compras />} />
        <Route path="/proveedores/nuevo" element={<p>pagina:nuevo-proveedor</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

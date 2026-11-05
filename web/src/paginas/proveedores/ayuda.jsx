import { render } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import Proveedores from './index.jsx'

export const PROVEEDOR = {
  id: 4, razonSocial: 'Aceros del Valle S.A.S.', nit: '900123456-7',
  contacto: 'Marta Ruiz', correo: 'marta@aceros.co', telefono: '3101234567', plazoDias: 30,
}

/** `ruta` puede ser un texto o `{ pathname, state }`. */
export function montar(ruta = '/proveedores') {
  return render(
    <MemoryRouter initialEntries={[ruta]}>
      <Routes>
        <Route path="/proveedores/*" element={<Proveedores />} />
      </Routes>
    </MemoryRouter>,
  )
}

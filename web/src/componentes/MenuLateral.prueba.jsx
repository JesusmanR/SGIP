import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import MenuLateral from './MenuLateral.jsx'

describe('menú lateral', () => {
  it('cada grupo es un grupo con nombre, con su lista de enlaces', () => {
    render(
      <MemoryRouter>
        <MenuLateral id="m" rol="ADMINISTRADOR" abierto nombre="Ana" alSalir={() => {}} />
      </MemoryRouter>,
    )
    const grupos = screen.getAllByRole('group')
    expect(grupos.length).toBeGreaterThan(1)
    for (const g of grupos) {
      expect(g).toHaveAccessibleName()
      expect(within(g).getAllByRole('link').length).toBeGreaterThan(0)
    }
  })
})

/**
 * Barra superior.
 *
 * Lo único con criterio aquí es qué pasa al salir cuando la red falla, y
 * la decisión es que la sesión local se cierra igual. Dejar al usuario
 * dentro porque la petición no llegó es lo peor de los dos mundos:
 * parece que salió y no salió.
 */
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import Barra from './Barra.jsx'
import { api } from '../servicios/api.js'
import { guardarSesion, haySesion } from '../servicios/sesion.js'

const navegar = vi.fn()
vi.mock('react-router-dom', async (original) => ({
  ...(await original()),
  useNavigate: () => navegar,
}))

vi.mock('../servicios/api.js', () => ({
  api: { salir: vi.fn() },
  ErrorApi: class extends Error {},
}))

describe('barra superior', () => {
  beforeEach(() => {
    sessionStorage.clear()
    guardarSesion({ token: 'abc', nombre: 'Jesús Manuel', rol: 'ADMINISTRADOR' })
  })
  afterEach(() => vi.clearAllMocks())

  const montar = () => render(<MemoryRouter><Barra /></MemoryRouter>)

  it('saluda con el nombre de quien entró', () => {
    montar()
    expect(screen.getByText('Jesús Manuel')).toBeInTheDocument()
  })

  it('al salir cierra la sesión y vuelve al acceso', async () => {
    const teclado = userEvent.setup()
    api.salir.mockResolvedValue({})
    montar()
    await teclado.click(screen.getByRole('button', { name: /salir/i }))
    await waitFor(() => expect(haySesion()).toBe(false))
    expect(navegar).toHaveBeenCalledWith('/acceso', { replace: true })
  })

  it('y la cierra también si la petición falla', async () => {
    const teclado = userEvent.setup()
    api.salir.mockRejectedValue(new Error('sin red'))
    montar()
    await teclado.click(screen.getByRole('button', { name: /salir/i }))
    await waitFor(() => expect(haySesion()).toBe(false))
    expect(navegar).toHaveBeenCalledWith('/acceso', { replace: true })
  })
})

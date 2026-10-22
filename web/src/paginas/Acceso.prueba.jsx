/**
 * Pantalla P-01 · Acceso.
 *
 * Dos de estas pruebas protegen decisiones, no comportamiento. Que el
 * mensaje de error no diga cuál de los dos campos falló es una decisión
 * de seguridad —distinguirlos confirma qué correos están registrados— y
 * es la clase de cosa que alguien «arregla» con buena intención al
 * intentar ser más útil con el usuario.
 */
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import Acceso from './Acceso.jsx'
import { api, ErrorApi } from '../servicios/api.js'
import { leerSesion } from '../servicios/sesion.js'

const navegar = vi.fn()
vi.mock('react-router-dom', async (original) => ({
  ...(await original()),
  useNavigate: () => navegar,
}))

vi.mock('../servicios/api.js', () => {
  class ErrorApi extends Error {
    constructor(estado, detalle) { super(detalle); this.estado = estado }
  }
  return { api: { entrar: vi.fn() }, ErrorApi }
})

async function entrar(correo = 'ana@bsp.com.co', clave = 'secreta') {
  const teclado = userEvent.setup()
  await teclado.type(screen.getByLabelText(/correo/i), correo)
  await teclado.type(screen.getByLabelText(/contraseña/i), clave)
  await teclado.click(screen.getByRole('button', { name: /entrar/i }))
}

describe('pantalla de acceso', () => {
  beforeEach(() => {
    sessionStorage.clear()
    render(<MemoryRouter><Acceso /></MemoryRouter>)
  })
  afterEach(() => vi.clearAllMocks())

  it('guarda la sesión y entra al catálogo', async () => {
    api.entrar.mockResolvedValue({ token: 'abc', nombre: 'Ana', rol: 'VENDEDOR' })
    await entrar()
    await waitFor(() => expect(leerSesion()?.nombre).toBe('Ana'))
    expect(navegar).toHaveBeenCalledWith('/catalogo', { replace: true })
  })

  it('muestra el motivo que da el servidor', async () => {
    api.entrar.mockRejectedValue(new ErrorApi(401, 'Correo o contraseña incorrectos.'))
    await entrar()
    await waitFor(() => expect(screen.getByRole('alert'))
      .toHaveTextContent('Correo o contraseña incorrectos.'))
  })

  it('no dice cuál de los dos campos falló', async () => {
    api.entrar.mockRejectedValue(new ErrorApi(401, 'Correo o contraseña incorrectos.'))
    await entrar()
    const aviso = await screen.findByRole('alert')
    // Decir «ese correo no existe» confirma qué correos están
    // registrados, que es la mitad del trabajo de quien va a probar
    // contraseñas.
    expect(aviso.textContent).not.toMatch(/no existe|no registrad|contraseña incorrecta\b/i)
  })

  it('un fallo de red no se confunde con unas credenciales malas', async () => {
    api.entrar.mockRejectedValue(new TypeError('Failed to fetch'))
    await entrar()
    await waitFor(() => expect(screen.getByRole('alert'))
      .toHaveTextContent(/no se pudo conectar/i))
    expect(leerSesion()).toBeNull()
  })

  it('no deja reenviar mientras la petición está en vuelo', async () => {
    let resolver
    api.entrar.mockReturnValue(new Promise((r) => { resolver = r }))
    await entrar()
    const boton = screen.getByRole('button', { name: /entrando/i })
    expect(boton).toBeDisabled()
    await act(async () => { resolver({ token: 'abc', nombre: 'Ana' }) })
  })

  it('y vuelve a dejar intentarlo cuando falla', async () => {
    api.entrar.mockRejectedValue(new ErrorApi(401, 'Correo o contraseña incorrectos.'))
    await entrar()
    await screen.findByRole('alert')
    // Si el botón se quedara inhabilitado, un error de tecleo obligaría a
    // recargar la página.
    expect(screen.getByRole('button', { name: /entrar/i })).toBeEnabled()
  })
})

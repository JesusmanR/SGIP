/**
 * Cambio de contraseña obligatorio.
 *
 * Se prueba a través de la aplicación entera y no de la pantalla suelta,
 * porque lo que importa es el recorrido: quien tiene la contraseña
 * inicial no ve ninguna otra pantalla hasta cambiarla, y al cambiarla
 * aterriza donde le toca. Las demás pantallas se sustituyen por un rótulo
 * para no arrastrar sus llamadas al API.
 */
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import App from '../App.jsx'
import { api, ErrorApi } from '../servicios/api.js'
import { guardarSesion, haySesion, leerSesion } from '../servicios/sesion.js'

vi.mock('./Catalogo.jsx', () => ({ default: () => <p>Pantalla del catálogo</p> }))
vi.mock('./Panel.jsx', () => ({ default: () => <p>Pantalla del panel</p> }))
vi.mock('./Empresa.jsx', () => ({ default: () => <p>Pantalla de la empresa</p> }))
vi.mock('./Importacion.jsx', () => ({ default: () => <p>Pantalla de importación</p> }))

vi.mock('../servicios/api.js', async (original) => {
  // ErrorApi es la clase real: la pantalla decide con `instanceof`.
  const real = await original()
  return { api: { cambiarClave: vi.fn(), salir: vi.fn() }, ErrorApi: real.ErrorApi }
})

const PENDIENTE = { token: 'abc', nombre: 'Ana', rol: 'VENDEDOR', debeCambiarClave: true }

function montar(ruta = '/cambiar-clave') {
  return render(<MemoryRouter initialEntries={[ruta]}><App /></MemoryRouter>)
}

async function rellenar({ actual = 'Inicial2026', nueva = 'Nueva2026xyz', repetida = nueva } = {}) {
  const teclado = userEvent.setup()
  await teclado.type(screen.getByLabelText('Contraseña actual'), actual)
  await teclado.type(screen.getByLabelText('Contraseña nueva'), nueva)
  await teclado.type(screen.getByLabelText('Repite la contraseña nueva'), repetida)
  await teclado.click(screen.getByRole('button', { name: /guardar contraseña/i }))
}

describe('cambio de contraseña obligatorio', () => {
  beforeEach(() => {
    sessionStorage.clear()
    guardarSesion(PENDIENTE)
  })
  afterEach(() => vi.clearAllMocks())

  it('pone su propio título a la pestaña', () => {
    montar()
    expect(document.title).toBe('Cambiar contraseña · SGIP')
  })

  describe('quién llega a la pantalla', () => {
    it.each(['/catalogo', '/panel', '/empresa', '/importar'])(
      'con el cambio pendiente, %s lleva a cambiar la contraseña', (ruta) => {
        montar(ruta)
        expect(screen.getByRole('heading', { name: /cambia tu contraseña/i })).toBeInTheDocument()
        expect(screen.queryByText(/^Pantalla del/)).not.toBeInTheDocument()
      })

    it('una ruta desconocida también', () => {
      montar('/lo-que-sea')
      expect(screen.getByRole('heading', { name: /cambia tu contraseña/i })).toBeInTheDocument()
    })

    it('sin sesión no se ve la pantalla: se pide entrar', () => {
      sessionStorage.clear()
      montar('/cambiar-clave')
      expect(screen.queryByRole('heading', { name: /cambia tu contraseña/i })).not.toBeInTheDocument()
      expect(haySesion()).toBe(false)
    })

    it('sin el cambio pendiente las pantallas se ven con normalidad', () => {
      guardarSesion({ ...PENDIENTE, debeCambiarClave: false })
      montar('/catalogo')
      expect(screen.getByText('Pantalla del catálogo')).toBeInTheDocument()
    })
  })

  describe('el formulario', () => {
    it('pide los tres datos con su etiqueta y el autocompletado correcto', () => {
      montar()
      expect(screen.getByLabelText('Contraseña actual')).toHaveAttribute('autocomplete', 'current-password')
      expect(screen.getByLabelText('Contraseña nueva')).toHaveAttribute('autocomplete', 'new-password')
      expect(screen.getByLabelText('Repite la contraseña nueva')).toHaveAttribute('autocomplete', 'new-password')
    })

    it('dice el mínimo de caracteres antes de que se incumpla', () => {
      montar()
      expect(screen.getByText(/al menos 8 caracteres/i)).toBeInTheDocument()
    })

    it('si la nueva y la repetida no coinciden, no llama al API', async () => {
      montar()
      await rellenar({ nueva: 'Nueva2026xyz', repetida: 'Otra2026xyzz' })

      expect(await screen.findByRole('alert')).toHaveTextContent(/no coinciden/i)
      expect(api.cambiarClave).not.toHaveBeenCalled()
    })

    it('al guardar con éxito la sesión queda sin el cambio pendiente y va al catálogo', async () => {
      api.cambiarClave.mockResolvedValue(null)
      montar()
      await rellenar()

      await waitFor(() => expect(screen.getByText('Pantalla del catálogo')).toBeInTheDocument())
      expect(api.cambiarClave).toHaveBeenCalledWith('Inicial2026', 'Nueva2026xyz')
      expect(leerSesion().debeCambiarClave).toBe(false)
      // El token es el mismo: el servidor conserva esta sesión.
      expect(leerSesion().token).toBe('abc')
    })

    it('el administrador, al terminar, va al panel', async () => {
      guardarSesion({ ...PENDIENTE, rol: 'ADMINISTRADOR' })
      api.cambiarClave.mockResolvedValue(null)
      montar()
      await rellenar()

      await waitFor(() => expect(screen.getByText('Pantalla del panel')).toBeInTheDocument())
    })

    it('un 400 muestra el detalle del servidor y deja intentarlo otra vez', async () => {
      api.cambiarClave.mockRejectedValue(new ErrorApi(400, 'La contraseña actual no es correcta.'))
      montar()
      await rellenar()

      expect(await screen.findByRole('alert')).toHaveTextContent('La contraseña actual no es correcta.')
      expect(leerSesion().debeCambiarClave).toBe(true)
      expect(screen.getByRole('button', { name: /guardar contraseña/i })).toBeEnabled()
    })

    it('un fallo de red no se presenta como contraseña incorrecta', async () => {
      api.cambiarClave.mockRejectedValue(new TypeError('Failed to fetch'))
      montar()
      await rellenar()

      expect(await screen.findByRole('alert')).toHaveTextContent(/no se pudo conectar/i)
    })

    it('no deja reenviar mientras la petición está en vuelo', async () => {
      let resolver
      api.cambiarClave.mockReturnValue(new Promise((r) => { resolver = r }))
      montar()
      await rellenar()

      expect(screen.getByRole('button', { name: /guardando/i })).toBeDisabled()
      resolver(null)
      await waitFor(() => expect(screen.getByText('Pantalla del catálogo')).toBeInTheDocument())
    })

    it('si la sesión había expirado, vuelve a pedir entrar', async () => {
      // El API ya borra la sesión al ver un 401.
      api.cambiarClave.mockImplementation(async () => {
        sessionStorage.clear()
        throw new ErrorApi(401, 'La sesión expiró. Vuelve a entrar.')
      })
      montar()
      await rellenar()

      await waitFor(() => expect(screen.queryByRole('heading', { name: /cambia tu contraseña/i }))
        .not.toBeInTheDocument())
    })
  })

  describe('salir sin cambiarla', () => {
    it('cierra la sesión y vuelve al acceso', async () => {
      api.salir.mockResolvedValue(null)
      montar()
      await userEvent.setup().click(screen.getByRole('button', { name: /cerrar sesión/i }))

      await waitFor(() => expect(haySesion()).toBe(false))
      expect(api.salir).toHaveBeenCalled()
    })

    it('cierra la sesión local aunque la red falle', async () => {
      api.salir.mockRejectedValue(new TypeError('Failed to fetch'))
      montar()
      await userEvent.setup().click(screen.getByRole('button', { name: /cerrar sesión/i }))

      await waitFor(() => expect(haySesion()).toBe(false))
    })
  })
})

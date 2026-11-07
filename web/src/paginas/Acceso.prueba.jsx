/**
 * Pantalla P-01 · Acceso.
 *
 * Dos de estas pruebas protegen decisiones, no comportamiento. Que el
 * mensaje de error no diga cuál de los dos campos falló es una decisión
 * de seguridad —distinguirlos confirma qué correos están registrados— y
 * es la clase de cosa que alguien «arregla» con buena intención al
 * intentar ser más útil con el usuario.
 */
import { readFileSync } from 'node:fs'
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
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

vi.mock('../servicios/api.js', async (original) => {
  // ErrorApi es la clase real: la pantalla decide con `instanceof`, y una
  // copia local no lo cumpliría con los errores que lanza la API real.
  const real = await original()
  return { api: { entrar: vi.fn() }, ErrorApi: real.ErrorApi }
})

async function entrar(correo = 'ana@bsp.com.co', clave = 'secreta') {
  const teclado = userEvent.setup()
  await teclado.type(screen.getByLabelText(/correo/i), correo)
  await teclado.type(screen.getByLabelText(/contraseña/i), clave)
  await teclado.click(screen.getByRole('button', { name: /iniciar sesión/i }))
}

describe('pantalla de acceso', () => {
  beforeEach(() => {
    sessionStorage.clear()
    render(<MemoryRouter><Acceso /></MemoryRouter>)
  })
  afterEach(() => vi.clearAllMocks())

  it('pone su propio título a la pestaña', () => {
    expect(document.title).toBe('Acceso · SGIP')
  })

  it('al salir de la pantalla devuelve el título de la aplicación', () => {
    cleanup()
    expect(document.title).toBe('SGIP')
  })

  it('guarda la sesión y entra al catálogo', async () => {
    api.entrar.mockResolvedValue({ token: 'abc', nombre: 'Ana', rol: 'VENDEDOR' })
    await entrar()
    await waitFor(() => expect(leerSesion()?.nombre).toBe('Ana'))
    expect(navegar).toHaveBeenCalledWith('/catalogo', { replace: true })
  })

  it('al administrador lo lleva al panel, que es su primera pantalla', async () => {
    // No es una preferencia de navegación: la especificación de P-02
    // dice que el panel «es la primera pantalla tras el acceso del
    // administrador». La regla vive en `inicioSegunRol` porque tres
    // sitios la deciden —el acceso, la ruta comodín y la barra— y basta
    // olvidar uno para que entrar y recargar lleven a sitios distintos.
    api.entrar.mockResolvedValue({ token: 'abc', nombre: 'Ana', rol: 'ADMINISTRADOR' })
    await entrar()
    await waitFor(() => expect(navegar).toHaveBeenCalledWith('/panel', { replace: true }))
  })

  it('al super administrador no, porque el panel es de una empresa', async () => {
    // El panel enseña el inventario y el capital de UNA empresa, y el
    // super administrador no pertenece a ninguna (ADR-0020). Llevarlo
    // allí le mostraría un panel en cero que no significa nada. Tampoco
    // el catálogo, que lee de la empresa de la sesión: va a la plataforma.
    api.entrar.mockResolvedValue({ token: 'abc', nombre: 'Raíz', rol: 'SUPER_ADMIN' })
    await entrar()
    await waitFor(() => expect(navegar).toHaveBeenCalledWith('/plataforma/empresas', { replace: true }))
  })

  it('con la contraseña inicial pendiente, lo lleva a cambiarla antes que a trabajar', async () => {
    // Vale para cualquier rol: el administrador tampoco pasa por el panel
    // hasta cambiarla. El servidor lo exige igual; esto evita que la
    // primera pantalla sea un muro de 403.
    api.entrar.mockResolvedValue({
      token: 'abc', nombre: 'Ana', rol: 'ADMINISTRADOR', debeCambiarClave: true,
    })
    await entrar()
    await waitFor(() => expect(navegar).toHaveBeenCalledWith('/cambiar-clave', { replace: true }))
    expect(leerSesion().debeCambiarClave).toBe(true)
  })

  it('muestra el motivo que da el servidor', async () => {
    api.entrar.mockRejectedValue(new ErrorApi(401, 'Correo o contraseña incorrectos.'))
    await entrar()
    await waitFor(() => expect(screen.getByRole('alert'))
      .toHaveTextContent('Correo o contraseña incorrectos.'))
  })

  it('unas credenciales rechazadas no se presentan como sesión expirada', async () => {
    // Aquí el módulo de la API está sustituido, así que la prueba usa la
    // implementación real con un fetch simulado: lo que importa es el
    // texto que llega al usuario tras un 401 del login.
    const real = await vi.importActual('../servicios/api.js')
    api.entrar.mockImplementation(real.api.entrar)
    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      json: async () => ({
        detail: 'El correo o la contraseña no son correctos. Si te equivocas varias veces, '
          + 'la cuenta se bloquea durante 15 minutos.',
      }),
    })
    await entrar()
    const aviso = await screen.findByRole('alert')
    // El servidor dice siempre cuánto dura el bloqueo (ADR-0028): la pantalla
    // muestra su texto tal cual, para que quien no entra sepa que esperar sirve.
    expect(aviso).toHaveTextContent('El correo o la contraseña no son correctos.')
    expect(aviso).toHaveTextContent('se bloquea durante 15 minutos')
    expect(aviso.textContent).not.toMatch(/sesión expiró/i)
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
    const boton = screen.getByRole('button', { name: /iniciando sesión/i })
    expect(boton).toBeDisabled()
    await act(async () => { resolver({ token: 'abc', nombre: 'Ana' }) })
  })

  it('y vuelve a dejar intentarlo cuando falla', async () => {
    api.entrar.mockRejectedValue(new ErrorApi(401, 'Correo o contraseña incorrectos.'))
    await entrar()
    await screen.findByRole('alert')
    // Si el botón se quedara inhabilitado, un error de tecleo obligaría a
    // recargar la página.
    expect(screen.getByRole('button', { name: /iniciar sesión/i })).toBeEnabled()
  })

  describe('diseño y teclado', () => {
    it('tiene un único h1 que dice qué se hace aquí', () => {
      const titulos = screen.getAllByRole('heading', { level: 1 })
      expect(titulos).toHaveLength(1)
      expect(titulos[0]).toHaveTextContent(/inicia sesión en tu cuenta/i)
    })

    it('la contraseña va oculta y un botón la muestra y la vuelve a ocultar', async () => {
      const teclado = userEvent.setup()
      const campo = screen.getByLabelText(/contraseña/i)
      expect(campo).toHaveAttribute('type', 'password')
      await teclado.click(screen.getByRole('button', { name: /mostrar contraseña/i }))
      expect(campo).toHaveAttribute('type', 'text')
      await teclado.click(screen.getByRole('button', { name: /ocultar contraseña/i }))
      expect(campo).toHaveAttribute('type', 'password')
    })

    it('no ofrece recuperar la contraseña, registrarse ni otros accesos que no existen', () => {
      expect(screen.queryByText(/olvidaste|crear cuenta|google|mantener sesión|demostración/i))
        .not.toBeInTheDocument()
      expect(screen.queryByRole('link')).not.toBeInTheDocument()
    })

    it('con el teclado se recorre correo, contraseña, mostrar y entrar, y Enter envía', async () => {
      api.entrar.mockResolvedValue({ token: 'abc', nombre: 'Ana', rol: 'VENDEDOR' })
      const teclado = userEvent.setup()
      await teclado.type(screen.getByLabelText(/correo/i), 'ana@bsp.com.co')
      await teclado.tab()
      expect(screen.getByLabelText(/contraseña/i)).toHaveFocus()
      await teclado.keyboard('secreta')
      await teclado.tab()
      expect(screen.getByRole('button', { name: /mostrar contraseña/i })).toHaveFocus()
      await teclado.tab()
      expect(screen.getByRole('button', { name: /iniciar sesión/i })).toHaveFocus()
      await teclado.keyboard('{Enter}')
      await waitFor(() => expect(api.entrar).toHaveBeenCalledWith('ana@bsp.com.co', 'secreta'))
    })

    it('el error se anuncia como alerta y no vacía lo escrito', async () => {
      api.entrar.mockRejectedValue(new ErrorApi(401, 'Correo o contraseña incorrectos.'))
      await entrar()
      expect(await screen.findByRole('alert')).toBeInTheDocument()
      expect(screen.getByLabelText(/correo/i)).toHaveValue('ana@bsp.com.co')
    })
  })

  describe('ronda 1: entrada de contraseña y contraste', () => {
    it('al enviar, la contraseña mostrada vuelve a ocultarse', async () => {
      api.entrar.mockRejectedValue(new ErrorApi(401, 'Correo o contraseña incorrectos.'))
      await userEvent.setup().click(screen.getByRole('button', { name: /mostrar contraseña/i }))
      expect(screen.getByLabelText(/contraseña/i)).toHaveAttribute('type', 'text')
      await entrar()
      await screen.findByRole('alert')
      expect(screen.getByLabelText(/contraseña/i)).toHaveAttribute('type', 'password')
    })

    it('la contraseña no se corrige ni se capitaliza', () => {
      const campo = screen.getByLabelText(/contraseña/i)
      expect(campo).toHaveAttribute('spellcheck', 'false')
      expect(campo).toHaveAttribute('autocapitalize', 'none')
      expect(campo).toHaveAttribute('autocorrect', 'off')
    })

    it('el texto del panel de marca usa un token de texto sobre fondo oscuro, con contraste AA', () => {
      const css = readFileSync('src/estilos/acceso.css', 'utf8')
      const tokens = readFileSync('src/estilos/tokens.css', 'utf8')
      const color = (nombre) => tokens.match(new RegExp(`--${nombre}:\\s*(#[0-9A-Fa-f]{6})`))[1]
      const luz = (hex) => {
        const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
          .map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4))
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
      }
      const razon = (a, b) => {
        const [x, y] = [luz(a), luz(b)].sort((p, q) => q - p)
        return (x + 0.05) / (y + 0.05)
      }
      const regla = css.match(/\.acceso--ingreso \.acceso-descripcion\s*\{[^}]*color:\s*var\(--([\w-]+)\)/)
      expect(regla, 'la descripción debe declarar color con un token').not.toBeNull()
      expect(regla[1]).toBe('sobre-acento')
      expect(razon(color(regla[1]), color('chrome'))).toBeGreaterThanOrEqual(4.5)
    })
  })
})

/**
 * Cambio de contraseña obligatorio.
 *
 * Se prueba a través de la aplicación entera y no de la pantalla suelta,
 * porque lo que importa es el recorrido: quien tiene la contraseña
 * inicial no ve ninguna otra pantalla hasta cambiarla, y al cambiarla
 * aterriza donde le toca. Las demás pantallas se sustituyen por un rótulo
 * para no arrastrar sus llamadas al API.
 */
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
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

// El 400 de clave actual incorrecta se reconoce por el type del problema, no por el texto.
const TIPO_ACTUAL_INCORRECTA = 'urn:sgip:clave-actual-incorrecta'

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

  it('al salir de la pantalla devuelve el título de la aplicación', () => {
    montar()
    cleanup()
    expect(document.title).toBe('SGIP')
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

  describe('diseño, requisitos y teclado', () => {
    it('tiene un único h1', () => {
      montar()
      expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    })

    it('dice de quién es la cuenta que se protege, con el nombre de la sesión', () => {
      montar()
      expect(screen.getByText('Ana')).toBeInTheDocument()
    })

    it('el indicador solo lista lo que el servidor exige: ocho caracteres y distinta de la actual', () => {
      montar()
      const lista = screen.getByRole('list', { name: /requisitos/i })
      expect(within(lista).getAllByRole('listitem')).toHaveLength(2)
      expect(lista).toHaveTextContent(/al menos 8 caracteres/i)
      expect(lista).toHaveTextContent(/distinta de la actual/i)
      expect(lista).not.toHaveTextContent(/mayúscula|minúscula|número|símbolo/i)
    })

    it('cada requisito dice con palabras si se cumple, no solo con color', async () => {
      montar()
      const teclado = userEvent.setup()
      const lista = screen.getByRole('list', { name: /requisitos/i })
      const [ocho, distinta] = within(lista).getAllByRole('listitem')
      expect(ocho).toHaveTextContent(/pendiente/i)
      expect(distinta).toHaveTextContent(/pendiente/i)

      await teclado.type(screen.getByLabelText('Contraseña nueva'), 'corta')
      expect(ocho).toHaveTextContent(/pendiente/i)
      await teclado.type(screen.getByLabelText('Contraseña nueva'), 'larga123')
      expect(ocho).toHaveTextContent(/cumplido/i)

      await teclado.type(screen.getByLabelText('Contraseña actual'), 'cortalarga123')
      expect(distinta).toHaveTextContent(/pendiente/i)
      await teclado.type(screen.getByLabelText('Contraseña actual'), 'X')
      expect(distinta).toHaveTextContent(/cumplido/i)
    })

    it('si no coinciden, el motivo queda junto al campo repetido y lo marca como inválido', async () => {
      montar()
      await rellenar({ nueva: 'Nueva2026xyz', repetida: 'Otra2026xyzz' })
      const alerta = await screen.findByRole('alert')
      expect(alerta).toHaveTextContent(/no coinciden/i)
      const campo = screen.getByLabelText('Repite la contraseña nueva')
      expect(campo).toHaveAttribute('aria-invalid', 'true')
      expect(campo).toHaveAttribute('aria-describedby', expect.stringContaining(alerta.id))
    })

    it('con la cuenta bloqueada (423) muestra el texto del servidor y deja volver a intentar', async () => {
      api.cambiarClave.mockRejectedValue(new ErrorApi(423,
        'Tu cuenta está bloqueada por intentos fallidos. No puedes cambiar la contraseña '
        + 'hasta que pasen 15 minutos desde el bloqueo.'))
      montar()
      await rellenar()
      expect(await screen.findByRole('alert')).toHaveTextContent(/cuenta está bloqueada/i)
      expect(leerSesion().debeCambiarClave).toBe(true)
      expect(screen.getByRole('button', { name: /guardar contraseña/i })).toBeEnabled()
    })

    it('cada contraseña se puede mostrar y ocultar con su propio botón', async () => {
      montar()
      const teclado = userEvent.setup()
      expect(screen.getAllByRole('button', { name: /mostrar/i })).toHaveLength(3)
      const nueva = screen.getByLabelText('Contraseña nueva')
      await teclado.click(screen.getByRole('button', { name: /mostrar contraseña nueva/i }))
      expect(nueva).toHaveAttribute('type', 'text')
      expect(screen.getByLabelText('Contraseña actual')).toHaveAttribute('type', 'password')
      await teclado.click(screen.getByRole('button', { name: /ocultar contraseña nueva/i }))
      expect(nueva).toHaveAttribute('type', 'password')
    })

    it('no ofrece recuperar la contraseña ni registrarse', () => {
      montar()
      expect(screen.queryByText(/olvidaste|crear cuenta|google/i)).not.toBeInTheDocument()
    })

    it('Enter en el último campo envía el formulario', async () => {
      api.cambiarClave.mockResolvedValue(null)
      montar()
      const teclado = userEvent.setup()
      await teclado.type(screen.getByLabelText('Contraseña actual'), 'Inicial2026')
      await teclado.type(screen.getByLabelText('Contraseña nueva'), 'Nueva2026xyz')
      await teclado.type(screen.getByLabelText('Repite la contraseña nueva'), 'Nueva2026xyz{Enter}')
      await waitFor(() => expect(api.cambiarClave).toHaveBeenCalledWith('Inicial2026', 'Nueva2026xyz'))
    })
  })

  describe('ronda 1: foco, errores junto a su campo y entradas de contraseña', () => {
    it('si no coinciden, el foco va al campo «Repite»', async () => {
      montar()
      await rellenar({ nueva: 'Nueva2026xyz', repetida: 'Otra2026xyzz' })
      await screen.findByRole('alert')
      expect(screen.getByLabelText('Repite la contraseña nueva')).toHaveFocus()
    })

    it('la contraseña actual incorrecta (400) va junto a su campo y lo enfoca', async () => {
      api.cambiarClave.mockRejectedValue(new ErrorApi(400, 'La contraseña actual no es correcta.', TIPO_ACTUAL_INCORRECTA))
      montar()
      await rellenar()
      const alerta = await screen.findByRole('alert')
      const actual = screen.getByLabelText('Contraseña actual')
      expect(alerta).toHaveTextContent('La contraseña actual no es correcta.')
      expect(actual).toHaveAttribute('aria-invalid', 'true')
      expect(actual).toHaveAttribute('aria-describedby', expect.stringContaining(alerta.id))
      expect(actual).toHaveFocus()
      // No se repite arriba, en el aviso del formulario.
      expect(screen.getAllByRole('alert')).toHaveLength(1)
    })

    it('la contraseña actual incorrecta se reconoce por el tipo aunque el texto no empiece por «La contraseña actual»', async () => {
      api.cambiarClave.mockRejectedValue(new ErrorApi(400, 'Esa no es tu clave.', TIPO_ACTUAL_INCORRECTA))
      montar()
      await rellenar()
      const alerta = await screen.findByRole('alert')
      const actual = screen.getByLabelText('Contraseña actual')
      expect(alerta).toHaveTextContent('Esa no es tu clave.')
      expect(actual).toHaveAttribute('aria-invalid', 'true')
      expect(actual).toHaveFocus()
    })

    it('un 400 sin tipo cuyo texto empieza por «La contraseña actual» va arriba y no marca el campo', async () => {
      api.cambiarClave.mockRejectedValue(new ErrorApi(400, 'La contraseña actual no es correcta.'))
      montar()
      await rellenar()
      const alerta = await screen.findByRole('alert')
      expect(alerta.className).toMatch(/aviso-estado/)
      expect(screen.getByLabelText('Contraseña actual')).not.toHaveAttribute('aria-invalid', 'true')
    })

    it('otros errores del servidor siguen arriba y no marcan la contraseña actual', async () => {
      api.cambiarClave.mockRejectedValue(new ErrorApi(400, 'La contraseña nueva debe ser distinta de la actual.'))
      montar()
      await rellenar()
      const alerta = await screen.findByRole('alert')
      expect(alerta).toHaveTextContent(/distinta de la actual/i)
      expect(alerta.className).toMatch(/aviso-estado/)
      expect(screen.getByLabelText('Contraseña actual')).not.toHaveAttribute('aria-invalid', 'true')
    })

    it('el 423 sigue arriba como aviso del formulario', async () => {
      api.cambiarClave.mockRejectedValue(new ErrorApi(423, 'Tu cuenta está bloqueada por intentos fallidos.'))
      montar()
      await rellenar()
      const alerta = await screen.findByRole('alert')
      expect(alerta.className).toMatch(/aviso-estado/)
      expect(screen.getByLabelText('Contraseña actual')).not.toHaveAttribute('aria-invalid', 'true')
    })

    it('al enviar, las contraseñas mostradas vuelven a ocultarse', async () => {
      api.cambiarClave.mockRejectedValue(new ErrorApi(400, 'La contraseña actual no es correcta.'))
      montar()
      const teclado = userEvent.setup()
      await teclado.click(screen.getByRole('button', { name: /mostrar contraseña nueva/i }))
      expect(screen.getByLabelText('Contraseña nueva')).toHaveAttribute('type', 'text')
      await rellenar()
      await screen.findByRole('alert')
      expect(screen.getByLabelText('Contraseña nueva')).toHaveAttribute('type', 'password')
      expect(screen.getByRole('button', { name: /mostrar contraseña nueva/i })).toBeInTheDocument()
    })

    it('las entradas no corrigen, ni capitalizan, ni revisan la ortografía de una contraseña', () => {
      montar()
      for (const nombre of ['Contraseña actual', 'Contraseña nueva', 'Repite la contraseña nueva']) {
        const campo = screen.getByLabelText(nombre)
        expect(campo).toHaveAttribute('spellcheck', 'false')
        expect(campo).toHaveAttribute('autocapitalize', 'none')
        expect(campo).toHaveAttribute('autocorrect', 'off')
        expect(campo).toHaveAttribute('maxlength', '100')
      }
    })

    it('teclear no vuelve a leer la sesión', async () => {
      montar()
      const lectura = vi.spyOn(Storage.prototype, 'getItem')
      await userEvent.setup().type(screen.getByLabelText('Contraseña nueva'), 'abcdef')
      expect(lectura).not.toHaveBeenCalled()
      lectura.mockRestore()
    })
  })
})

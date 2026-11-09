/**
 * El contador de la prueba gratuita (RF-73, ADR-0031).
 *
 * El tiempo lo da el servidor; entre una petición y otra se descuenta con
 * `performance.now()`, un reloj que no cambia cuando alguien toca la hora del
 * equipo. Por eso las pruebas mueven `performance.now()` a mano y comprueban
 * que mover `Date` no cambia nada.
 */
import { act, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import ContadorPrueba, { textoDePrueba } from './ContadorPrueba.jsx'
import Armazon from './Armazon.jsx'
import { apiPrueba } from '../servicios/api/prueba.js'
import { guardarSesion } from '../servicios/sesion.js'

vi.mock('../servicios/api/prueba.js', () => ({ apiPrueba: { estado: vi.fn() } }))
vi.mock('../servicios/api.js', () => ({ api: { salir: vi.fn() }, ErrorApi: class extends Error {} }))

const DIA = 86_400
const AVISO_CONSULTA = 'Tu empresa está en modo consulta: puedes ver toda tu información, pero no registrar cambios.'
const ACTIVAR = 'Para activar tu cuenta, comunícate con el equipo de SGIP.'

let monotono = 0

/** El contador visible. No es una región viva: el anuncio va aparte. */
function contador() {
  return document.querySelector('.contador-prueba')
}

/** La región oculta que anuncia los cambios de estado. */
function anuncio() {
  return screen.getByRole('status')
}

/** Deja que la promesa de la petición se resuelva y React pinte. */
async function esperarPeticion() {
  await act(async () => {})
}

function responde(estado, segundosRestantes = null) {
  vi.mocked(apiPrueba.estado).mockResolvedValue({
    estado,
    venceEn: segundosRestantes === null ? null : '2026-12-10T23:59:59-05:00',
    segundosRestantes,
  })
}

/** Pasa el tiempo monótono y deja correr los temporizadores. */
function pasan(segundos) {
  monotono += segundos * 1000
  act(() => { vi.advanceTimersByTime(segundos * 1000) })
}

beforeEach(() => {
  monotono = 1_000
  vi.spyOn(performance, 'now').mockImplementation(() => monotono)
  // Se simulan los temporizadores y Date; performance.now() lo mueve la prueba.
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] })
  vi.setSystemTime(new Date('2026-12-01T12:00:00-05:00'))
  vi.mocked(apiPrueba.estado).mockReset()
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('formato del tiempo restante', () => {
  it.each([
    [12 * DIA, 'Quedan 12 días de prueba'],
    [2 * DIA, 'Quedan 2 días de prueba'],
    [DIA + 5, 'Queda 1 día de prueba'],
    [2 * DIA - 1, 'Queda 1 día de prueba'],
    [5 * 3600 + 20 * 60 + 59, 'Quedan 5 h 20 min de prueba'],
    [DIA - 1, 'Quedan 23 h 59 min de prueba'],
    [45 * 60 + 10, 'Quedan 45 min de prueba'],
    [30, 'Quedan 0 min de prueba'],
  ])('%i s → «%s»', (segundos, texto) => {
    expect(textoDePrueba(segundos)).toBe(texto)
  })
})

describe('visibilidad por rol y por estado', () => {
  it('el administrador ve el tiempo y cómo activar la cuenta', async () => {
    responde('PRUEBA', 12 * DIA)
    render(<ContadorPrueba rol="ADMINISTRADOR" />)
    await esperarPeticion()

    expect(contador()).toHaveTextContent('Quedan 12 días de prueba')
    expect(contador()).toHaveTextContent(ACTIVAR)
  })

  it('el vendedor ve el tiempo pero no cómo activar la cuenta', async () => {
    responde('PRUEBA', 12 * DIA)
    render(<ContadorPrueba rol="VENDEDOR" />)
    await esperarPeticion()

    expect(contador()).toHaveTextContent('Quedan 12 días de prueba')
    expect(screen.queryByText(ACTIVAR)).not.toBeInTheDocument()
  })

  it.each(['CLIENTE', 'SUPER_ADMIN', undefined])('%s no ve nada ni pregunta al servidor', async (rol) => {
    responde('PRUEBA', 12 * DIA)
    const { container } = render(<ContadorPrueba rol={rol} />)
    await esperarPeticion()

    expect(container).toBeEmptyDOMElement()
    expect(apiPrueba.estado).not.toHaveBeenCalled()
  })

  it('con la empresa activa no se muestra nada', async () => {
    responde('ACTIVA')
    const { container } = render(<ContadorPrueba rol="ADMINISTRADOR" />)
    await esperarPeticion()

    expect(container).toBeEmptyDOMElement()
  })

  it.each(['PRUEBA_VENCIDA', 'DESACTIVADA'])('con %s el contador se sustituye por el aviso de modo consulta', async (estado) => {
    responde(estado, estado === 'PRUEBA_VENCIDA' ? 0 : null)
    render(<ContadorPrueba rol="VENDEDOR" />)
    await esperarPeticion()

    expect(contador()).toHaveTextContent(AVISO_CONSULTA)
    expect(screen.queryByText(/de prueba$/)).not.toBeInTheDocument()
  })

  it('con la prueba vencida el administrador ve además cómo activar la cuenta', async () => {
    responde('PRUEBA_VENCIDA', 0)
    render(<ContadorPrueba rol="ADMINISTRADOR" />)
    await esperarPeticion()

    expect(contador()).toHaveTextContent(ACTIVAR)
  })

  it('si el endpoint falla no se muestra nada', async () => {
    vi.mocked(apiPrueba.estado).mockRejectedValue(new Error('sin red'))
    const { container } = render(<ContadorPrueba rol="ADMINISTRADOR" />)
    await esperarPeticion()

    expect(container).toBeEmptyDOMElement()
  })
})

describe('anuncio accesible', () => {
  it('el contador visible no es una región viva', async () => {
    responde('PRUEBA', 12 * DIA)
    render(<ContadorPrueba rol="ADMINISTRADOR" />)
    await esperarPeticion()

    expect(contador()).not.toHaveAttribute('role')
    expect(contador()).not.toHaveAttribute('aria-live')
    expect(contador().querySelector('[role], [aria-live]')).toBeNull()
  })

  it('la región de estado está oculta a la vista y no repite el contador al cargar', async () => {
    responde('PRUEBA', 12 * DIA)
    render(<ContadorPrueba rol="ADMINISTRADOR" />)
    await esperarPeticion()

    expect(anuncio()).toHaveClass('oculto-visualmente')
    expect(anuncio()).toHaveTextContent('')
    expect(anuncio()).not.toHaveAttribute('aria-live')
  })

  it('el último día, pasar un minuto no cambia el texto de la región', async () => {
    responde('PRUEBA', 5 * 3600 + 20 * 60 + 30)
    render(<ContadorPrueba rol="VENDEDOR" />)
    await esperarPeticion()
    const antes = anuncio().textContent

    pasan(60)

    expect(contador()).toHaveTextContent('Quedan 5 h 19 min de prueba')
    expect(anuncio().textContent).toBe(antes)
  })

  it('cuando la prueba vence, la región anuncia el modo consulta una vez', async () => {
    responde('PRUEBA', 90)
    render(<ContadorPrueba rol="VENDEDOR" />)
    await esperarPeticion()

    responde('PRUEBA_VENCIDA', 0)
    pasan(95)
    pasan(2)
    await esperarPeticion()

    expect(anuncio()).toHaveTextContent(AVISO_CONSULTA)
    pasan(60)
    expect(anuncio()).toHaveTextContent(AVISO_CONSULTA)
  })
})

describe('el reloj', () => {
  it('descuenta con performance.now() y no con la hora del equipo', async () => {
    const dateNow = vi.spyOn(Date, 'now')
    responde('PRUEBA', 5 * 3600 + 20 * 60 + 30)
    render(<ContadorPrueba rol="VENDEDOR" />)
    await esperarPeticion()
    expect(contador()).toHaveTextContent('Quedan 5 h 20 min de prueba')

    pasan(21 * 60)
    expect(contador()).toHaveTextContent('Quedan 4 h 59 min de prueba')

    // Adelantar el reloj del equipo un mes no cambia el contador.
    act(() => { vi.setSystemTime(new Date('2027-01-01T12:00:00-05:00')) })
    pasan(1)
    expect(contador()).toHaveTextContent('Quedan 4 h 59 min de prueba')
    expect(dateNow).not.toHaveBeenCalled()
  })

  it('vuelve a preguntar al servidor cada 5 minutos', async () => {
    responde('PRUEBA', 12 * DIA)
    render(<ContadorPrueba rol="VENDEDOR" />)
    await esperarPeticion()
    expect(apiPrueba.estado).toHaveBeenCalledTimes(1)

    pasan(5 * 60)
    await esperarPeticion()
    expect(apiPrueba.estado).toHaveBeenCalledTimes(2)
  })

  it.each(['ACTIVA', 'DESACTIVADA'])('con %s no vuelve a preguntar a los 5 minutos', async (estado) => {
    responde(estado)
    render(<ContadorPrueba rol="ADMINISTRADOR" />)
    await esperarPeticion()

    pasan(5 * 60)
    await esperarPeticion()
    pasan(5 * 60)
    await esperarPeticion()

    expect(apiPrueba.estado).toHaveBeenCalledTimes(1)
  })

  it('al llegar a cero vuelve a preguntar y muestra lo que diga el servidor', async () => {
    responde('PRUEBA', 90)
    render(<ContadorPrueba rol="VENDEDOR" />)
    await esperarPeticion()
    expect(contador()).toHaveTextContent('Quedan 1 min de prueba')

    responde('PRUEBA_VENCIDA', 0)
    pasan(95)
    expect(contador()).toHaveTextContent('Quedan 0 min de prueba')
    // Un pequeño margen antes de volver a preguntar: en el último segundo el
    // servidor todavía puede contestar PRUEBA con 0.
    pasan(2)
    await esperarPeticion()

    expect(apiPrueba.estado).toHaveBeenCalledTimes(2)
    expect(contador()).toHaveTextContent(AVISO_CONSULTA)
  })

  it('al desmontarse deja de preguntar', async () => {
    responde('PRUEBA', 12 * DIA)
    const { unmount } = render(<ContadorPrueba rol="VENDEDOR" />)
    await esperarPeticion()
    unmount()

    pasan(15 * 60)
    expect(apiPrueba.estado).toHaveBeenCalledTimes(1)
  })
})

describe('en el armazón', () => {
  function montar() {
    return render(
      <MemoryRouter initialEntries={['/catalogo']}>
        <Routes>
          <Route element={<Armazon />}>
            <Route path="*" element={<h1>Contenido de la página</h1>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    )
  }

  beforeEach(() => {
    sessionStorage.clear()
    guardarSesion({ token: 'abc', nombre: 'Ana', rol: 'ADMINISTRADOR' })
  })

  it('va en el encabezado', async () => {
    responde('PRUEBA', 12 * DIA)
    montar()
    await esperarPeticion()

    expect(within(screen.getByRole('banner')).getByText('Quedan 12 días de prueba')).toBeInTheDocument()
  })

  it('si el contador falla el armazón sigue funcionando', async () => {
    vi.mocked(apiPrueba.estado).mockRejectedValue(new Error('500'))
    montar()
    await esperarPeticion()

    expect(screen.getByRole('heading', { name: 'Contenido de la página' })).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: /principal/i })).toBeInTheDocument()
    expect(within(screen.getByRole('banner')).queryByRole('status')).not.toBeInTheDocument()
  })
})

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ErrorApi } from './api.js'
import { guardarSesion, haySesion } from './sesion.js'

describe('cliente del API', () => {
  beforeEach(() => {
    sessionStorage.clear()
    global.fetch = vi.fn()
  })
  afterEach(() => { vi.restoreAllMocks() })

  function responder(estado, cuerpo) {
    global.fetch.mockResolvedValue({
      ok: estado >= 200 && estado < 300,
      status: estado,
      json: async () => cuerpo,
    })
  }

  it('no manda cabecera de autorización si no hay sesión', async () => {
    responder(200, [])
    await api.catalogo()
    const [, opciones] = global.fetch.mock.calls[0]
    expect(opciones.headers.Authorization).toBeUndefined()
  })

  it('manda el token cuando hay sesión', async () => {
    guardarSesion({ token: 'abc123', nombre: 'Ana' })
    responder(200, [])
    await api.catalogo()
    const [, opciones] = global.fetch.mock.calls[0]
    expect(opciones.headers.Authorization).toBe('Bearer abc123')
  })

  it('un 401 cierra la sesión local', async () => {
    guardarSesion({ token: 'caducado', nombre: 'Ana' })
    responder(401, {})
    await expect(api.catalogo()).rejects.toBeInstanceOf(ErrorApi)
    expect(haySesion()).toBe(false)
  })

  it('usa el detalle del ProblemDetail como mensaje', async () => {
    responder(409, { detail: 'Ya existe una categoría llamada «Bebidas».' })
    await expect(api.categorias()).rejects.toThrow('Ya existe una categoría llamada «Bebidas».')
  })

  it('un error sin cuerpo JSON no rompe la aplicación', async () => {
    global.fetch.mockResolvedValue({
      ok: false, status: 500, json: async () => { throw new Error('sin cuerpo') },
    })
    await expect(api.categorias()).rejects.toThrow('No se pudo completar la operación.')
  })

  it('el texto de búsqueda se codifica antes de ponerlo en la ruta', async () => {
    responder(200, [])
    await api.catalogo('arroz & panela')
    const [ruta] = global.fetch.mock.calls[0]
    expect(ruta).toBe('/api/v1/productos?busqueda=arroz%20%26%20panela')
  })

  it('un 204 no intenta leer cuerpo', async () => {
    guardarSesion({ token: 'abc', nombre: 'Ana' })
    global.fetch.mockResolvedValue({ ok: true, status: 204 })
    await expect(api.salir()).resolves.toBeNull()
  })
})

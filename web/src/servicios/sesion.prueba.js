import { beforeEach, describe, expect, it } from 'vitest'
import { borrarSesion, guardarSesion, haySesion, leerSesion } from './sesion.js'

describe('sesión del navegador', () => {
  beforeEach(() => { sessionStorage.clear() })

  it('guarda y recupera', () => {
    guardarSesion({ token: 'abc', nombre: 'Ana' })
    expect(leerSesion().nombre).toBe('Ana')
    expect(haySesion()).toBe(true)
  })

  it('sin sesión devuelve nulo', () => {
    expect(leerSesion()).toBeNull()
    expect(haySesion()).toBe(false)
  })

  it('un valor corrupto no deja la aplicación inservible', () => {
    sessionStorage.setItem('sgip.sesion', 'esto no es json')
    expect(leerSesion()).toBeNull()
    // Y además lo limpia, para no repetir el fallo en cada lectura.
    expect(sessionStorage.getItem('sgip.sesion')).toBeNull()
  })

  it('borrar deja la sesión vacía', () => {
    guardarSesion({ token: 'abc' })
    borrarSesion()
    expect(haySesion()).toBe(false)
  })
})

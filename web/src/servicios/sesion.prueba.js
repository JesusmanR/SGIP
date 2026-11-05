import { beforeEach, describe, expect, it } from 'vitest'
import { borrarSesion, guardarSesion, haySesion, inicioSegunRol, leerSesion } from './sesion.js'

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

describe('inicio según el rol', () => {
  it.each([
    ['ADMINISTRADOR', '/panel'],
    ['VENDEDOR', '/catalogo'],
    ['CLIENTE', '/catalogo'],
    // El backend no le sirve catálogo a quien no pertenece a una empresa.
    ['SUPER_ADMIN', '/plataforma/empresas'],
  ])('%s entra en %s', (rol, destino) => {
    expect(inicioSegunRol({ rol })).toBe(destino)
  })

  it('con la clave pendiente va a cambiarla, sea cual sea el rol', () => {
    expect(inicioSegunRol({ rol: 'SUPER_ADMIN', debeCambiarClave: true })).toBe('/cambiar-clave')
  })
})

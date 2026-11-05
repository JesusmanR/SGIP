import { beforeEach, describe, expect, it, vi } from 'vitest'

import { pedir } from '../api.js'
import { apiUsuarios } from './usuarios.js'

vi.mock('../api.js', () => ({ pedir: vi.fn() }))

describe('apiUsuarios', () => {
  beforeEach(() => {
    vi.mocked(pedir).mockReset()
    vi.mocked(pedir).mockResolvedValue({})
  })

  it('lista y ve usuarios con GET', async () => {
    await apiUsuarios.listar()
    expect(pedir).toHaveBeenLastCalledWith('/usuarios')
    await apiUsuarios.ver(7)
    expect(pedir).toHaveBeenLastCalledWith('/usuarios/7')
  })

  it('crea con POST y manda solo lo que el backend recibe', async () => {
    await apiUsuarios.crear({ nombre: 'Ana', correo: 'ana@x.co', clave: 'abcdefgh', rol: 'VENDEDOR', extra: 1 })
    expect(pedir).toHaveBeenLastCalledWith('/usuarios', {
      method: 'POST',
      body: { nombre: 'Ana', correo: 'ana@x.co', clave: 'abcdefgh', rol: 'VENDEDOR' },
    })
  })

  it('edita con PUT solo nombre y rol: ni correo ni contraseña', async () => {
    await apiUsuarios.editar(7, { nombre: 'Ana', rol: 'CLIENTE', correo: 'otro@x.co', clave: 'zzzzzzzz' })
    expect(pedir).toHaveBeenLastCalledWith('/usuarios/7', {
      method: 'PUT', body: { nombre: 'Ana', rol: 'CLIENTE' },
    })
  })

  it('activa, desactiva y desbloquea con POST', async () => {
    await apiUsuarios.activar(7)
    expect(pedir).toHaveBeenLastCalledWith('/usuarios/7/activar', { method: 'POST' })
    await apiUsuarios.desactivar(7)
    expect(pedir).toHaveBeenLastCalledWith('/usuarios/7/desactivar', { method: 'POST' })
    await apiUsuarios.desbloquear(7)
    expect(pedir).toHaveBeenLastCalledWith('/usuarios/7/desbloquear', { method: 'POST' })
  })

  it('no ofrece restablecer contraseña: esa ruta no existe', () => {
    expect(Object.keys(apiUsuarios).sort()).toEqual(
      ['activar', 'crear', 'desactivar', 'desbloquear', 'editar', 'listar', 'ver'],
    )
  })
})

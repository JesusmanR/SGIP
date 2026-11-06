import { beforeEach, describe, expect, it, vi } from 'vitest'

import { pedir } from '../api.js'
import { apiPlataformaEmpresas } from './plataforma-empresas.js'

vi.mock('../api.js', () => ({ pedir: vi.fn() }))

describe('apiPlataformaEmpresas', () => {
  beforeEach(() => {
    vi.mocked(pedir).mockReset()
    vi.mocked(pedir).mockResolvedValue({})
  })

  it('lista y ve empresas con GET', async () => {
    await apiPlataformaEmpresas.listar()
    expect(pedir).toHaveBeenLastCalledWith('/plataforma/empresas')
    await apiPlataformaEmpresas.ver(4)
    expect(pedir).toHaveBeenLastCalledWith('/plataforma/empresas/4')
  })

  it('da de alta mandando solo los campos del backend', async () => {
    const datos = {
      nit: '9001', razonSocial: 'Tienda', correoContacto: 'a@b.co', telefono: '1', ciudad: 'Cali',
      administradorNombre: 'Ana', administradorCorreo: 'ana@b.co', administradorClave: 'abcdefgh',
      estado: 'ACTIVA', plan: 'PRO',
    }
    await apiPlataformaEmpresas.darDeAlta(datos)
    const { body } = vi.mocked(pedir).mock.calls[0][1]
    expect(vi.mocked(pedir).mock.calls[0][0]).toBe('/plataforma/empresas')
    expect(body).not.toHaveProperty('estado')
    expect(body).not.toHaveProperty('plan')
    expect(body.administradorClave).toBe('abcdefgh')
  })

  it('activa y desactiva con POST y motivo opcional', async () => {
    await apiPlataformaEmpresas.activar(4, 'pagó')
    expect(pedir).toHaveBeenLastCalledWith('/plataforma/empresas/4/activar', { method: 'POST', body: { motivo: 'pagó' } })
    await apiPlataformaEmpresas.desactivar(4, '')
    expect(pedir).toHaveBeenLastCalledWith('/plataforma/empresas/4/desactivar', { method: 'POST', body: { motivo: null } })
  })

  it('lista los usuarios de una empresa con GET', async () => {
    await apiPlataformaEmpresas.listarUsuarios(4)
    expect(pedir).toHaveBeenLastCalledWith('/plataforma/empresas/4/usuarios')
  })

  it('desbloquea a un usuario de una empresa', async () => {
    await apiPlataformaEmpresas.desbloquearUsuario(4, 9)
    expect(pedir).toHaveBeenLastCalledWith('/plataforma/empresas/4/usuarios/9/desbloquear', { method: 'POST' })
  })
})

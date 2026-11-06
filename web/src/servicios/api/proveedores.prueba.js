import { beforeEach, describe, expect, it, vi } from 'vitest'

import { pedir } from '../api.js'
import { crearProveedor, listarProveedores, verProveedor } from './proveedores.js'

vi.mock('../api.js', () => ({ pedir: vi.fn(async () => ({ ok: true })) }))

beforeEach(() => vi.clearAllMocks())

describe('servicio de proveedores', () => {
  it('lista con GET /proveedores', async () => {
    await listarProveedores()
    expect(pedir).toHaveBeenCalledWith('/proveedores')
  })

  it('ve uno por su id', async () => {
    await verProveedor(7)
    expect(pedir).toHaveBeenCalledWith('/proveedores/7')
  })

  it('crea con POST y el cuerpo tal cual', async () => {
    const datos = { razonSocial: 'Acme', nit: '900', plazoDias: 0 }
    await crearProveedor(datos)
    expect(pedir).toHaveBeenCalledWith('/proveedores', { method: 'POST', body: datos })
  })
})

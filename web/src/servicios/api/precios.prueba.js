import { beforeEach, describe, expect, it, vi } from 'vitest'

import { pedir } from '../api.js'
import { apiPrecios } from './precios.js'

vi.mock('../api.js', () => ({ pedir: vi.fn() }))

const REGLA = {
  categoriaId: 3, proveedorId: null, formaPago: 'CONTADO', formaCalculo: 'SOBRE_COSTO',
  factor: 1.2, vigenteDesde: '2026-11-06', vigenteHasta: null,
}

describe('apiPrecios', () => {
  beforeEach(() => {
    vi.mocked(pedir).mockReset()
    vi.mocked(pedir).mockResolvedValue({})
  })

  it('lista y ve reglas con GET', async () => {
    await apiPrecios.listar()
    expect(pedir).toHaveBeenLastCalledWith('/precios/reglas')
    await apiPrecios.ver(4)
    expect(pedir).toHaveBeenLastCalledWith('/precios/reglas/4')
  })

  it('el alcance manda solo lo que se eligió', async () => {
    await apiPrecios.alcance(3, null)
    expect(pedir).toHaveBeenLastCalledWith('/precios/reglas/alcance?categoria=3')
    await apiPrecios.alcance(null, 9)
    expect(pedir).toHaveBeenLastCalledWith('/precios/reglas/alcance?proveedor=9')
    await apiPrecios.alcance(null, null)
    expect(pedir).toHaveBeenLastCalledWith('/precios/reglas/alcance')
  })

  it('simula y crea con POST, campo a campo', async () => {
    await apiPrecios.simular({ ...REGLA, extra: 1 })
    expect(pedir).toHaveBeenLastCalledWith('/precios/reglas/simulacion', { method: 'POST', body: REGLA })
    await apiPrecios.crear({ ...REGLA, extra: 1 })
    expect(pedir).toHaveBeenLastCalledWith('/precios/reglas', { method: 'POST', body: REGLA })
  })

  it('corrige con PUT a la regla indicada', async () => {
    await apiPrecios.corregir(4, REGLA)
    expect(pedir).toHaveBeenLastCalledWith('/precios/reglas/4', { method: 'PUT', body: REGLA })
  })

  it('cierra con o sin fecha', async () => {
    await apiPrecios.cerrar(4)
    expect(pedir).toHaveBeenLastCalledWith('/precios/reglas/4/cerrar', { method: 'POST' })
    await apiPrecios.cerrar(4, '2026-11-05')
    expect(pedir).toHaveBeenLastCalledWith('/precios/reglas/4/cerrar?hasta=2026-11-05', { method: 'POST' })
  })

  it('trae categorías y proveedores para elegir', async () => {
    await apiPrecios.categorias()
    expect(pedir).toHaveBeenLastCalledWith('/categorias')
    await apiPrecios.proveedores()
    expect(pedir).toHaveBeenLastCalledWith('/proveedores')
  })
})

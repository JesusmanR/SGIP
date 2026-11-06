import { beforeEach, describe, expect, it, vi } from 'vitest'

import { pedir } from '../api.js'
import { apiInventario } from './inventario.js'

vi.mock('../api.js', () => ({ pedir: vi.fn() }))

describe('apiInventario', () => {
  beforeEach(() => {
    vi.mocked(pedir).mockReset()
    vi.mocked(pedir).mockResolvedValue({})
  })

  it('pide los últimos movimientos con su límite', async () => {
    await apiInventario.movimientos(250)
    expect(pedir).toHaveBeenLastCalledWith('/inventario/movimientos?limite=250')
  })

  it('pide el kárdex, el cuadre y el producto de una referencia', async () => {
    await apiInventario.kardex(5, 100)
    expect(pedir).toHaveBeenLastCalledWith('/inventario/productos/5/movimientos?limite=100')
    await apiInventario.cuadre(5)
    expect(pedir).toHaveBeenLastCalledWith('/inventario/productos/5/cuadre')
    await apiInventario.producto(5)
    expect(pedir).toHaveBeenLastCalledWith('/productos/5')
  })

  it('busca productos en el servidor, con texto codificado y tope', async () => {
    await apiInventario.buscarProductos('tuerca & perno')
    expect(pedir).toHaveBeenLastCalledWith('/productos?busqueda=tuerca%20%26%20perno&limite=10')
  })

  it('ingresa con POST y manda solo lo que el backend recibe', async () => {
    await apiInventario.ingresar({ productoId: 5, cantidad: 24, documento: 'OC-1', motivo: null, extra: 1 })
    expect(pedir).toHaveBeenLastCalledWith('/inventario/ingresos', {
      method: 'POST', body: { productoId: 5, cantidad: 24, documento: 'OC-1', motivo: null },
    })
  })

  it('ajusta con POST el saldo contado, no una diferencia', async () => {
    await apiInventario.ajustar({
      productoId: 5, existenciaContada: 30, motivo: 'Conteo', documento: null, diferencia: 3,
    })
    expect(pedir).toHaveBeenLastCalledWith('/inventario/ajustes', {
      method: 'POST', body: { productoId: 5, existenciaContada: 30, motivo: 'Conteo', documento: null },
    })
  })
})

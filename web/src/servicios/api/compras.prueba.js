import { beforeEach, describe, expect, it, vi } from 'vitest'

import { pedir } from '../api.js'
import { buscarProductos, crearOrden, listarOrdenes, recibirOrden, verOrden } from './compras.js'

vi.mock('../api.js', () => ({ pedir: vi.fn(async () => ({ ok: true })) }))

beforeEach(() => vi.clearAllMocks())

describe('servicio de compras', () => {
  it('lista sin filtros: solo el límite pedido', async () => {
    await listarOrdenes({ limite: 50 })
    expect(pedir).toHaveBeenCalledWith('/compras/ordenes?limite=50')
  })

  it('lista con estado, y no manda el estado vacío', async () => {
    await listarOrdenes({ estado: 'PARCIAL', limite: 100 })
    expect(pedir).toHaveBeenCalledWith('/compras/ordenes?estado=PARCIAL&limite=100')
    await listarOrdenes({ estado: '', limite: 50 })
    expect(pedir).toHaveBeenLastCalledWith('/compras/ordenes?limite=50')
  })

  it('ve una orden', async () => {
    await verOrden(12)
    expect(pedir).toHaveBeenCalledWith('/compras/ordenes/12')
  })

  it('crea una orden con POST', async () => {
    const datos = { proveedorId: 1, consecutivo: 'OC-1', items: [] }
    await crearOrden(datos)
    expect(pedir).toHaveBeenCalledWith('/compras/ordenes', { method: 'POST', body: datos })
  })

  it('registra una recepción en la ruta de la orden', async () => {
    const datos = { items: [{ productoId: 3, cantidadRecibida: 2 }] }
    await recibirOrden(12, datos)
    expect(pedir).toHaveBeenCalledWith('/compras/ordenes/12/recepciones', { method: 'POST', body: datos })
  })

  it('busca productos en el servidor con un límite corto y el texto codificado', async () => {
    await buscarProductos('tornillo m6 & más')
    expect(pedir).toHaveBeenCalledWith('/productos?busqueda=tornillo%20m6%20%26%20m%C3%A1s&limite=8')
  })
})

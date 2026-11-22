import { describe, expect, it } from 'vitest'

import { PINTURA, TORNILLO } from './ayuda.js'
import {
  armarRecibo, lineaDeProducto, lineaDeProductoNuevo, lineasParaVista, puedeCobrar,
} from './venta.js'

const delServidor = (productoId, cantidad, precioUnitario, iva) => ({
  productoId, cantidad, precioUnitario, subtotal: precioUnitario * cantidad, descuento: 0,
  base: precioUnitario * cantidad, tarifaIva: 0.19, tratamientoIva: 'GRAVADO', iva,
  total: precioUnitario * cantidad + iva,
})
const EMPRESA = { razonSocial: 'Black Steel Parts', nit: '1' }
const nuevo = (nombre) => lineaDeProductoNuevo({ nombre, precioVenta: 500, codigoBarras: '', entregaEstimada: '2026-12-01' })

describe('lo que se manda a la vista previa', () => {
  it('cada línea lleva su productoId, o null si es un producto nuevo', () => {
    const lineas = [lineaDeProducto(PINTURA), nuevo('Llave de paso')]
    expect(lineasParaVista(lineas).map((l) => l.productoId)).toEqual([31, null])
  })
})

describe('recibo', () => {
  it('empareja los importes con cada línea por productoId y no por posición (el servidor las ordena por producto)', () => {
    // Se escaneó primero la pintura (31) y después el tornillo (30); el servidor devuelve 30, 31.
    const recibo = armarRecibo({
      empresa: EMPRESA,
      venta: { consecutivo: 'V-1', total: 35700, cambio: 0, lineas: [delServidor(30, 1, 10000, 1900), delServidor(31, 1, 20000, 3800)] },
      lineas: [lineaDeProducto(PINTURA), lineaDeProducto(TORNILLO)],
      pagos: [], cliente: null, cajero: 'Marta', domicilio: null,
    })
    const por = Object.fromEntries(recibo.lineas.map((l) => [l.nombre, l]))
    expect(por['Pintura blanca']).toMatchObject({ precioUnitario: 20000, iva: 3800, total: 23800 })
    expect(por['Tornillo M6']).toMatchObject({ precioUnitario: 10000, iva: 1900, total: 11900 })
  })

  it('los productos nuevos toman las líneas que quedan, en el orden en que se agregaron', () => {
    const recibo = armarRecibo({
      empresa: EMPRESA,
      venta: {
        consecutivo: 'V-2', total: 0, cambio: 0,
        lineas: [delServidor(30, 1, 10000, 1900), delServidor(900, 1, 500, 0), delServidor(901, 2, 700, 0)],
      },
      lineas: [nuevo('Primero'), lineaDeProducto(TORNILLO), { ...nuevo('Segundo'), cantidad: '2' }],
      pagos: [], cliente: null, cajero: 'Marta', domicilio: null,
    })
    const por = Object.fromEntries(recibo.lineas.map((l) => [l.nombre, l]))
    expect(por.Primero).toMatchObject({ precioUnitario: 500, cantidad: 1 })
    expect(por.Segundo).toMatchObject({ precioUnitario: 700, cantidad: 2 })
    expect(por['Tornillo M6'].total).toBe(11900)
  })
})

describe('cuándo se puede cobrar', () => {
  const base = { bloqueo: null, vista: { total: 1000 }, pagos: [{ monto: 1000 }], contraEntrega: false, sinConexion: false, enviando: false }

  it('con todo en orden sí', () => {
    expect(puedeCobrar(base)).toBe(true)
  })

  it.each([
    ['hay un bloqueo', { bloqueo: 'Falta algo.' }],
    ['no hay conexión', { sinConexion: true }],
    ['ya se está enviando', { enviando: true }],
    ['lo pagado no cuadra', { pagos: [{ monto: 900 }] }],
    ['no hay total', { vista: { total: 0 }, pagos: [] }],
  ])('no si %s', (_caso, cambio) => {
    expect(puedeCobrar({ ...base, ...cambio })).toBe(false)
  })

  it('contra entrega no pide pagos', () => {
    expect(puedeCobrar({ ...base, contraEntrega: true, pagos: [] })).toBe(true)
  })
})

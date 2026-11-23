import { describe, expect, it } from 'vitest'

import { PINTURA, TORNILLO } from './ayuda.js'
import {
  armarRecibo, armarSolicitud, lineaDeProducto, lineasParaVista, puedeCobrar,
} from './venta.js'

const delServidor = (productoId, cantidad, precioUnitario, iva) => ({
  productoId, cantidad, precioUnitario, subtotal: precioUnitario * cantidad, descuento: 0,
  base: precioUnitario * cantidad, tarifaIva: 0.19, tratamientoIva: 'GRAVADO', iva,
  total: precioUnitario * cantidad + iva,
})
const EMPRESA = { razonSocial: 'Black Steel Parts', nit: '1' }

describe('lo que se manda a la vista previa', () => {
  it('cada línea lleva su productoId', () => {
    const lineas = [lineaDeProducto(PINTURA), lineaDeProducto(TORNILLO)]
    expect(lineasParaVista(lineas).map((l) => l.productoId)).toEqual([31, 30])
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

  it('un producto que el servidor no devolvió se recibe con importes en cero, sin tomar el de otra línea', () => {
    const recibo = armarRecibo({
      empresa: EMPRESA,
      venta: { consecutivo: 'V-2', total: 0, cambio: 0, lineas: [delServidor(30, 1, 10000, 1900)] },
      lineas: [lineaDeProducto(PINTURA), lineaDeProducto(TORNILLO)],
      pagos: [], cliente: null, cajero: 'Marta', domicilio: null,
    })
    const por = Object.fromEntries(recibo.lineas.map((l) => [l.nombre, l]))
    expect(por['Pintura blanca'].total).toBe(0)
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

describe('la solicitud de venta', () => {
  it('cada línea es un producto del catálogo: lleva su productoId y nunca un productoNuevo', () => {
    const solicitud = armarSolicitud({
      claveIdempotencia: 'k', cliente: null, lineas: [lineaDeProducto(PINTURA), lineaDeProducto(TORNILLO)],
      descuentoTotal: null, pagos: [], motivoEncargo: '', domicilio: null,
    })
    expect(solicitud.lineas.map((l) => l.productoId)).toEqual([31, 30])
    for (const l of solicitud.lineas) expect(l).not.toHaveProperty('productoNuevo')
  })
})

import { describe, expect, it } from 'vitest'

import { calcularVistaPrevia } from './calculo.js'

const linea = (extra = {}) => ({ clave: 'a', precio: 10000, tarifaIva: 0.19, cantidad: 1, descuento: null, ...extra })

describe('vista previa de la venta (misma regla que el servidor)', () => {
  it('10 000 a 0,19 con 10 %: descuento 1 000, base 9 000, IVA 1 710, total 10 710', () => {
    const r = calcularVistaPrevia([linea({ descuento: { tipo: 'PORCENTAJE', valor: 10 } })])
    expect(r.lineas[0]).toMatchObject({ subtotal: 10000, descuento: 1000, base: 9000, iva: 1710, total: 10710 })
    expect(r).toMatchObject({ subtotal: 10000, descuento: 1000, iva: 1710, total: 10710, motivo: null })
  })

  it('el porcentaje se redondea a pesos hacia arriba en la mitad: 10 % de 1 005 es 101', () => {
    const r = calcularVistaPrevia([linea({ precio: 1005, tarifaIva: 0, descuento: { tipo: 'PORCENTAJE', valor: 10 } })])
    expect(r.lineas[0].descuento).toBe(101)
    expect(r.total).toBe(904)
  })

  it('un descuento en pesos es el monto; la cantidad multiplica antes de descontar', () => {
    const r = calcularVistaPrevia([linea({ cantidad: 3, tarifaIva: 0, descuento: { tipo: 'PESOS', valor: 500 } })])
    expect(r.lineas[0]).toMatchObject({ subtotal: 30000, descuento: 500, base: 29500, total: 29500 })
  })

  it('el IVA de cada línea se redondea a pesos sobre su base, y la venta suma los IVA', () => {
    // 1 005 por 0,19 = 190,95: 191 por cada línea; no se redondea la suma.
    const r = calcularVistaPrevia([linea({ clave: 'a', precio: 1005 }), linea({ clave: 'b', precio: 1005 })])
    expect(r.lineas.map((l) => l.iva)).toEqual([191, 191])
    expect(r.iva).toBe(382)
    expect(r.total).toBe(2392)
  })

  it('el descuento de línea va antes que el de toda la compra', () => {
    const r = calcularVistaPrevia(
      [linea({ tarifaIva: 0, descuento: { tipo: 'PORCENTAJE', valor: 50 } })],
      { tipo: 'PORCENTAJE', valor: 10 },
    )
    // 10 000 - 5 000 = 5 000; el 10 % de 5 000 son 500.
    expect(r.lineas[0]).toMatchObject({ descuento: 5500, base: 4500 })
    expect(r.total).toBe(4500)
  })

  it('el descuento de toda la compra se reparte por la base de cada línea y suma exacto', () => {
    const r = calcularVistaPrevia(
      [linea({ clave: 'a', precio: 100, tarifaIva: 0 }), linea({ clave: 'b', precio: 100, tarifaIva: 0 }),
        linea({ clave: 'c', precio: 100, tarifaIva: 0 })],
      { tipo: 'PESOS', valor: 100 },
    )
    // 33,33 por línea: 33 + 33 + 33 = 99, y el peso que falta va a la primera de igual base.
    expect(r.lineas.map((l) => l.descuento)).toEqual([34, 33, 33])
    expect(r.descuento).toBe(100)
    expect(r.total).toBe(200)
  })

  it('un descuento mayor que el valor no se calcula y dice por qué', () => {
    const r = calcularVistaPrevia([linea({ tarifaIva: 0, descuento: { tipo: 'PESOS', valor: 10001 } })])
    expect(r.motivo).toMatch(/descuento/i)
    const p = calcularVistaPrevia([linea({ descuento: { tipo: 'PORCENTAJE', valor: 101 } })])
    expect(p.motivo).toMatch(/100/)
    const t = calcularVistaPrevia([linea()], { tipo: 'PESOS', valor: 99999 })
    expect(t.motivo).toMatch(/descuento/i)
  })

  it('un descuento vacío o en cero no descuenta, y sin líneas todo es cero', () => {
    expect(calcularVistaPrevia([linea({ descuento: { tipo: 'PESOS', valor: 0 } })]).descuento).toBe(0)
    expect(calcularVistaPrevia([])).toMatchObject({ subtotal: 0, descuento: 0, iva: 0, total: 0, motivo: null })
  })

  it('una tarifa con cuatro decimales no pierde precisión (0,0525 de 20 000 son 1 050)', () => {
    const r = calcularVistaPrevia([linea({ precio: 20000, tarifaIva: 0.0525 })])
    expect(r.iva).toBe(1050)
  })
})

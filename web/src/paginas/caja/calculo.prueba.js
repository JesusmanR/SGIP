import { describe, expect, it } from 'vitest'

import { calcularVistaPrevia } from './calculo.js'

const linea = (extra = {}) => ({
  clave: 'a', productoId: 1, precio: 10000, tarifaIva: 0.19, cantidad: 1, descuento: null, ...extra,
})

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
    const r = calcularVistaPrevia([linea({ clave: 'a', productoId: 1, precio: 1005 }), linea({ clave: 'b', productoId: 2, precio: 1005 })])
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

  describe('el reparto del descuento de toda la compra', () => {
    const tres = (ids) => ids.map((id, i) => linea({ clave: `c${i}`, productoId: id, precio: 100, tarifaIva: 0 }))

    it('suma exacto y el peso que sobra va a la línea de menor productoId, no a la primera escaneada', () => {
      const r = calcularVistaPrevia(tres([30, 10, 20]), { tipo: 'PESOS', valor: 100 })
      // 33,33 por línea: 33 + 33 + 33 = 99; el peso que falta es para el producto 10 (la segunda).
      expect(r.lineas.map((l) => [l.clave, l.descuento])).toEqual([['c1', 34], ['c2', 33], ['c0', 33]])
      expect(r.descuento).toBe(100)
      expect(r.total).toBe(200)
    })

    it('un residuo negativo se quita también a la línea de menor productoId', () => {
      // Dos líneas de 1 003 y 1 peso de descuento: cada una redondea a 1 (suma 2) y sobra -1.
      const r = calcularVistaPrevia(
        [linea({ clave: 'A', productoId: 20, precio: 1003, tarifaIva: 0.19 }), linea({ clave: 'B', productoId: 10, precio: 1003, tarifaIva: 0 })],
        { tipo: 'PESOS', valor: 1 },
      )
      expect(r.lineas.map((l) => [l.clave, l.descuento])).toEqual([['B', 0], ['A', 1]])
    })

    it('contraejemplo de la revisión: A (id 20, IVA 0,19) escaneado antes que B (id 10, sin IVA), 1 peso: total 2 195, no 2 196', () => {
      const r = calcularVistaPrevia(
        [linea({ clave: 'A', productoId: 20, precio: 1003, tarifaIva: 0.19 }), linea({ clave: 'B', productoId: 10, precio: 1003, tarifaIva: 0 })],
        { tipo: 'PESOS', valor: 1 },
      )
      expect(r.total).toBe(2195)
      expect(r.iva).toBe(190)
    })

    it('el orden de escaneo no cambia el resultado (IVA mixto, ids desordenados)', () => {
      const a = linea({ clave: 'A', productoId: 20, precio: 1003, tarifaIva: 0.19 })
      const b = linea({ clave: 'B', productoId: 10, precio: 1003, tarifaIva: 0 })
      const c = linea({ clave: 'C', productoId: 15, precio: 2501, tarifaIva: 0.05, cantidad: 2 })
      const descuento = { tipo: 'PORCENTAJE', valor: 7.5 }
      const base = calcularVistaPrevia([a, b, c], descuento)
      for (const orden of [[c, b, a], [b, a, c], [a, c, b]]) {
        const r = calcularVistaPrevia(orden, descuento)
        expect(r.total).toBe(base.total)
        expect(r.iva).toBe(base.iva)
        for (const l of base.lineas) expect(r.lineas.find((x) => x.clave === l.clave)).toEqual(l)
      }
    })

    it('los productos nuevos (sin id) cuentan después de los del catálogo, en el orden en que se agregaron', () => {
      const nuevo = (clave) => linea({ clave, productoId: null, precio: 100, tarifaIva: 0 })
      const r = calcularVistaPrevia(
        [nuevo('n1'), linea({ clave: 'cat', productoId: 99, precio: 100, tarifaIva: 0 }), nuevo('n2')],
        { tipo: 'PESOS', valor: 100 },
      )
      // Orden del servidor: cat (99), n1, n2. El peso que sobra va al primero: cat.
      expect(r.lineas.map((l) => [l.clave, l.descuento])).toEqual([['cat', 34], ['n1', 33], ['n2', 33]])
    })
  })

  it('un descuento mayor que el valor no se calcula y dice por qué', () => {
    const r = calcularVistaPrevia([linea({ tarifaIva: 0, descuento: { tipo: 'PESOS', valor: 10001 } })])
    expect(r.motivo).toMatch(/descuento/i)
    const p = calcularVistaPrevia([linea({ descuento: { tipo: 'PORCENTAJE', valor: 101 } })])
    expect(p.motivo).toMatch(/100/)
    const t = calcularVistaPrevia([linea()], { tipo: 'PESOS', valor: 99999 })
    expect(t.motivo).toMatch(/descuento/i)
  })

  it('un descuento admite hasta 2 decimales, como el servidor', () => {
    expect(calcularVistaPrevia([linea({ descuento: { tipo: 'PORCENTAJE', valor: 7.55 } })]).motivo).toBeNull()
    expect(calcularVistaPrevia([linea({ descuento: { tipo: 'PORCENTAJE', valor: 7.555 } })]).motivo).toMatch(/2 decimales/)
    expect(calcularVistaPrevia([linea()], { tipo: 'PORCENTAJE', valor: 1.001 }).motivo).toMatch(/2 decimales/)
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

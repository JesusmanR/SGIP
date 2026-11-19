import { describe, expect, it } from 'vitest'

import { cantidadAdevolver, unidadesParaApi, valorDeUnidades } from './devolucion.js'

const LINEA = { productoId: 1, cantidad: 3, cantidadDevuelta: 0, total: 1000, valorDevuelto: 0 }

describe('devolución: cálculos', () => {
  it('las devoluciones de una línea suman exactamente su total (333, 333 y 334)', () => {
    expect(valorDeUnidades(LINEA, 1)).toBe(333)
    const despues = { ...LINEA, cantidadDevuelta: 1, valorDevuelto: 333 }
    expect(valorDeUnidades(despues, 1)).toBe(333)
    const ultima = { ...LINEA, cantidadDevuelta: 2, valorDevuelto: 666 }
    expect(valorDeUnidades(ultima, 1)).toBe(334)
    expect(valorDeUnidades(LINEA, 2)).toBe(667)
    expect(valorDeUnidades(LINEA, 3)).toBe(1000)
  })

  it('nunca devuelve más de lo que queda por devolver de la línea (T=9, n=6 y 8 ya devueltos)', () => {
    const linea = { productoId: 1, cantidad: 6, cantidadDevuelta: 4, total: 9, valorDevuelto: 8 }
    // HALF_UP(9/6) sería 2, pero solo quedan 9 - 8 = 1.
    expect(valorDeUnidades(linea, 1)).toBe(1)
    // Y un valor devuelto que ya pasa del total no da negativos.
    expect(valorDeUnidades({ ...linea, valorDevuelto: 12 }, 1)).toBe(0)
  })

  it('la cantidad vale de 0 a lo que queda', () => {
    expect(cantidadAdevolver(LINEA, '')).toBe(0)
    expect(cantidadAdevolver(LINEA, '2')).toBe(2)
    expect(cantidadAdevolver(LINEA, '4')).toBeNaN()
    expect(cantidadAdevolver(LINEA, '1.5')).toBeNaN()
    expect(cantidadAdevolver({ ...LINEA, cantidadDevuelta: 2 }, '2')).toBeNaN()
  })

  it('unidades vacía solo cuando se anula todo y nada se había devuelto', () => {
    expect(unidadesParaApi([LINEA], { 1: '3' })).toEqual([])
    expect(unidadesParaApi([LINEA], { 1: '2' })).toEqual([{ productoId: 1, cantidad: 2 }])
    const parcial = { ...LINEA, cantidadDevuelta: 1, valorDevuelto: 333 }
    expect(unidadesParaApi([parcial], { 1: '2' })).toEqual([{ productoId: 1, cantidad: 2 }])
  })
})

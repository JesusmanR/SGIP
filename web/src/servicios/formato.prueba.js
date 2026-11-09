import { describe, expect, it } from 'vitest'

import {
  costoEnPesos, diaCorto, fechaCorta, fraccionComoPorcentaje, porcentaje,
} from './formato.js'

const DURO = String.fromCharCode(160)

describe('fechaCorta', () => {
  it('escribe día, mes de tres letras, año y hora, con el mismo mes que fechaDia', () => {
    expect(fechaCorta('2026-09-18T09:14:00')).toBe('18 sep 2026, 9:14 a. m.')
    expect(fechaCorta('2026-11-06T21:05:00')).toBe('6 nov 2026, 9:05 p. m.')
  })

  it('las doce de la noche y del mediodía no son las cero', () => {
    expect(fechaCorta('2026-01-01T00:07:00')).toBe('1 ene 2026, 12:07 a. m.')
    expect(fechaCorta('2026-01-01T12:00:00')).toBe('1 ene 2026, 12:00 p. m.')
  })

  it('sin fecha o con una que no es, dice «—»', () => {
    expect(fechaCorta(null)).toBe('—')
    expect(fechaCorta('no')).toBe('—')
  })
})

describe('diaCorto', () => {
  it('es el día del eje del gráfico con el mismo mes escrito', () => {
    expect(diaCorto('2026-09-08')).toBe('8 sep')
    expect(diaCorto('2026-12-31')).toBe('31 dic')
    expect(diaCorto(null)).toBe('')
  })
})

describe('porcentaje', () => {
  it('lleva dos decimales y el espacio duro antes del signo', () => {
    expect(porcentaje(20)).toBe(`20,00${DURO}%`)
    expect(porcentaje(33.333)).toBe(`33,33${DURO}%`)
    expect(porcentaje(-10)).toBe(`-10,00${DURO}%`)
  })

  it('el cero es un dato y lo ausente es «—»', () => {
    expect(porcentaje(0)).toBe(`0,00${DURO}%`)
    expect(porcentaje(null)).toBe('—')
    expect(porcentaje(undefined)).toBe('—')
    expect(porcentaje('abc')).toBe('—')
  })

  it('puede llevar otros decimales y el signo de las subidas', () => {
    expect(porcentaje(12.34, { decimales: 1 })).toBe(`12,3${DURO}%`)
    expect(porcentaje(12.34, { decimales: 1, conSigno: true })).toBe(`+12,3${DURO}%`)
    expect(porcentaje(-5, { decimales: 1, conSigno: true })).toBe(`-5,0${DURO}%`)
    expect(porcentaje(0, { decimales: 1, conSigno: true })).toBe(`0,0${DURO}%`)
  })
})

describe('fraccionComoPorcentaje', () => {
  it('pasa 0,19 a 19,00 % sin los ruidos de la coma flotante', () => {
    expect(fraccionComoPorcentaje(0.19)).toBe(`19,00${DURO}%`)
    expect(fraccionComoPorcentaje('0.0414')).toBe(`4,14${DURO}%`)
    expect(fraccionComoPorcentaje(0)).toBe(`0,00${DURO}%`)
  })
})

describe('costoEnPesos', () => {
  it('un costo entero va sin centavos', () => {
    expect(costoEnPesos(2520000)).toBe(`$${DURO}2.520.000`)
    expect(costoEnPesos('1500')).toBe(`$${DURO}1.500`)
  })

  it('un costo con centavos los conserva', () => {
    expect(costoEnPesos(1234.5)).toBe(`$${DURO}1.234,50`)
    expect(costoEnPesos('99.99')).toBe(`$${DURO}99,99`)
  })
})

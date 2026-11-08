import { describe, expect, it } from 'vitest'

import { estadoDeVigencia, fechaDia, hoyISO, sumarDias, textoDeVigencia } from './fechas.js'

describe('fechas de vigencia', () => {
  it('hoy sale de los componentes locales, no de UTC', () => {
    expect(hoyISO(new Date(2026, 10, 6, 21, 30))).toBe('2026-11-06')
    expect(hoyISO(new Date(2026, 0, 1, 0, 5))).toBe('2026-01-01')
  })

  it('suma y resta días cruzando mes y año', () => {
    expect(sumarDias('2026-11-06', -1)).toBe('2026-11-05')
    expect(sumarDias('2026-03-01', -1)).toBe('2026-02-28')
    expect(sumarDias('2026-12-31', 1)).toBe('2027-01-01')
  })

  it('escribe el día sin correrlo por la zona horaria', () => {
    expect(fechaDia('2026-11-06')).toBe('6 nov 2026')
    expect(fechaDia('2026-01-01')).toBe('1 ene 2026')
    expect(fechaDia(null)).toBe('—')
    expect(fechaDia('no')).toBe('—')
  })

  it('dice si una regla rige, va a empezar o ya terminó', () => {
    expect(estadoDeVigencia({ vigente: true, vigenteDesde: '2026-01-01' }, '2026-11-06')).toBe('rige')
    expect(estadoDeVigencia({ vigente: false, vigenteDesde: '2026-12-01' }, '2026-11-06')).toBe('proxima')
    expect(estadoDeVigencia({ vigente: false, vigenteDesde: '2026-01-01' }, '2026-11-06')).toBe('cerrada')
  })

  it('escribe la vigencia con o sin fin', () => {
    expect(textoDeVigencia({ vigenteDesde: '2026-01-01', vigenteHasta: null })).toBe('Desde el 1 ene 2026')
    expect(textoDeVigencia({ vigenteDesde: '2026-01-01', vigenteHasta: '2026-06-30' }))
      .toBe('Del 1 ene 2026 al 30 jun 2026')
  })
})

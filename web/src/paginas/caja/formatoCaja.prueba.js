import { describe, expect, it } from 'vitest'

import { fechaDocumento, fechaHora, nombreDeMedio, textoDeDiferencia } from './formatoCaja.js'

describe('formato de la caja', () => {
  it('la diferencia se dice con palabras', () => {
    expect(textoDeDiferencia(2000)).toMatch(/^Sobran \$\s2\.000$/)
    expect(textoDeDiferencia(-2000)).toMatch(/^Faltan \$\s2\.000$/)
    expect(textoDeDiferencia(0)).toBe('Cuadra')
    expect(textoDeDiferencia(0, { cuadra: 'El efectivo cuadra' })).toBe('El efectivo cuadra')
  })

  it('los medios tienen nombre de tienda', () => {
    expect(nombreDeMedio('SALDO_A_FAVOR')).toBe('Saldo a favor')
    expect(nombreDeMedio('OTRO')).toBe('OTRO')
  })

  it('la fecha va como dd/mm/aaaa y lo que no es fecha, como raya', () => {
    expect(fechaHora('2026-11-16T08:05:00')).toMatch(/^16\/11\/2026,? 08:05$/)
    expect(fechaHora(null)).toBe('—')
    expect(fechaHora('basura')).toBe('—')
  })

  it('las fechas de documento van como dd/mm/aaaa', () => {
    expect(fechaDocumento('2026-11-25')).toBe('25/11/2026')
    expect(fechaDocumento(null)).toBe('—')
  })
})

/**
 * Reglas de CSS que jsdom no aplica, comprobadas leyendo las hojas tal
 * cual: sirven para que un cambio de capas o de punto de corte no pase
 * inadvertido. No sustituyen mirarlo en un navegador.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const leer = (nombre) => readFileSync(`src/estilos/${nombre}.css`, 'utf8')
const base = leer('base')
const armazon = leer('armazon')
const comun = leer('comun')

/** Cuerpo de la primera regla cuyo selector es exactamente `selector`. */
function regla(css, selector) {
  const escapado = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const m = css.match(new RegExp(`(?:^|\\})\\s*${escapado}\\s*\\{([^}]*)\\}`))
  if (!m) throw new Error(`Falta la regla ${selector}`)
  return m[1]
}

describe('capas', () => {
  it('el velo de un diálogo queda por encima de la barra y el menú del armazón', () => {
    const velo = Number(regla(base, '.velo').match(/z-index:\s*(\d+)/)[1])
    const zArmazon = [...armazon.matchAll(/z-index:\s*(\d+)/g)].map((m) => Number(m[1]))
    expect(zArmazon.length).toBeGreaterThan(0)
    for (const z of zArmazon) expect(velo).toBeGreaterThan(z)
  })
})

describe('tabla de tarjetas', () => {
  it('la celda principal no hereda el th global: lleva color y tamaño propios', () => {
    const cuerpo = regla(comun, '.tabla-tarjetas tbody th')
    expect(cuerpo).toMatch(/color:\s*var\(--tinta\)/)
    expect(cuerpo).toMatch(/font-size:\s*var\(--t-cuerpo\)/)
  })

  it('pasa a tarjetas en el mismo punto de corte que el armazón', () => {
    const corte = (css) => css.match(/@media \(max-width:\s*([\d.]+rem)\)/)[1]
    expect(corte(comun)).toBe(corte(armazon))
  })
})

describe('menú móvil', () => {
  it('la altura de la barra es una variable que usan la barra y el menú', () => {
    expect(armazon).toMatch(/--alto-barra-movil:\s*[\d.]+rem/)
    expect((armazon.match(/var\(--alto-barra-movil\)/g) ?? []).length).toBeGreaterThanOrEqual(2)
  })
})

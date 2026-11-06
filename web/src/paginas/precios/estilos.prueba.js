/**
 * La hoja de precios queda cargada en toda la aplicación una vez que se visita la
 * sección: cada regla tiene que colgar de su clase para no cambiar otras pantallas.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const css = readFileSync('src/estilos/precios.css', 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')
const selectores = [...css.matchAll(/^([^\s@}{][^{]*)\{/gm)].map((m) => m[1].trim())

describe('estilos/precios.css', () => {
  it('declara reglas y todas cuelgan de .seccion-precios', () => {
    expect(selectores.length).toBeGreaterThan(0)
    for (const s of selectores) {
      for (const parte of s.split(',').map((p) => p.trim())) {
        expect(parte, `regla global: ${parte}`).toMatch(/^\.seccion-precios\b/)
      }
    }
  })

  it('no escribe colores sueltos', () => {
    expect(css).not.toMatch(/#[0-9a-fA-F]{3,8}\b/)
    expect(css).not.toMatch(/\brgba?\(/)
  })
})

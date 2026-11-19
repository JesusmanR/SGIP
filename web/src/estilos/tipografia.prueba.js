/**
 * Las fuentes viajan con la aplicación (ADR-0032): sin Google Fonts, porque
 * la tienda puede estar sin internet y una petición externa filtra visitas.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const base = readFileSync('src/estilos/base.css', 'utf8')
const main = readFileSync('src/main.jsx', 'utf8')
const tokens = readFileSync('src/estilos/tokens.css', 'utf8')
const html = readFileSync('index.html', 'utf8')
const todo = base + main + tokens + html

describe('tipografía', () => {
  it('DM Sans y Manrope salen de paquetes locales', () => {
    expect(main).toMatch(/@fontsource\/dm-sans\//)
    expect(main).toMatch(/@fontsource\/manrope\//)
  })
  it('no se pide nada a servidores externos de fuentes', () => {
    expect(todo).not.toMatch(/fonts\.(googleapis|gstatic)\.com/)
  })
  it('el texto usa DM Sans y los títulos Manrope, desde tokens', () => {
    expect(tokens).toMatch(/--familia:\s*"DM Sans"/)
    expect(tokens).toMatch(/--familia-titulo:\s*"Manrope"/)
    expect(base).toMatch(/font-family:\s*var\(--familia\)/)
  })
})

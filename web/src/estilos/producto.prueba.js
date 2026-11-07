/** Categorías en celular: el renombrado no debe desplazar la tabla de lado. */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const css = readFileSync('src/estilos/producto.css', 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')

describe('producto.css · categorías en celular', () => {
  it('la fila en modo renombrar se envuelve en vez de desplazar la tabla', () => {
    expect(css).toMatch(/@media[^{]*\{[\s\S]*\.panel-categorias\s+tr\s*\{[^}]*flex-wrap:\s*wrap/)
    expect(css).toMatch(/\.panel-categorias\s+td\.acciones\s*\{[^}]*white-space:\s*normal/)
    expect(css).toMatch(/\.panel-categorias\s+input[^{]*\{[^}]*min-width:\s*0/)
  })
})

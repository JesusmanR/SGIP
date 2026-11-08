/**
 * Un selector a medio escribir no falla: se pega con la regla siguiente y la
 * deja sin efecto. `.plataforma-empresas` suelto delante de
 * `.plataforma-empresas td .boton` produce un selector que no encaja con nada,
 * y el botón pierde su estilo sin que ninguna otra prueba lo note.
 */
import { readdirSync, readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const sinComentarios = (css) => css.replace(/\/\*[\s\S]*?\*\//g, '')

/** Los selectores de cada regla: lo que va antes de cada `{`, partido por comas. */
function selectoresDe(css) {
  return [...sinComentarios(css).matchAll(/([^{}]+)\{/g)]
    .flatMap((m) => m[1].split(','))
    .map((s) => s.replace(/\s+/g, ' ').trim())
    .filter((s) => s && !s.startsWith('@'))
}

const hojas = readdirSync('src/estilos').filter((n) => n.endsWith('.css'))

describe('selectores de las hojas de estilo', () => {
  it.each(hojas)('%s no repite la misma clase como ancestro de sí misma', (hoja) => {
    const css = readFileSync(`src/estilos/${hoja}`, 'utf8')
    const repetidos = selectoresDe(css).filter((s) => /(\.[\w-]+) (?:.* )?\1(?![\w-])/.test(s))
    expect(repetidos).toEqual([])
  })
})

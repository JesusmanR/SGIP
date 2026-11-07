/**
 * `base.css` es lo que usan dos o más pantallas. Los estilos de una sola
 * pantalla viven en su hoja (`estilos/<pantalla>.css`), importada por ella:
 * así quien rediseña una pantalla no pisa a otra ni toca lo común.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const leer = (nombre) => readFileSync(`src/estilos/${nombre}.css`, 'utf8')
const sinComentarios = (css) => css.replace(/\/\*[\s\S]*?\*\//g, '')
const base = sinComentarios(leer('base'))

const hojas = ['acceso', 'catalogo', 'producto', 'importacion', 'panel', 'empresa']

/** Los selectores de nivel superior y los de dentro de @media, sin duplicados. */
function selectores(css) {
  return [...sinComentarios(css).matchAll(/(?:^|[}\n])\s*([^@\s{}][^{}]*)\{/g)]
    .flatMap((m) => m[1].split(',').map((s) => s.trim().replace(/\s+/g, ' ')))
}

describe.each(hojas)('estilos/%s.css', (nombre) => {
  const propios = selectores(leer(nombre))

  it('declara reglas', () => {
    expect(propios.length).toBeGreaterThan(0)
  })

  it('ningún selector suyo se repite en base.css', () => {
    const deBase = new Set(selectores(base))
    for (const s of propios) expect(deBase.has(s), `${s} está también en base.css`).toBe(false)
  })
})

describe('base.css no vuelve a alojar clases de una sola pantalla', () => {
  it.each([
    '.acceso', '.col-imagen', '.titulo-fila', '.fila-logo', '.velo', '.galeria',
    '.pasos', '.zona-archivo', '.panel-rejilla', '.grafico-lienzo', '.hueso',
  ])('%s', (clase) => {
    const escapada = clase.replace('.', String.raw`\.`)
    expect(base).not.toMatch(new RegExp(String.raw`${escapada}(?![\w-])`))
  })
})

describe('orden de las hojas', () => {
  it('main.jsx carga base.css antes que la aplicación, para que las hojas de pantalla queden después', () => {
    const main = readFileSync('src/main.jsx', 'utf8')
    expect(main.indexOf("./estilos/base.css")).toBeGreaterThan(-1)
    expect(main.indexOf("./estilos/base.css")).toBeLessThan(main.indexOf("./App.jsx"))
  })
})

/**
 * `base.css` es lo que usan dos o más pantallas. Los estilos de una sola
 * pantalla viven en su hoja (`estilos/<pantalla>.css`), importada por ella:
 * así quien rediseña una pantalla no pisa a otra ni toca lo común.
 */
import { readdirSync, readFileSync } from 'node:fs'
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

describe('mensaje de ayuda de campo', () => {
  it('base.css da el color de la ayuda y las hojas de sección no lo repiten', () => {
    expect(base).toMatch(/\.campo-mensaje\.ayuda\s*\{[^}]*color:\s*var\(--tinta-3\)/)
    for (const hoja of ['compras', 'proveedores', 'impuestos', 'inventario', 'plataforma-empresas',
      'plataforma-tributario', 'precios', 'usuarios']) {
      expect(sinComentarios(leer(hoja)), hoja).not.toMatch(/\.campo-mensaje(\.error|\.ayuda)?\s*\{/)
    }
  })
})

describe('una hoja de pantalla solo se usa donde se importa', () => {
  const fuentes = []
  const recorrer = (dir) => {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const ruta = `${dir}/${e.name}`
      if (e.isDirectory()) recorrer(ruta)
      else if (/\.jsx$/.test(e.name) && !/prueba/.test(e.name)) fuentes.push({ ruta, texto: readFileSync(ruta, 'utf8') })
    }
  }
  recorrer('src')

  // Palabras que además de clase son texto corriente o clases de otra hoja con el mismo nombre.
  const homonimos = new Set(['actual', 'codigo']) // prosa y claves de datos que se llaman igual

  // Lo que base.css, comun.css o armazon.css ya definen es común: una hoja de
  // pantalla solo lo retoca (`.acceso .tarjeta`) y no es suyo.
  const compartidas = new Set(['base', 'comun', 'armazon']
    .flatMap((h) => [...sinComentarios(leer(h)).matchAll(/\.([a-z][\w-]*)/g)].map((m) => m[1])))

  it.each(hojas)('las clases de %s no aparecen en archivos que no la importan', (nombre) => {
    const clases = new Set([...sinComentarios(leer(nombre)).matchAll(/\.([a-z][\w-]*)/g)].map((m) => m[1]))
    const importa = new RegExp(`estilos/${nombre}\.css`)
    const usos = []
    for (const { ruta, texto } of fuentes) {
      if (importa.test(texto)) continue
      const literales = [...texto.matchAll(/(['"`])((?:(?!\1).)*)\1/g)].map((m) => m[2])
      for (const c of clases) {
        if (homonimos.has(c)) continue
        if (compartidas.has(c)) continue
        if (literales.some((l) => !l.includes('/') && l.split(/\s+/).includes(c))) usos.push(`${ruta} usa .${c}`)
      }
    }
    expect(usos).toEqual([])
  })
})

/**
 * Las hojas de sección (`estilos/<seccion>.css`) cargan en toda la aplicación:
 * Vite las junta en una sola. Para que una sección no pise a otra, una clase
 * que solo ella define no se usa en archivos de otra carpeta de páginas, y lo
 * que se repetía en cada sección vive una sola vez en comun.css.
 */
import { readdirSync, readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const leer = (nombre) => readFileSync(`src/estilos/${nombre}.css`, 'utf8')
const sinComentarios = (css) => css.replace(/\/\*[\s\S]*?\*\//g, '')

const clasesDe = (css) => new Set([...sinComentarios(css).matchAll(/\.([a-z][\w-]*)/g)].map((m) => m[1]))

const fuentes = []
const recorrer = (dir) => {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const ruta = `${dir}/${e.name}`
    if (e.isDirectory()) recorrer(ruta)
    else if (/\.jsx$/.test(e.name) && !/prueba/.test(e.name)) fuentes.push({ ruta, texto: readFileSync(ruta, 'utf8') })
  }
}
recorrer('src')

// Lo que base.css, comun.css o armazon.css definen es común: una hoja de sección solo lo retoca.
const compartidas = new Set(['base', 'comun', 'armazon'].flatMap((h) => [...clasesDe(leer(h))]))

/** Cada cadena entre comillas del archivo, también las de dentro de una plantilla. */
function literalesDe(texto) {
  return [
    ...texto.matchAll(/(['"`])((?:(?!\1).)*)\1/g),
    ...texto.matchAll(/(['"])([^'"\n]*)\1/g),
  ].map((m) => m[2])
}

// Palabras que además de clase son texto corriente o claves de datos («sí», «no», «cambio»).
const homonimos = new Set(['si', 'no', 'cambio', 'alcance'])

const SECCIONES = [
  ['compras', 'seccion-compras', 'compras'],
  ['impuestos', 'seccion-impuestos', 'impuestos'],
  ['inventario', 'inventario', 'inventario'],
  ['plataforma-empresas', 'plataforma-empresas', 'plataforma-empresas'],
  ['plataforma-tributario', 'plataforma-tributario', 'plataforma-tributario'],
  ['precios', 'seccion-precios', 'precios'],
  ['proveedores', 'seccion-proveedores', 'proveedores'],
  ['usuarios', 'usuarios', 'usuarios'],
]

// Una clase que dos secciones definen cada una bajo su raíz (`.ficha`) es de cada una; solo se
// vigila la que define una sola hoja, porque ahí un uso en otra carpeta es una fuga.
const definidaEn = new Map()
for (const [nombre, raiz] of SECCIONES) {
  for (const c of clasesDe(leer(nombre))) {
    if (c !== raiz) definidaEn.set(c, [...(definidaEn.get(c) ?? []), nombre])
  }
}

describe.each(SECCIONES)('estilos/%s.css', (nombre, raiz, carpeta) => {
  it('sus clases no se usan en archivos de otra carpeta de páginas', () => {
    const propias = [...clasesDe(leer(nombre))]
      .filter((c) => c !== raiz && !homonimos.has(c) && !compartidas.has(c) && definidaEn.get(c)?.length === 1)
    const usos = []
    for (const { ruta, texto } of fuentes) {
      if (ruta.startsWith(`src/paginas/${carpeta}/`)) continue
      const literales = literalesDe(texto)
      for (const c of propias) {
        if (literales.some((l) => !l.includes('/') && l.split(/\s+/).includes(c))) usos.push(`${ruta} usa .${c}`)
      }
    }
    expect(usos).toEqual([])
  })
})

describe('clases que se repetían en cada sección', () => {
  const comunes = ['nota', 'volver', 'enlace-boton', 'aviso-foco']

  it.each(comunes)('.%s se define una vez, en comun.css; una sección solo ajusta sus márgenes', (clase) => {
    expect(clasesDe(leer('comun')).has(clase)).toBe(true)
    const termina = new RegExp(String.raw`\.${clase}(?![\w-])\s*(?::[\w-]+)?$`)
    const repetidas = []
    for (const [nombre] of SECCIONES) {
      for (const [, selector, cuerpo] of sinComentarios(leer(nombre)).matchAll(/([^{}]+)\{([^}]*)\}/g)) {
        if (!selector.split(',').some((x) => termina.test(x.trim()))) continue
        const propiedades = cuerpo.split(';').map((d) => d.split(':')[0].trim()).filter(Boolean)
        if (propiedades.some((p) => !p.startsWith('margin'))) repetidas.push(`${nombre}: ${selector.trim()}`)
      }
    }
    expect(repetidas).toEqual([])
  })
})

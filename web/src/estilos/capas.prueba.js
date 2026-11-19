/**
 * Reglas de CSS que jsdom no aplica, comprobadas leyendo las hojas tal
 * cual: sirven para que un cambio de capas o de punto de corte no pase
 * inadvertido. No sustituyen mirarlo en un navegador.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const leer = (nombre) => readFileSync(`src/estilos/${nombre}.css`, 'utf8')
const producto = leer('producto')
const armazon = leer('armazon')
const comun = leer('comun')

/** Cuerpo de la primera regla cuyo selector es exactamente `selector`. */
function regla(css, selector) {
  const escapado = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const m = css.match(new RegExp(`(?<![\\w.#-])${escapado}\\s*\\{([^}]*)\\}`))
  if (!m) throw new Error(`Falta la regla ${selector}`)
  return m[1]
}

describe('capas', () => {
  it('el velo de un diálogo queda por encima de la barra y el menú del armazón', () => {
    const velo = Number(regla(producto, '.velo').match(/z-index:\s*(\d+)/)[1])
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
  it('la barra tiene una altura mínima en variable y el cajón queda por encima de la barra', () => {
    expect(armazon).toMatch(/--alto-barra-movil:\s*[\d.]+(rem|px)/)
    expect((armazon.match(/var\(--alto-barra-movil\)/g) ?? []).length).toBeGreaterThanOrEqual(1)
    const barra = Number(regla(armazon, '.armazon-barra').match(/z-index:\s*(\d+)/)[1])
    const velo = Number(armazon.match(/\.armazon-velo\s*\{[^}]*z-index:\s*(\d+)/)[1])
    expect(velo).toBeGreaterThan(barra)
    const cajon = Number(armazon.match(/\.menu-lateral\s*\{[^}]*z-index:\s*(\d+)/)[1])
    expect(cajon).toBeGreaterThan(velo)
  })

  // --alto-barra-movil se usa una sola vez (la barra del celular): el cajón ya
  // no cuelga de ella porque es de altura completa, así que no hay un segundo uso.
  it('el marco deja sitio a la barra fija al enfocar (WCAG 2.2, foco no oculto)', () => {
    expect(armazon).toMatch(/html\s*\{[^}]*scroll-padding-top:\s*var\(--alto-barra\)/)
    const movil = armazon.slice(armazon.indexOf('@media (max-width'))
    expect(movil).toMatch(/--alto-barra:\s*var\(--alto-barra-movil\)/)
  })

  it('la altura medida solo mueve el margen de desplazamiento, nunca la altura mínima de la barra', () => {
    expect(armazon).toMatch(/scroll-padding-top:\s*var\(--alto-barra-real,\s*var\(--alto-barra\)\)/)
    expect(regla(armazon, '.armazon-barra')).not.toMatch(/--alto-barra-real/)
    expect(regla(armazon, '.armazon-barra')).toMatch(/min-height:\s*var\(--alto-barra\)/)
  })

  it('el cajón del celular usa la altura dinámica, con 100vh de reserva', () => {
    const movil = armazon.slice(armazon.indexOf('@media (max-width'))
    expect(movil).toMatch(/height:\s*100vh;\s*height:\s*100dvh/)
  })

  it('los títulos largos de la miga se recortan: overflow en un bloque con min-width 0', () => {
    expect(regla(armazon, '.migas [aria-current="page"]')).toMatch(/display:\s*block/)
    expect(regla(armazon, '.migas [aria-current="page"]')).toMatch(/text-overflow:\s*ellipsis/)
  })

  it('en el celular el contador gana en especificidad al margen de escritorio', () => {
    const movil = armazon.slice(armazon.indexOf('@media (max-width'))
    expect(movil).toMatch(/\.armazon-barra \.contador-prueba\s*\{[^}]*margin-left:\s*0/)
  })
})

describe('tabla sobre tarjeta', () => {
  it('en escritorio la caja es una tarjeta con borde que desplaza por dentro', () => {
    const caja = regla(comun, '.tabla-tarjetas-caja')
    expect(caja).toMatch(/background:\s*var\(--tarjeta\)/)
    expect(caja).toMatch(/border:\s*1px solid var\(--borde\)/)
    expect(caja).toMatch(/overflow-x:\s*auto/)
  })

  it('en celular la caja desaparece y deja a cada fila ser su tarjeta', () => {
    const movil = comun.slice(comun.indexOf('@media (max-width'))
    expect(movil).toMatch(/\.tabla-tarjetas-caja\s*\{[^}]*overflow:\s*visible/)
    expect(movil).toMatch(/\.tabla-tarjetas-caja\s*\{[^}]*border:\s*0/)
  })

  it('en celular todo el contenido de la celda va a la derecha como un bloque', () => {
    const movil = comun.slice(comun.indexOf('@media (max-width'))
    expect(movil).toMatch(/\.tabla-tarjetas td,[^{]*\{[^}]*display:\s*grid/)
    expect(movil).toMatch(/\.tabla-tarjetas td > \*[^{]*\{[^}]*grid-column:\s*2/)
    expect(movil).not.toMatch(/margin-left:\s*auto/)
  })
})

/**
 * Contraste de los tokens de color (RNF-13) y color del acento (ADR-0026).
 *
 * Se lee tokens.css tal cual, sin cargarlo en un navegador: así la prueba
 * falla en cuanto alguien cambia un valor, sin depender de cómo se pinte.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const css = readFileSync('src/estilos/tokens.css', 'utf8')

function token(nombre) {
  const m = css.match(new RegExp(`--${nombre}:\\s*(#[0-9a-fA-F]{6})\\b`))
  if (!m) throw new Error(`Falta el token --${nombre} con valor hexadecimal`)
  return m[1].toUpperCase()
}

function luminancia(hex) {
  const [r, g, b] = [1, 3, 5]
    .map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4))
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

function contraste(a, b) {
  const [l1, l2] = [luminancia(a), luminancia(b)].sort((x, y) => y - x)
  return (l1 + 0.05) / (l2 + 0.05)
}

const texto = [
  ['sobre-acento', 'acento'],
  ['sobre-acento', 'chrome'],
  ['sobre-acento', 'peligro'],
  ['acento', 'tarjeta'], ['acento', 'lienzo'],
  ['acento', 'acento-sutil'], // .zona-archivo al pasar el cursor o arrastrar
  ['acento', 'aviso-fondo'], // enlaces dentro de un aviso del panel
  ['menu-texto', 'menu-fondo'], ['menu-texto', 'menu-hover'], // enlace del menú lateral blanco
  ['menu-rotulo', 'menu-fondo'], // rótulo de grupo: el gris del diseño (#AAA8B3) no llegaba
  ['menu-activo-texto', 'menu-activo'], // enlace activo del menú lateral
  ['tinta-3', 'menu-fondo'], // migas y rol del perfil sobre la barra blanca
  ['acento', 'menu-activo'],
  ['tinta', 'tarjeta'], ['tinta', 'lienzo'],
  ['tinta-2', 'tarjeta'], ['tinta-2', 'lienzo'],
  ['tinta-3', 'tarjeta'], ['tinta-3', 'lienzo'],
  ['exito', 'exito-fondo'], ['aviso', 'aviso-fondo'],
  ['peligro', 'peligro-fondo'], ['info', 'info-fondo'],
  ['neutro', 'neutro-fondo'], // estado que no es éxito, aviso ni peligro: borrador, anulada
  ['tinta-3', 'exito-fondo'], ['tinta-3', 'aviso-fondo'],
  ['tinta-3', 'peligro-fondo'], ['tinta-3', 'info-fondo'], ['tinta-3', 'borde'],
]

describe('tokens del marco', () => {
  it('el marco ya no usa el menú oscuro', () => {
    expect(css).not.toMatch(/--chrome-activo:/)
  })
  it.each(['menu-fondo', 'menu-hover', 'menu-activo', 'menu-activo-texto', 'menu-texto', 'menu-rotulo', 'borde-suave'])(
    'existe --%s', (n) => { expect(token(n)).toMatch(/^#/) },
  )
})

describe('tokens de color', () => {
  it('el acento es el morado del producto', () => {
    expect(token('acento')).toBe('#6C54DF')
  })

  it.each(texto)('%s sobre %s llega a 4,5:1', (frente, fondo) => {
    expect(contraste(token(frente), token(fondo))).toBeGreaterThanOrEqual(4.5)
  })

  it.each(['tarjeta', 'lienzo'])('el borde de un control llega a 3:1 sobre %s', (fondo) => {
    expect(contraste(token('borde-control'), token(fondo))).toBeGreaterThanOrEqual(3)
  })
})

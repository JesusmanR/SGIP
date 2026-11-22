import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { imprimirRecibo } from './imprimir.js'

function montarRecibo() {
  document.body.innerHTML = '<div id="root"><div class="recibo"><p>Black Steel Parts</p></div></div>'
}

describe('imprimirRecibo', () => {
  beforeEach(() => { montarRecibo(); window.print = vi.fn() })
  afterEach(() => { document.body.innerHTML = ''; document.head.querySelectorAll('style').forEach((s) => s.remove()) })

  it('fija una página de 80 mm de ancho y de alto igual al recibo (CSS válido, sin "auto")', () => {
    imprimirRecibo()
    const css = [...document.head.querySelectorAll('style[data-impresion-recibo]')].map((s) => s.textContent).join('')
    expect(css).toMatch(/@page\s*\{\s*size:\s*80mm\s+\d+(\.\d+)?mm/)
    expect(css).not.toMatch(/auto/)
  })

  it('llama a window.print una sola vez, con una copia del recibo lista para imprimir', () => {
    imprimirRecibo()
    expect(window.print).toHaveBeenCalledTimes(1)
    expect(document.querySelectorAll('.recibo-impresion .recibo')).toHaveLength(1)
  })

  it('quita el estilo y la copia al terminar de imprimir', () => {
    imprimirRecibo()
    window.dispatchEvent(new Event('afterprint'))
    expect(document.head.querySelector('style[data-impresion-recibo]')).toBeNull()
    expect(document.querySelector('.recibo-impresion')).toBeNull()
  })

  it('si el navegador nunca avisa del fin, lo quita por el plazo de seguridad', () => {
    vi.useFakeTimers()
    imprimirRecibo()
    vi.advanceTimersByTime(60_000)
    expect(document.head.querySelector('style[data-impresion-recibo]')).toBeNull()
    vi.useRealTimers()
  })

  it('si no hay recibo en pantalla no imprime nada', () => {
    document.body.innerHTML = ''
    imprimirRecibo()
    expect(window.print).not.toHaveBeenCalled()
  })

  it('si print lanza un error, limpia igual', () => {
    window.print = vi.fn(() => { throw new Error('x') })
    expect(() => imprimirRecibo()).toThrow()
    expect(document.head.querySelector('style[data-impresion-recibo]')).toBeNull()
  })
})

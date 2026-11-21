/**
 * Imprime solo el recibo, como una tirilla continua de 80 mm.
 *
 * `@page { size: 80mm auto }` no es CSS válido (una longitud no se mezcla con
 * `auto`) y Chrome lo descarta y cae a Carta. Por eso se mide el recibo en
 * milímetros, a 80 mm de ancho, y se fija esa altura exacta en un `@page`
 * temporal. El recibo se copia a un contenedor propio (`.recibo-impresion`):
 * al imprimir, `caja.css` oculta con `display: none` todo lo demás, así que
 * no sobran páginas en blanco.
 */
const ANCHO_MM = 80
const PX_POR_MM = 96 / 25.4
const ALTO_MINIMO_MM = 20
const PLAZO_DE_LIMPIEZA_MS = 60_000

export function imprimirRecibo() {
  const original = document.querySelector('.recibo')
  if (!original) return

  // Una impresión anterior que no avisó de su fin no debe dejar copias viejas.
  document.querySelectorAll('.recibo-impresion, style[data-impresion-recibo]').forEach((n) => n.remove())

  const contenedor = document.createElement('div')
  contenedor.className = 'recibo-impresion'
  contenedor.setAttribute('aria-hidden', 'true')
  contenedor.appendChild(original.cloneNode(true))
  document.body.appendChild(contenedor)

  // +1 mm de holgura: un redondeo hacia abajo cortaría la última línea.
  const alto = Math.max(ALTO_MINIMO_MM, Math.ceil(contenedor.offsetHeight / PX_POR_MM) + 1)
  const estilo = document.createElement('style')
  estilo.setAttribute('data-impresion-recibo', '')
  estilo.textContent = `@page { size: ${ANCHO_MM}mm ${alto}mm; margin: 0; }`
  document.head.appendChild(estilo)

  let plazo
  const limpiar = () => {
    clearTimeout(plazo)
    window.removeEventListener('afterprint', limpiar)
    estilo.remove()
    contenedor.remove()
  }
  window.addEventListener('afterprint', limpiar)
  plazo = setTimeout(limpiar, PLAZO_DE_LIMPIEZA_MS)
  try {
    window.print()
  } catch (error) {
    limpiar()
    throw error
  }
}

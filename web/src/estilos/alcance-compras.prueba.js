/**
 * Las hojas de compras y proveedores se cargan una vez y quedan en toda la
 * aplicación: sus reglas genéricas tienen que colgar de la clase de su sección
 * para no cambiar el aspecto de otras pantallas.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const hojas = ['compras', 'proveedores']

describe.each(hojas)('estilos/%s.css', (nombre) => {
  const css = readFileSync(`src/estilos/${nombre}.css`, 'utf8')
  const selectores = [...css.matchAll(/^([^\s@/}{][^{]*)\{/gm)].map((m) => m[1].trim())

  it('declara reglas y ninguna es global sin la clase de su sección', () => {
    expect(selectores.length).toBeGreaterThan(0)
    for (const s of selectores) {
      for (const parte of s.split(',').map((p) => p.trim())) {
        const esGenerica = /^(a\.boton|\.boton|\.ficha|\.subtitulo-seccion|\.formulario-angosto|\.total-vista)\b/.test(parte)
        expect(esGenerica, `regla global: ${parte}`).toBe(false)
      }
    }
  })

  it('a.boton y .ficha cuelgan de la clase de la sección', () => {
    expect(css).toMatch(new RegExp(`\\.seccion-${nombre} a\\.boton`))
    expect(css).toMatch(new RegExp(`\\.seccion-${nombre} \\.ficha`))
  })
})

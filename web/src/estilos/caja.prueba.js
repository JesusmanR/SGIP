import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const css = readFileSync('src/estilos/caja.css', 'utf8')
const regla = (selector) => new RegExp(`${selector.replace(/[.:()[\]]/g, '\\$&')}\\s*\\{([^}]*)\\}`).exec(css)?.[1]

describe('estilos de la caja', () => {
  it('el foco de las pestañas se ve: contorno con el acento (más de 3:1), no con el halo claro', () => {
    const foco = regla('.caja-pestana:focus-visible')
    expect(foco).toMatch(/outline:[^;]*var\(--acento\)/)
    expect(foco).not.toMatch(/acento-sutil/)
  })
})

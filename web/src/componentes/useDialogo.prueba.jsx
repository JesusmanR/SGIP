/**
 * El teclado de un diálogo modal: foco, Escape y Tab contenido.
 *
 * Lo que se protege es que el foco no se escape: la lista de elementos
 * enfocables no puede incluir lo que el navegador no enfoca (hidden,
 * desactivado, no visible), porque entonces el «último» es uno al que
 * Tab nunca llega y la vuelta no se da.
 */
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import Galeria from './Galeria.jsx'
import useDialogo from './useDialogo.js'
import { api } from '../servicios/api.js'

vi.mock('../servicios/api.js', () => ({
  api: { imagenesDe: vi.fn(), hacerPrincipal: vi.fn(), borrarImagen: vi.fn() },
  imagenAutenticada: vi.fn(async () => 'blob:simulada'),
}))
vi.mock('../servicios/subidas.js', () => ({ MAXIMO_POR_PRODUCTO: 5, subirImagenes: vi.fn() }))

function Prueba({ onCerrar }) {
  const d = useDialogo(onCerrar)
  return (
    <>
      <button>fuera antes</button>
      <div role="dialog" aria-label="d" tabIndex={-1} ref={d.ref} onKeyDown={d.onKeyDown}>
        <button>primero</button>
        <input type="file" hidden />
        <input type="hidden" defaultValue="x" />
        <button disabled>desactivado</button>
        <div hidden><button>en bloque oculto</button></div>
        <button style={{ display: 'none' }}>sin mostrar</button>
        <button>ultimo</button>
        <input type="file" hidden />
      </div>
      <button>fuera después</button>
    </>
  )
}

describe('useDialogo', () => {
  afterEach(() => vi.clearAllMocks())

  it('el foco entra en el diálogo y vuelve a lo que lo abrió al cerrarse', () => {
    const origen = document.createElement('button')
    document.body.appendChild(origen)
    origen.focus()
    const { unmount } = render(<Prueba onCerrar={() => {}} />)
    expect(screen.getByRole('dialog')).toHaveFocus()
    unmount()
    expect(origen).toHaveFocus()
    origen.remove()
  })

  it('Escape llama a onCerrar', async () => {
    const onCerrar = vi.fn()
    render(<Prueba onCerrar={onCerrar} />)
    await userEvent.keyboard('{Escape}')
    expect(onCerrar).toHaveBeenCalledTimes(1)
  })

  it('Tab da la vuelta saltando lo oculto, desactivado o invisible', async () => {
    render(<Prueba onCerrar={() => {}} />)
    const visitados = []
    for (let i = 0; i < 6; i += 1) {
      await userEvent.tab()
      visitados.push(document.activeElement.textContent)
    }
    expect(visitados).toEqual(['primero', 'ultimo', 'primero', 'ultimo', 'primero', 'ultimo'])
  })

  it('Mayús+Tab desde el primero va al último, sin salir', async () => {
    render(<Prueba onCerrar={() => {}} />)
    await userEvent.tab()
    expect(document.activeElement.textContent).toBe('primero')
    await userEvent.tab({ shift: true })
    expect(document.activeElement.textContent).toBe('ultimo')
  })

  it('el foco no se escapa a lo enfocable de fuera, ni hacia delante ni hacia atrás', async () => {
    render(<Prueba onCerrar={() => {}} />)
    const dialogo = screen.getByRole('dialog')
    for (let i = 0; i < 8; i += 1) {
      await userEvent.tab()
      expect(dialogo).toContainElement(document.activeElement)
    }
    for (let i = 0; i < 8; i += 1) {
      await userEvent.tab({ shift: true })
      expect(dialogo).toContainElement(document.activeElement)
    }
  })

  describe('con la galería sin imágenes (su input de archivo va hidden)', () => {
    it('Tab da la vuelta dentro y no sale a lo de fuera', async () => {
      api.imagenesDe.mockResolvedValue([])
      render(
        <>
          <button>fuera antes</button>
          <Galeria productoId={1} nombre="P" puedeEditar onCerrar={() => {}} onCambio={() => {}} />
          <button>fuera después</button>
        </>)
      await screen.findByText(/todavía no tiene imágenes/i)
      const dialogo = screen.getByRole('dialog')
      for (let i = 0; i < 6; i += 1) {
        await userEvent.tab()
        expect(dialogo).toContainElement(document.activeElement)
      }
      for (let i = 0; i < 6; i += 1) {
        await userEvent.tab({ shift: true })
        expect(dialogo).toContainElement(document.activeElement)
      }
    })
  })
})

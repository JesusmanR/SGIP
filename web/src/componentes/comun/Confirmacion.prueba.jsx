import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { StrictMode, useRef, useState } from 'react'
import { describe, expect, it, vi } from 'vitest'

import Confirmacion from './Confirmacion.jsx'

/** Un botón que abre la confirmación y la deja en su sitio, como en la ficha de usuario. */
function Anfitrion() {
  const [abierta, setAbierta] = useState(false)
  const estable = useRef(null)
  return (
    <div>
      <div ref={estable} tabIndex={-1} aria-label="Sección estable" role="region">
        <button type="button" onClick={() => setAbierta(true)}>Abrir</button>
      </div>
      {abierta && (
        <Confirmacion
          pregunta="¿Desactivar la cuenta?"
          detalle="No se borra nada."
          textoConfirmar="Sí, desactivar"
          focoEstable={estable}
          alConfirmar={() => setAbierta(false)}
          alCancelar={() => setAbierta(false)}
        />
      )}
    </div>
  )
}

describe('Confirmacion', () => {
  it('dice qué pasa y ofrece confirmar y cancelar', async () => {
    render(<Anfitrion />)
    await userEvent.click(screen.getByRole('button', { name: 'Abrir' }))
    const caja = screen.getByRole('group', { name: '¿Desactivar la cuenta?' })
    expect(caja).toHaveTextContent('No se borra nada.')
    expect(screen.getByRole('button', { name: 'Sí, desactivar' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Cancelar' })).toBeEnabled()
  })

  it('al abrirse lleva el foco a la caja', async () => {
    render(<Anfitrion />)
    await userEvent.click(screen.getByRole('button', { name: 'Abrir' }))
    expect(screen.getByRole('group', { name: '¿Desactivar la cuenta?' })).toHaveFocus()
  })

  it('deshabilita los dos botones mientras envía', () => {
    render(
      <Confirmacion pregunta="¿Seguro?" textoConfirmar="Sí" enviando alConfirmar={() => {}} alCancelar={() => {}} />,
    )
    expect(screen.getByRole('button', { name: 'Sí' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Cancelar' })).toBeDisabled()
  })

  it('confirmar y cancelar llaman a lo suyo', async () => {
    const alConfirmar = vi.fn()
    const alCancelar = vi.fn()
    render(<Confirmacion pregunta="¿Seguro?" textoConfirmar="Sí" alConfirmar={alConfirmar} alCancelar={alCancelar} />)
    await userEvent.click(screen.getByRole('button', { name: 'Sí' }))
    expect(alConfirmar).toHaveBeenCalledTimes(1)
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(alCancelar).toHaveBeenCalledTimes(1)
  })

  it('el texto del botón de cancelar se puede cambiar', () => {
    render(
      <Confirmacion
        pregunta="¿Guardar?" textoConfirmar="Sí" textoCancelar="Volver a editar"
        alConfirmar={() => {}} alCancelar={() => {}}
      />,
    )
    expect(screen.getByRole('button', { name: 'Volver a editar' })).toBeInTheDocument()
  })

  it('Escape cancela, también desde un campo de dentro', async () => {
    const alCancelar = vi.fn()
    render(
      <Confirmacion pregunta="¿Seguro?" textoConfirmar="Sí" alConfirmar={() => {}} alCancelar={alCancelar}>
        <input aria-label="Motivo" />
      </Confirmacion>,
    )
    await userEvent.click(screen.getByLabelText('Motivo'))
    await userEvent.keyboard('{Escape}')
    expect(alCancelar).toHaveBeenCalledTimes(1)
  })

  it('Escape no cancela mientras envía', async () => {
    const alCancelar = vi.fn()
    render(<Confirmacion pregunta="¿Seguro?" textoConfirmar="Sí" enviando alConfirmar={() => {}} alCancelar={alCancelar} />)
    screen.getByRole('group').focus()
    await userEvent.keyboard('{Escape}')
    expect(alCancelar).not.toHaveBeenCalled()
  })

  it('al cancelar devuelve el foco al botón que la abrió', async () => {
    render(<Anfitrion />)
    await userEvent.click(screen.getByRole('button', { name: 'Abrir' }))
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(screen.getByRole('button', { name: 'Abrir' })).toHaveFocus()
  })

  it('al confirmar devuelve el foco al botón que la abrió', async () => {
    render(<Anfitrion />)
    await userEvent.click(screen.getByRole('button', { name: 'Abrir' }))
    await userEvent.click(screen.getByRole('button', { name: 'Sí, desactivar' }))
    expect(screen.getByRole('button', { name: 'Abrir' })).toHaveFocus()
  })

  it('al cerrar con Escape el foco también vuelve al botón', async () => {
    render(<Anfitrion />)
    await userEvent.click(screen.getByRole('button', { name: 'Abrir' }))
    await userEvent.keyboard('{Escape}')
    expect(screen.getByRole('button', { name: 'Abrir' })).toHaveFocus()
  })

  it('si el botón ya no existe, el foco va al elemento estable', async () => {
    function Quitado() {
      const [abierta, setAbierta] = useState(false)
      const [hay, setHay] = useState(true)
      const estable = useRef(null)
      return (
        <div>
          <div ref={estable} tabIndex={-1} role="region" aria-label="Sección estable">
            {hay && <button type="button" onClick={() => setAbierta(true)}>Abrir</button>}
          </div>
          {abierta && (
            <Confirmacion
              pregunta="¿Seguro?" textoConfirmar="Sí" focoEstable={estable}
              alConfirmar={() => { setHay(false); setAbierta(false) }} alCancelar={() => setAbierta(false)}
            />
          )}
        </div>
      )
    }
    render(<Quitado />)
    await userEvent.click(screen.getByRole('button', { name: 'Abrir' }))
    await userEvent.click(screen.getByRole('button', { name: 'Sí' }))
    expect(screen.getByRole('region', { name: 'Sección estable' })).toHaveFocus()
  })

  it('el botón que la abrió se cambia en el mismo cuadro: el foco va al nuevo, no al viejo', async () => {
    // Como la ficha de empresa: la confirmación ocupa el lugar de los botones.
    function Cambiada() {
      const [abierta, setAbierta] = useState(false)
      const nuevo = useRef(null)
      return (
        <div>
          {abierta ? (
            <Confirmacion
              pregunta="¿Seguro?" textoConfirmar="Sí" volverA={() => nuevo.current}
              alConfirmar={() => setAbierta(false)} alCancelar={() => setAbierta(false)}
            />
          ) : (
            <button type="button" ref={nuevo} onClick={() => setAbierta(true)}>Abrir</button>
          )}
        </div>
      )
    }
    render(<Cambiada />)
    await userEvent.click(screen.getByRole('button', { name: 'Abrir' }))
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(screen.getByRole('button', { name: 'Abrir' })).toHaveFocus()
  })

  it('quien la usa puede decir a dónde vuelve el foco según lo que se hizo', async () => {
    const volverA = vi.fn((accion) => document.getElementById(accion === 'confirmar' ? 'a' : 'b'))
    function Doble() {
      const [abierta, setAbierta] = useState(true)
      return (
        <div>
          <button type="button" id="a">A</button>
          <button type="button" id="b">B</button>
          {abierta && (
            <Confirmacion
              pregunta="¿Seguro?" textoConfirmar="Sí" volverA={volverA}
              alConfirmar={() => setAbierta(false)} alCancelar={() => setAbierta(false)}
            />
          )}
        </div>
      )
    }
    render(<Doble />)
    await userEvent.click(screen.getByRole('button', { name: 'Sí' }))
    expect(document.getElementById('a')).toHaveFocus()
  })

  it('con StrictMode lleva el foco a la caja y lo devuelve al cerrar', async () => {
    render(<StrictMode><Anfitrion /></StrictMode>)
    await userEvent.click(screen.getByRole('button', { name: 'Abrir' }))
    expect(screen.getByRole('group', { name: '¿Desactivar la cuenta?' })).toHaveFocus()
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(screen.getByRole('button', { name: 'Abrir' })).toHaveFocus()
  })
})

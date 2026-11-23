import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { administradores } from '../../servicios/api/caja.js'
import { ADMINS, prepararApi } from './ayuda.js'
import DialogoPin from './DialogoPin.jsx'

vi.mock('../../servicios/api/caja.js')

const alConfirmar = vi.fn()
const alCerrar = vi.fn()

beforeEach(() => {
  vi.resetAllMocks()
  prepararApi()
  alConfirmar.mockResolvedValue(null)
})

const MOTIVOS = ['Descuento en la venta', 'Venta como encargo (sin existencia)']
const montar = () => render(<DialogoPin motivos={MOTIVOS} alConfirmar={alConfirmar} alCerrar={alCerrar} />)
const pin = () => screen.getByLabelText(/^pin$/i)

describe('diálogo del PIN', () => {
  it('es un diálogo que lista lo que se va a autorizar y pide administrador y PIN', async () => {
    montar()
    expect(screen.getByRole('dialog', { name: /autorización del administrador/i })).toBeInTheDocument()
    expect(screen.getByText('Descuento en la venta')).toBeInTheDocument()
    expect(screen.getByText('Venta como encargo (sin existencia)')).toBeInTheDocument()
    expect(await screen.findByRole('option', { name: 'Laura Pérez' })).toBeInTheDocument()
    expect(pin()).toHaveAttribute('type', 'password')
    expect(pin()).toHaveAttribute('inputmode', 'numeric')
    expect(pin()).toHaveAttribute('autocomplete', 'off')
  })

  it('solo deja autorizar con un administrador elegido y un PIN de 4 a 6 dígitos', async () => {
    montar()
    await screen.findByRole('option', { name: 'Laura Pérez' })
    const boton = screen.getByRole('button', { name: /autorizar y cobrar/i })
    expect(boton).toBeDisabled()
    await userEvent.selectOptions(screen.getByLabelText(/^administrador$/i), 'Laura Pérez')
    await userEvent.type(pin(), '12ab3')
    expect(pin()).toHaveValue('123')
    expect(boton).toBeDisabled()
    await userEvent.type(pin(), '4')
    expect(boton).toBeEnabled()
  })

  it('manda el administrador y el PIN, y se queda cerrado solo si todo salió bien', async () => {
    montar()
    await screen.findByRole('option', { name: 'Laura Pérez' })
    await userEvent.selectOptions(screen.getByLabelText(/^administrador$/i), 'Laura Pérez')
    await userEvent.type(pin(), '1234')
    await userEvent.click(screen.getByRole('button', { name: /autorizar y cobrar/i }))
    expect(alConfirmar).toHaveBeenCalledWith({ administradorId: ADMINS[0].id, pin: '1234' })
  })

  it('pinErradoMuestraErrorSinCerrar: el error del servidor se anuncia y el diálogo sigue', async () => {
    alConfirmar.mockResolvedValue('El PIN no es correcto.')
    montar()
    await screen.findByRole('option', { name: 'Laura Pérez' })
    await userEvent.selectOptions(screen.getByLabelText(/^administrador$/i), 'Laura Pérez')
    await userEvent.type(pin(), '0000')
    await userEvent.click(screen.getByRole('button', { name: /autorizar y cobrar/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent('El PIN no es correcto.')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(alCerrar).not.toHaveBeenCalled()
    // El PIN errado se borra: se escribe de nuevo y no queda a la vista del siguiente.
    expect(pin()).toHaveValue('')
    expect(document.body).not.toHaveTextContent('0000')
  })

  it('un envío ignorado (ya hay otro en vuelo) no se toma por éxito ni por error: el diálogo sigue esperando', async () => {
    alConfirmar.mockResolvedValue({ ignorado: true })
    montar()
    await screen.findByRole('option', { name: 'Laura Pérez' })
    await userEvent.selectOptions(screen.getByLabelText(/^administrador$/i), 'Laura Pérez')
    await userEvent.type(pin(), '1234')
    await userEvent.click(screen.getByRole('button', { name: /autorizar y cobrar/i }))
    await waitFor(() => expect(alConfirmar).toHaveBeenCalled())
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(pin()).toHaveValue('1234')
    expect(screen.getByRole('button', { name: /cobrando/i })).toBeDisabled()
  })

  it('Escape y Cancelar cierran', async () => {
    montar()
    await userEvent.keyboard('{Escape}')
    expect(alCerrar).toHaveBeenCalledTimes(1)
    await userEvent.click(screen.getByRole('button', { name: /cancelar/i }))
    expect(alCerrar).toHaveBeenCalledTimes(2)
  })

  it('el PIN vive solo en el diálogo: al cerrarlo y abrirlo de nuevo está vacío y no queda en el almacenamiento ni en la consola', async () => {
    const consola = ['log', 'info', 'warn', 'error', 'debug'].map((n) => vi.spyOn(console, n))
    function Contenedor() {
      const [abierto, setAbierto] = useState(true)
      return (
        <>
          <button type="button" onClick={() => setAbierto(true)}>Reabrir</button>
          {abierto && <DialogoPin motivos={MOTIVOS} alConfirmar={alConfirmar} alCerrar={() => setAbierto(false)} />}
        </>
      )
    }
    render(<Contenedor />)
    await screen.findByRole('option', { name: 'Laura Pérez' })
    await userEvent.type(pin(), '4321')
    expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toContain('4321')
    await userEvent.click(screen.getByRole('button', { name: /cancelar/i }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Reabrir' }))
    expect(pin()).toHaveValue('')
    for (const espia of consola) {
      for (const llamada of espia.mock.calls) expect(JSON.stringify(llamada)).not.toContain('4321')
    }
  })

  it('con un solo administrador ya elegido, el foco va al campo del PIN', async () => {
    administradores.mockResolvedValue([ADMINS[0]])
    montar()
    await waitFor(() => expect(pin()).toHaveFocus())
    expect(screen.getByLabelText(/^administrador$/i)).toHaveValue(String(ADMINS[0].id))
  })

  it('si no se pudo traer la lista de administradores lo dice', async () => {
    administradores.mockRejectedValue(new Error('Se cayó'))
    montar()
    expect(await screen.findByRole('alert')).toHaveTextContent('Se cayó')
  })
})

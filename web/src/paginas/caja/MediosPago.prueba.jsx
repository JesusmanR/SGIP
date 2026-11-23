import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { cambiarActivoMedio, listarMedios } from '../../servicios/api/caja.js'
import { MEDIOS, entrar, errorApi } from './ayuda.js'
import MediosPago from './MediosPago.jsx'

vi.mock('../../servicios/api/caja.js')

beforeEach(() => {
  vi.resetAllMocks()
  entrar('ADMINISTRADOR')
  listarMedios.mockResolvedValue(MEDIOS)
  cambiarActivoMedio.mockImplementation(async (id, activo) => ({ ...MEDIOS.find((m) => m.id === id), activo }))
})

const interruptor = (nombre) => screen.getByRole('switch', { name: nombre })

describe('medios de pago', () => {
  it('lista los medios con su interruptor de activo', async () => {
    render(<MediosPago />)
    expect(screen.getByRole('heading', { level: 1, name: /medios de pago/i })).toBeInTheDocument()
    expect(await screen.findByText('Transferencia')).toBeInTheDocument()
    expect(interruptor(/efectivo/i)).toBeChecked()
    expect(interruptor(/tarjeta/i)).toBeChecked()
  })

  it('desactiva y vuelve a activar un medio', async () => {
    render(<MediosPago />)
    await screen.findByText('Transferencia')
    await userEvent.click(interruptor(/tarjeta/i))
    expect(cambiarActivoMedio).toHaveBeenCalledWith(2, false)
    await waitFor(() => expect(interruptor(/tarjeta/i)).not.toBeChecked())
    await userEvent.click(interruptor(/tarjeta/i))
    expect(cambiarActivoMedio).toHaveBeenLastCalledWith(2, true)
    await waitFor(() => expect(interruptor(/tarjeta/i)).toBeChecked())
  })

  it('noSePuedeDesactivarElUltimoMedio: lo explica y no llama al servidor', async () => {
    listarMedios.mockResolvedValue([
      MEDIOS[0], { ...MEDIOS[1], activo: false }, { ...MEDIOS[2], activo: false }, MEDIOS[3],
    ])
    render(<MediosPago />)
    await screen.findByText('Transferencia')
    await userEvent.click(interruptor(/efectivo/i))
    expect(cambiarActivoMedio).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveTextContent(/debe quedar al menos un medio de pago activo/i)
    expect(interruptor(/efectivo/i)).toBeChecked()
  })

  it('si el servidor lo rechaza, muestra su mensaje y deja el interruptor como estaba', async () => {
    cambiarActivoMedio.mockRejectedValueOnce(errorApi(409, 'Debe quedar al menos un medio activo.'))
    render(<MediosPago />)
    await screen.findByText('Transferencia')
    await userEvent.click(interruptor(/tarjeta/i))
    expect(await screen.findByRole('alert')).toHaveTextContent('Debe quedar al menos un medio activo.')
    expect(interruptor(/tarjeta/i)).toBeChecked()
  })

  it('el medio de sistema «Saldo a favor» aparece sin interruptor y con su explicación', async () => {
    render(<MediosPago />)
    await screen.findByText('Transferencia')
    const fila = screen.getByText('Saldo a favor').closest('li')
    expect(within(fila).queryByRole('switch')).not.toBeInTheDocument()
    expect(within(fila).getByText(/lo gestiona el sistema/i)).toBeInTheDocument()
  })

  it('un 403 se muestra como falta de permiso', async () => {
    listarMedios.mockRejectedValue(errorApi(403, 'No'))
    render(<MediosPago />)
    expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument()
  })
})

import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { entregarEncargo, listarEncargos } from '../../servicios/api/caja.js'
import { entrar, errorApi } from './ayuda.js'
import Encargos from './Encargos.jsx'

vi.mock('../../servicios/api/caja.js')

const PENDIENTE = {
  itemId: 71, consecutivo: 'V-0042', cliente: 'Ana Pérez', producto: 'Pintura blanca', cantidad: 2,
  entregaEstimada: '2026-11-25', estado: 'PENDIENTE',
}
const LLEGO = {
  itemId: 72, consecutivo: 'V-0040', cliente: 'Luis Soto', producto: 'Tornillo M6', cantidad: 10,
  entregaEstimada: '2026-11-20', estado: 'LLEGO',
}

beforeEach(() => {
  vi.resetAllMocks()
  entrar('ADMINISTRADOR')
  listarEncargos.mockImplementation(async (estado) => (estado === 'LLEGO' ? [LLEGO] : [PENDIENTE]))
  entregarEncargo.mockResolvedValue(null)
})

describe('encargos', () => {
  it('muestra las pestañas Pendientes y Ya llegó, cada una con su lista', async () => {
    render(<Encargos />)
    expect(screen.getByRole('heading', { level: 1, name: /encargos/i })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /^pendientes$/i })).toHaveAttribute('aria-selected', 'true')
    expect(await screen.findByText('Pintura blanca')).toBeInTheDocument()
    expect(listarEncargos).toHaveBeenCalledWith('PENDIENTE')
    expect(screen.getByText('Ana Pérez')).toBeInTheDocument()
    expect(screen.getByText('25/11/2026')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('tab', { name: /ya llegó/i }))
    expect(await screen.findByText('Tornillo M6')).toBeInTheDocument()
    expect(listarEncargos).toHaveBeenCalledWith('LLEGO')
    expect(screen.queryByText('Pintura blanca')).not.toBeInTheDocument()
  })

  it('«Marcar entregado» entrega la línea y la saca de la lista', async () => {
    render(<Encargos />)
    await screen.findByText('Pintura blanca')
    listarEncargos.mockResolvedValue([])
    await userEvent.click(screen.getByRole('button', { name: /marcar entregado.*pintura blanca/i }))
    expect(entregarEncargo).toHaveBeenCalledWith(71)
    expect(await screen.findByText(/no hay encargos pendientes/i)).toBeInTheDocument()
  })

  it('si no se pudo entregar, lo dice y deja la fila', async () => {
    entregarEncargo.mockRejectedValueOnce(errorApi(409, 'Este encargo ya se entregó.'))
    render(<Encargos />)
    await screen.findByText('Pintura blanca')
    await userEvent.click(screen.getByRole('button', { name: /marcar entregado/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Este encargo ya se entregó.')
    expect(screen.getByText('Pintura blanca')).toBeInTheDocument()
  })

  it('encargosSoloParaElAdministrador: el vendedor no ve la lista ni la pide', async () => {
    entrar('VENDEDOR')
    render(<Encargos />)
    expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument()
    expect(listarEncargos).not.toHaveBeenCalled()
  })

  it('un 403 del servidor se muestra como falta de permiso', async () => {
    listarEncargos.mockRejectedValue(errorApi(403, 'No autorizado'))
    render(<Encargos />)
    expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument()
  })
})

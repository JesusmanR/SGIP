import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  cobrarDomicilio, entregarDomicilio, listarDomicilios, saldoDeCliente,
} from '../../servicios/api/caja.js'
import { entrar, errorApi, pesos, prepararApi } from './ayuda.js'
import Domicilios from './Domicilios.jsx'

vi.mock('../../servicios/api/caja.js')

const DESPACHAR = {
  ventaId: 100, consecutivo: 'V-0042', cliente: 'Ana Pérez', clienteId: 7, direccion: 'Calle 1 # 2-3',
  ciudad: 'Cali', telefono: '3001112233', recibe: 'Ana', contraEntrega: false, valorPorCobrar: 0, antiguedadDias: 0,
}
const COBRAR = {
  ventaId: 101, consecutivo: 'V-0043', cliente: 'Luis Soto', clienteId: 8, direccion: 'Carrera 5 # 6-7',
  ciudad: 'Cali', telefono: '3009998877', recibe: 'Luis', contraEntrega: true, valorPorCobrar: 85000, antiguedadDias: 3,
}
const COBRAR_ENTREGADO = { ...COBRAR, ventaId: 103, consecutivo: 'V-0045', entregado: true }
const COBRAR_HOY = { ...COBRAR, ventaId: 102, consecutivo: 'V-0044', valorPorCobrar: 12000, antiguedadDias: 0 }

function montar() {
  return render(<MemoryRouter><Domicilios /></MemoryRouter>)
}

beforeEach(() => {
  vi.resetAllMocks()
  prepararApi()
  entrar('VENDEDOR')
  listarDomicilios.mockImplementation(async (estado) => (estado === 'POR_COBRAR' ? [COBRAR, COBRAR_HOY] : [DESPACHAR, COBRAR]))
  entregarDomicilio.mockResolvedValue(null)
  cobrarDomicilio.mockResolvedValue(null)
  saldoDeCliente.mockResolvedValue({ saldo: 0, medioId: 9 })
})

describe('domicilios', () => {
  it('domiciliosPestanasYAcciones: «Por despachar» y «Por cobrar», y «Marcar entregado» funciona en las dos', async () => {
    montar()
    expect(screen.getByRole('heading', { level: 1, name: /domicilios/i })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /por despachar/i })).toHaveAttribute('aria-selected', 'true')
    expect(await screen.findByText('V-0042')).toBeInTheDocument()
    expect(listarDomicilios).toHaveBeenCalledWith('POR_DESPACHAR')
    expect(screen.getByText(/Calle 1 # 2-3/)).toBeInTheDocument()

    // En «Por despachar»
    listarDomicilios.mockImplementation(async (estado) => (estado === 'POR_DESPACHAR' ? [COBRAR] : [COBRAR, COBRAR_HOY]))
    await userEvent.click(screen.getByRole('button', { name: /marcar entregado.*V-0042/i }))
    expect(entregarDomicilio).toHaveBeenCalledWith(100)
    await waitFor(() => expect(screen.queryByText('V-0042')).not.toBeInTheDocument())

    // En «Por cobrar»
    await userEvent.click(screen.getByRole('tab', { name: /por cobrar/i }))
    expect(await screen.findByText('V-0044')).toBeInTheDocument()
    expect(listarDomicilios).toHaveBeenCalledWith('POR_COBRAR')
    listarDomicilios.mockImplementation(async () => [COBRAR_HOY])
    await userEvent.click(screen.getByRole('button', { name: /marcar entregado.*V-0043/i }))
    expect(entregarDomicilio).toHaveBeenLastCalledWith(101)
    await waitFor(() => expect(screen.queryByText('V-0043')).not.toBeInTheDocument())
  })

  it('porCobrarMuestraValorYAntiguedad: cada venta dice cuánto falta y hace cuántos días', async () => {
    montar()
    await screen.findByText('V-0042')
    await userEvent.click(screen.getByRole('tab', { name: /por cobrar/i }))
    const tabla = await screen.findByRole('table', { name: /domicilios por cobrar/i })
    const filaTres = within(tabla).getByText('V-0043').closest('tr')
    expect(within(filaTres).getByText(pesos(85000))).toBeInTheDocument()
    expect(within(filaTres).getByText('Hace 3 días')).toBeInTheDocument()
    const filaHoy = within(tabla).getByText('V-0044').closest('tr')
    expect(within(filaHoy).getByText(pesos(12000))).toBeInTheDocument()
    expect(within(filaHoy).getByText('De hoy')).toBeInTheDocument()
  })

  it('«Registrar cobro» abre el Cobro por el saldo por cobrar y registra los pagos', async () => {
    montar()
    await screen.findByText('V-0042')
    await userEvent.click(screen.getByRole('tab', { name: /por cobrar/i }))
    await userEvent.click(await screen.findByRole('button', { name: /registrar cobro.*V-0043/i }))
    const cobro = await screen.findByRole('region', { name: /cobro de V-0043/i })
    expect(within(cobro).getByTestId('total-a-cobrar')).toHaveTextContent(pesos(85000))
    expect(within(cobro).getByLabelText(/^monto$/i)).toHaveValue('85000')
    await userEvent.click(within(cobro).getByRole('button', { name: /agregar pago/i }))
    listarDomicilios.mockImplementation(async () => [COBRAR_HOY])
    await userEvent.click(within(cobro).getByRole('button', { name: /^cobrar$/i }))
    await waitFor(() => expect(cobrarDomicilio).toHaveBeenCalledWith(101, [{ medioId: 1, monto: 85000, recibido: 85000 }]))
    await waitFor(() => expect(screen.queryByText('V-0043')).not.toBeInTheDocument())
    expect(screen.queryByRole('region', { name: /cobro de V-0043/i })).not.toBeInTheDocument()
  })

  it('un cobro rechazado se anuncia y deja el cobro abierto', async () => {
    cobrarDomicilio.mockRejectedValueOnce(errorApi(409, 'Necesitas un turno abierto para cobrar.'))
    montar()
    await screen.findByText('V-0042')
    await userEvent.click(screen.getByRole('tab', { name: /por cobrar/i }))
    await userEvent.click(await screen.findByRole('button', { name: /registrar cobro.*V-0043/i }))
    const cobro = await screen.findByRole('region', { name: /cobro de V-0043/i })
    await userEvent.click(within(cobro).getByRole('button', { name: /agregar pago/i }))
    await userEvent.click(within(cobro).getByRole('button', { name: /^cobrar$/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Necesitas un turno abierto para cobrar.')
    expect(screen.getByRole('region', { name: /cobro de V-0043/i })).toBeInTheDocument()
  })

  it('un fallo al entregar se anuncia y la fila sigue', async () => {
    entregarDomicilio.mockRejectedValueOnce(errorApi(409, 'Este domicilio ya se entregó.'))
    montar()
    await screen.findByText('V-0042')
    await userEvent.click(screen.getByRole('button', { name: /marcar entregado.*V-0042/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Este domicilio ya se entregó.')
    expect(screen.getByText('V-0042')).toBeInTheDocument()
  })

  it('sin domicilios lo dice en cada pestaña', async () => {
    listarDomicilios.mockResolvedValue([])
    montar()
    expect(await screen.findByText(/no hay domicilios por despachar/i)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('tab', { name: /por cobrar/i }))
    expect(await screen.findByText(/no hay domicilios por cobrar/i)).toBeInTheDocument()
  })

  it('en «Por cobrar» no ofrece «Marcar entregado» a lo que ya se entregó, pero sí cobrarlo', async () => {
    listarDomicilios.mockImplementation(async (estado) => (estado === 'POR_COBRAR' ? [COBRAR, COBRAR_ENTREGADO] : []))
    montar()
    await userEvent.click(screen.getByRole('tab', { name: /por cobrar/i }))
    await screen.findByText('V-0045')
    expect(screen.queryByRole('button', { name: /marcar entregado.*V-0045/i })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /registrar cobro.*V-0045/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /marcar entregado.*V-0043/i })).toBeInTheDocument()
  })
})

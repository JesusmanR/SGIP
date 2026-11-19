import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  registrarDevolucion, saldoDeCliente, ventaPorConsecutivo,
} from '../../servicios/api/caja.js'
import { entrar, errorApi, pesos, prepararApi } from './ayuda.js'
import Devolucion from './Devolucion.jsx'

vi.mock('../../servicios/api/caja.js')

// Tornillo: 3 unidades a 11.900 (35.700). Pintura: 1 unidad a 23.800.
const VENTA = {
  id: 100, consecutivo: 'V-0042', estado: 'ACTIVA', estadoPago: 'PAGADA', total: 59500,
  cliente: { id: 7, nombre: 'Ana Pérez', consumidorFinal: false },
  lineas: [
    { productoId: 30, nombre: 'Tornillo M6', cantidad: 3, cantidadDevuelta: 0, total: 35700, valorDevuelto: 0 },
    { productoId: 31, nombre: 'Pintura blanca', cantidad: 1, cantidadDevuelta: 0, total: 23800, valorDevuelto: 0 },
  ],
  pagos: [{ medioId: 2, medio: 'Tarjeta', tipo: 'TARJETA', monto: 59500 }],
}
const CONSUMIDOR_FINAL = { ...VENTA, cliente: { id: 1, nombre: 'Consumidor final', consumidorFinal: true } }
const PENDIENTE = { ...VENTA, estadoPago: 'PENDIENTE', pagos: [] }

function montar() {
  return render(<MemoryRouter><Devolucion /></MemoryRouter>)
}

async function buscar(venta = VENTA) {
  ventaPorConsecutivo.mockResolvedValue(venta)
  await userEvent.type(screen.getByLabelText(/consecutivo de la venta/i), venta.consecutivo)
  await userEvent.click(screen.getByRole('button', { name: /buscar venta/i }))
  await screen.findByLabelText('Cantidad a devolver de Tornillo M6')
}
const cantidad = async (nombre, n) => {
  const campo = screen.getByLabelText(`Cantidad a devolver de ${nombre}`)
  await userEvent.clear(campo)
  await userEvent.type(campo, String(n))
}
const dinero = async (rotulo, n) => {
  const campo = screen.getByLabelText(rotulo)
  await userEvent.clear(campo)
  await userEvent.type(campo, String(n))
}
const motivo = (texto = 'El cliente se equivocó de medida') => userEvent.type(screen.getByLabelText(/motivo de la devolución/i), texto)
const confirmar = () => userEvent.click(screen.getByRole('button', { name: /confirmar devolución/i }))

beforeEach(() => {
  vi.resetAllMocks()
  prepararApi()
  entrar('VENDEDOR')
  saldoDeCliente.mockResolvedValue({ saldo: 0, medioId: 9 })
  registrarDevolucion.mockResolvedValue({
    id: 1, consecutivo: 'D-00000001', valorDevuelto: 11900, reembolsado: 11900, saldoPorCobrar: 0, estadoVenta: 'DEVUELTA_PARCIAL', reembolsos: [],
  })
})

describe('devolución', () => {
  it('busca la venta por su consecutivo y dice si no existe', async () => {
    ventaPorConsecutivo.mockResolvedValue(null)
    montar()
    await userEvent.type(screen.getByLabelText(/consecutivo de la venta/i), 'V-9999')
    await userEvent.click(screen.getByRole('button', { name: /buscar venta/i }))
    expect(ventaPorConsecutivo).toHaveBeenCalledWith('V-9999')
    expect(await screen.findByText(/no hay una venta con ese consecutivo/i)).toBeInTheDocument()
  })

  it('al elegir las unidades muestra el valor de lo que se devuelve', async () => {
    montar()
    await buscar()
    await cantidad('Tornillo M6', 1)
    const resumen = screen.getByRole('region', { name: /resumen de la devolución/i })
    expect(within(resumen).getByTestId('valor-a-devolver')).toHaveTextContent(pesos(11900))
    // La que completa la línea toma lo que quede: las tres juntas valen exactamente la línea.
    await cantidad('Tornillo M6', 3)
    expect(within(resumen).getByTestId('valor-a-devolver')).toHaveTextContent(pesos(35700))
  })

  it('no deja devolver más de lo vendido', async () => {
    montar()
    await buscar()
    await cantidad('Tornillo M6', 4)
    expect(screen.getByText(/solo se pueden devolver 3/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /confirmar devolución/i })).toBeDisabled()
  })

  it('devolucionPideMotivoYPin: el vendedor prepara, y al confirmar el administrador autoriza con su PIN', async () => {
    montar()
    await buscar()
    await cantidad('Tornillo M6', 1)
    await dinero(/^por tarjeta/i, 11900)
    await userEvent.type(screen.getByLabelText(/n\.º de aprobación/i), 'A-123')

    // Sin motivo no se abre nada.
    await confirmar()
    expect(screen.getByText(/escribe el motivo de la devolución/i)).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(registrarDevolucion).not.toHaveBeenCalled()

    await motivo()
    await confirmar()
    const dialogo = await screen.findByRole('dialog', { name: /autorización del administrador/i })
    expect(within(dialogo).getByText('Devolución de la venta V-0042')).toBeInTheDocument()
    expect(registrarDevolucion).not.toHaveBeenCalled()
    await userEvent.selectOptions(within(dialogo).getByLabelText(/administrador/i), '3')
    await userEvent.type(within(dialogo).getByLabelText(/^pin$/i), '4821')
    await userEvent.click(within(dialogo).getByRole('button', { name: /autorizar y devolver/i }))
    await waitFor(() => expect(registrarDevolucion).toHaveBeenCalled())
    expect(registrarDevolucion).toHaveBeenCalledWith({
      ventaId: 100,
      unidades: [{ productoId: 30, cantidad: 1 }],
      motivo: 'El cliente se equivocó de medida',
      reembolsos: [{ medioId: 2, monto: 11900, referencia: 'A-123' }],
      autorizacion: { administradorId: 3, pin: '4821' },
    })
    expect(await screen.findByRole('heading', { name: /devolución registrada/i })).toBeInTheDocument()
    expect(screen.getByText(/D-00000001/)).toBeInTheDocument()
  })

  it('el administrador no necesita PIN: devuelve directo', async () => {
    entrar('ADMINISTRADOR')
    montar()
    await buscar()
    await cantidad('Tornillo M6', 1)
    await dinero(/^efectivo$/i, 11900)
    await motivo()
    await confirmar()
    await waitFor(() => expect(registrarDevolucion).toHaveBeenCalled())
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(registrarDevolucion.mock.calls[0][0]).toEqual({
      ventaId: 100, unidades: [{ productoId: 30, cantidad: 1 }], motivo: 'El cliente se equivocó de medida',
      reembolsos: [{ medioId: 1, monto: 11900 }],
    })
  })

  it('un PIN rechazado se dice en el diálogo y deja intentar otra vez', async () => {
    registrarDevolucion.mockRejectedValueOnce(errorApi(403, 'El PIN no es correcto.'))
    montar()
    await buscar()
    await cantidad('Tornillo M6', 1)
    await dinero(/^efectivo$/i, 11900)
    await motivo()
    await confirmar()
    const dialogo = await screen.findByRole('dialog')
    await userEvent.selectOptions(within(dialogo).getByLabelText(/administrador/i), '3')
    await userEvent.type(within(dialogo).getByLabelText(/^pin$/i), '0000')
    await userEvent.click(within(dialogo).getByRole('button', { name: /autorizar y devolver/i }))
    expect(await within(dialogo).findByRole('alert')).toHaveTextContent('El PIN no es correcto.')
    expect(within(dialogo).getByLabelText(/^pin$/i)).toHaveValue('')
  })

  it('devolucionDeVentaPendienteNoPideReembolso: una venta sin cobrar no reparte dinero', async () => {
    entrar('ADMINISTRADOR')
    montar()
    await buscar(PENDIENTE)
    expect(screen.getByText(/todavía no se cobró/i)).toBeInTheDocument()
    expect(screen.queryByLabelText(/^efectivo$/i)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/saldo a favor/i)).not.toBeInTheDocument()
    await cantidad('Pintura blanca', 1)
    await motivo()
    await confirmar()
    await waitFor(() => expect(registrarDevolucion).toHaveBeenCalled())
    expect(registrarDevolucion.mock.calls[0][0].reembolsos).toEqual([])
  })

  it('devolucionPorTarjetaPideReferencia: sin el número de aprobación no sigue', async () => {
    entrar('ADMINISTRADOR')
    montar()
    await buscar()
    await cantidad('Tornillo M6', 1)
    await dinero(/^por tarjeta/i, 11900)
    await motivo()
    await confirmar()
    expect(screen.getByText(/escribe el número de aprobación/i)).toBeInTheDocument()
    expect(registrarDevolucion).not.toHaveBeenCalled()
  })

  it('el reembolso tiene que sumar el valor devuelto', async () => {
    entrar('ADMINISTRADOR')
    montar()
    await buscar()
    await cantidad('Tornillo M6', 1)
    await dinero(/^efectivo$/i, 5000)
    expect(screen.getByText(`Falta repartir ${pesos(6900)}`)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /confirmar devolución/i })).toBeDisabled()
    await dinero(/^efectivo$/i, 20000)
    expect(screen.getByText(`Te pasas por ${pesos(8100)}`)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /confirmar devolución/i })).toBeDisabled()
  })

  it('se puede repartir entre efectivo, el mismo medio y saldo a favor', async () => {
    entrar('ADMINISTRADOR')
    montar()
    await buscar()
    await cantidad('Pintura blanca', 1)
    await dinero(/^efectivo$/i, 10000)
    await dinero(/^por tarjeta/i, 8000)
    await userEvent.type(screen.getByLabelText(/n\.º de aprobación/i), 'B-9')
    await dinero(/^saldo a favor/i, 5800)
    await motivo()
    await confirmar()
    await waitFor(() => expect(registrarDevolucion).toHaveBeenCalled())
    expect(registrarDevolucion.mock.calls[0][0].reembolsos).toEqual([
      { medioId: 1, monto: 10000 },
      { medioId: 2, monto: 8000, referencia: 'B-9' },
      { medioId: 9, monto: 5800 },
    ])
  })

  it('saldoAFavorDeshabilitadoConConsumidorFinal: no se abona saldo a quien no está identificado', async () => {
    montar()
    await buscar(CONSUMIDOR_FINAL)
    expect(screen.getByLabelText(/^saldo a favor/i)).toBeDisabled()
    expect(screen.getByText(/necesita un cliente identificado/i)).toBeInTheDocument()
    expect(saldoDeCliente).not.toHaveBeenCalled()
  })

  it('con un cliente identificado el saldo a favor se puede usar', async () => {
    montar()
    await buscar()
    await waitFor(() => expect(saldoDeCliente).toHaveBeenCalledWith(7))
    expect(screen.getByLabelText(/^saldo a favor/i)).toBeEnabled()
  })

  it('«Anular toda la venta» devuelve todo y manda las unidades vacías', async () => {
    entrar('ADMINISTRADOR')
    montar()
    await buscar()
    await userEvent.click(screen.getByRole('button', { name: /anular toda la venta/i }))
    expect(screen.getByLabelText('Cantidad a devolver de Tornillo M6')).toHaveValue('3')
    expect(screen.getByTestId('valor-a-devolver')).toHaveTextContent(pesos(59500))
    await dinero(/^por tarjeta/i, 59500)
    await userEvent.type(screen.getByLabelText(/n\.º de aprobación/i), 'C-1')
    await motivo('Venta equivocada')
    await confirmar()
    await waitFor(() => expect(registrarDevolucion).toHaveBeenCalled())
    expect(registrarDevolucion.mock.calls[0][0]).toMatchObject({ ventaId: 100, unidades: [], motivo: 'Venta equivocada' })
  })

  it('un rechazo del servidor se anuncia y conserva lo preparado', async () => {
    entrar('ADMINISTRADOR')
    registrarDevolucion.mockRejectedValueOnce(errorApi(409, 'Esta venta ya está anulada.'))
    montar()
    await buscar()
    await cantidad('Tornillo M6', 1)
    await dinero(/^efectivo$/i, 11900)
    await motivo()
    await confirmar()
    expect(await screen.findByRole('alert')).toHaveTextContent('Esta venta ya está anulada.')
    expect(screen.getByLabelText('Cantidad a devolver de Tornillo M6')).toHaveValue('1')
  })

  it('después de registrar permite devolver otra venta', async () => {
    entrar('ADMINISTRADOR')
    montar()
    await buscar()
    await cantidad('Tornillo M6', 1)
    await dinero(/^efectivo$/i, 11900)
    await motivo()
    await confirmar()
    await userEvent.click(await screen.findByRole('button', { name: /otra devolución/i }))
    expect(screen.getByLabelText(/consecutivo de la venta/i)).toHaveValue('')
  })
})

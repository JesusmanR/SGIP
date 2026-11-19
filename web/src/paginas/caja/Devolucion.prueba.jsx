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
// Pagada en parte con tarjeta (40.000) y en parte con saldo a favor (19.500).
const CON_SALDO = {
  ...VENTA,
  pagos: [
    { medioId: 2, medio: 'Tarjeta', tipo: 'TARJETA', monto: 40000 },
    { medioId: 9, medio: 'Saldo a favor', tipo: 'SALDO_A_FAVOR', monto: 19500 },
  ],
}

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

  it('el motivo admite hasta 200 caracteres, el límite del servidor', async () => {
    montar()
    await buscar()
    expect(screen.getByLabelText(/motivo de la devolución/i)).toHaveAttribute('maxlength', '200')
  })

  it('el mensaje de un 400 del servidor se muestra tal cual', async () => {
    entrar('ADMINISTRADOR')
    registrarDevolucion.mockRejectedValueOnce(errorApi(400, 'El reembolso no cuadra con lo pagado.'))
    montar()
    await buscar()
    await cantidad('Tornillo M6', 1)
    await dinero(/^efectivo$/i, 11900)
    await motivo()
    await confirmar()
    expect(await screen.findByRole('alert')).toHaveTextContent('El reembolso no cuadra con lo pagado.')
  })

  describe('lo pagado con saldo a favor vuelve solo como saldo a favor', () => {
    beforeEach(() => entrar('ADMINISTRADOR'))

    it('al devolver todo deja el saldo preseleccionado por lo que se pagó con saldo y lo explica en una línea', async () => {
      montar()
      await buscar(CON_SALDO)
      await userEvent.click(screen.getByRole('button', { name: /anular toda la venta/i }))
      expect(screen.getByLabelText(/^saldo a favor/i)).toHaveValue('19500')
      expect(screen.getByText(/se pagaron \$\s19\.500 con saldo a favor/i)).toBeInTheDocument()
      await dinero(/^por tarjeta/i, 40000)
      await userEvent.type(screen.getByLabelText(/n\.º de aprobación/i), 'D-1')
      expect(screen.getByRole('button', { name: /confirmar devolución/i })).toBeEnabled()
      await motivo('Venta equivocada')
      await confirmar()
      await waitFor(() => expect(registrarDevolucion).toHaveBeenCalled())
      expect(registrarDevolucion.mock.calls[0][0].reembolsos).toEqual([
        { medioId: 2, monto: 40000, referencia: 'D-1' },
        { medioId: 9, monto: 19500 },
      ])
    })

    it('no deja devolver en efectivo ni por tarjeta más de lo que se pagó así, aunque la suma cuadre', async () => {
      montar()
      await buscar(CON_SALDO)
      await userEvent.click(screen.getByRole('button', { name: /anular toda la venta/i }))
      await dinero(/^por tarjeta/i, 45000)
      await dinero(/^saldo a favor/i, 14500)
      expect(screen.getByText(/lo que se pagó con saldo a favor vuelve como saldo/i)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /confirmar devolución/i })).toBeDisabled()
    })

    it('el efectivo cuenta igual que la tarjeta: nunca se vuelve efectivo lo pagado con saldo', async () => {
      montar()
      await buscar(CON_SALDO)
      await userEvent.click(screen.getByRole('button', { name: /anular toda la venta/i }))
      await dinero(/^efectivo$/i, 59500)
      await dinero(/^saldo a favor/i, 0)
      expect(screen.getByRole('button', { name: /confirmar devolución/i })).toBeDisabled()
    })

    it('si lo devuelto cabe en lo pagado sin saldo, el saldo no se preselecciona', async () => {
      montar()
      await buscar(CON_SALDO)
      await cantidad('Tornillo M6', 1)
      expect(screen.getByLabelText(/^saldo a favor/i)).toHaveValue('')
      expect(screen.queryByText(/se pagaron/i)).not.toBeInTheDocument()
    })

    describe('lo ya devuelto antes', () => {
      // 59.500 pagados (40.000 sin saldo + 19.500 con saldo); el tornillo ya se devolvió completo (35.700).
      const parcial = (extra = {}) => ({
        ...CON_SALDO,
        ...extra,
        lineas: [
          { ...CON_SALDO.lineas[0], cantidadDevuelta: 3, valorDevuelto: 35700 },
          CON_SALDO.lineas[1],
        ],
      })

      it('sin reembolsadoSinSaldo se estima: lo devuelto antes gastó primero lo pagado sin saldo', async () => {
        montar()
        await buscar(parcial())
        await cantidad('Pintura blanca', 1)
        // Quedan 40.000 - 35.700 = 4.300 sin saldo: de los 23.800, 19.500 vuelven como saldo.
        expect(screen.getByLabelText(/^saldo a favor/i)).toHaveValue('19500')
      })

      it('con reembolsadoSinSaldo manda el dato del servidor', async () => {
        montar()
        await buscar(parcial({ reembolsadoSinSaldo: 35700 }))
        await cantidad('Pintura blanca', 1)
        expect(screen.getByLabelText(/^saldo a favor/i)).toHaveValue('19500')
      })

      it('con reembolsadoSinSaldo en 0 no se estima nada: todo lo pagado sin saldo sigue disponible', async () => {
        montar()
        await buscar(parcial({ reembolsadoSinSaldo: 0 }))
        await cantidad('Pintura blanca', 1)
        expect(screen.getByLabelText(/^saldo a favor/i)).toHaveValue('')
      })

      it('el caso del revisor: 40.000 por tarjeta, 10.000 devueltos antes como saldo y ahora 49.500', async () => {
        const antes = (extra) => ({
          ...CON_SALDO,
          ...extra,
          lineas: [
            { ...CON_SALDO.lineas[0], cantidadDevuelta: 1, valorDevuelto: 10000 },
            CON_SALDO.lineas[1],
          ],
        })
        // Con el dato del servidor (0 reembolsado sin saldo) pueden salir 40.000 por tarjeta: 9.500 van como saldo.
        const primera = montar()
        await buscar(antes({ reembolsadoSinSaldo: 0 }))
        await userEvent.click(screen.getByRole('button', { name: /anular toda la venta/i }))
        expect(screen.getByTestId('valor-a-devolver')).toHaveTextContent(pesos(49500))
        expect(screen.getByLabelText(/^saldo a favor/i)).toHaveValue('9500')
        await dinero(/^por tarjeta/i, 40000)
        expect(screen.getByRole('button', { name: /confirmar devolución/i })).toBeEnabled()
        primera.unmount()

        // Sin el dato se estima lo más prudente: lo devuelto antes gastó primero lo pagado sin saldo.
        montar()
        await buscar(antes({}))
        await userEvent.click(screen.getByRole('button', { name: /anular toda la venta/i }))
        expect(screen.getByLabelText(/^saldo a favor/i)).toHaveValue('19500')
      })
    })
  })
})

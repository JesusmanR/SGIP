import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { hoyISO, sumarDias } from '../../servicios/fechas.js'
import {
  abrirTurno, CLAVE_EN_USO, mediosDePago, productoPorCodigo, productosPorTexto, registrarVenta, saldoDeCliente, turnoActual,
} from '../../servicios/api/caja.js'
import { CLAVE_VENTA_PENDIENTE } from '../../servicios/ventaPendiente.js'
import { ANA, LLAVE_A, LLAVE_B, MEDIOS, PINTURA, TORNILLO, entrar, errorApi, pesos, prepararApi, ventaDelServidor } from './ayuda.js'
import Caja from './Caja.jsx'

vi.mock('../../servicios/api/caja.js')

beforeEach(() => {
  vi.resetAllMocks()
  prepararApi()
  entrar('ADMINISTRADOR')
})
afterEach(() => vi.restoreAllMocks())

const buscador = () => screen.getByLabelText(/buscar producto/i)
const cobrar = () => screen.getByRole('button', { name: /^cobrar$/i })
const total = () => screen.getByTestId('total-a-cobrar')

async function abrir() {
  render(<MemoryRouter><Caja /></MemoryRouter>)
  await screen.findByLabelText(/buscar producto/i)
}
async function agregar(producto = TORNILLO) {
  await userEvent.type(buscador(), `${producto.codigoBarras}{Enter}`)
  await screen.findByLabelText(`Cantidad de ${producto.nombre}`)
}
async function pagarTodo(extra = {}) {
  if (extra.recibido) await userEvent.type(screen.getByLabelText(/^recibido$/i), String(extra.recibido))
  await userEvent.click(screen.getByRole('button', { name: /agregar pago/i }))
}

describe('caja: turno', () => {
  it('sinTurnoMuestraAbrirTurno: pide la base y, al abrir, muestra la venta', async () => {
    turnoActual.mockResolvedValue(null)
    abrirTurno.mockResolvedValue({ id: 6 })
    render(<MemoryRouter><Caja /></MemoryRouter>)
    expect(await screen.findByRole('heading', { name: /abrir turno/i })).toBeInTheDocument()
    expect(screen.queryByLabelText(/buscar producto/i)).not.toBeInTheDocument()
    await userEvent.type(screen.getByLabelText(/base de efectivo/i), '50000')
    await userEvent.click(screen.getByRole('button', { name: /^abrir turno$/i }))
    expect(abrirTurno).toHaveBeenCalledWith(50000)
    expect(await screen.findByLabelText(/buscar producto/i)).toBeInTheDocument()
  })

  it('la base es obligatoria y entera', async () => {
    turnoActual.mockResolvedValue(null)
    render(<MemoryRouter><Caja /></MemoryRouter>)
    await screen.findByRole('heading', { name: /abrir turno/i })
    await userEvent.click(screen.getByRole('button', { name: /^abrir turno$/i }))
    expect(screen.getByText(/escribe la base de efectivo/i)).toBeInTheDocument()
    expect(abrirTurno).not.toHaveBeenCalled()
  })

  it('un error al abrir el turno se anuncia', async () => {
    turnoActual.mockResolvedValue(null)
    abrirTurno.mockRejectedValue(errorApi(409, 'Ya tienes un turno abierto.'))
    render(<MemoryRouter><Caja /></MemoryRouter>)
    await screen.findByRole('heading', { name: /abrir turno/i })
    await userEvent.type(screen.getByLabelText(/base de efectivo/i), '0')
    await userEvent.click(screen.getByRole('button', { name: /^abrir turno$/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Ya tienes un turno abierto.')
  })

  it('un fallo al leer el turno se muestra con reintento', async () => {
    turnoActual.mockRejectedValueOnce(errorApi(500, 'Se cayó'))
    render(<MemoryRouter><Caja /></MemoryRouter>)
    expect(await screen.findByRole('alert')).toHaveTextContent('Se cayó')
    await userEvent.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByLabelText(/buscar producto/i)).toBeInTheDocument()
  })
})

describe('caja: buscar y agregar', () => {
  it('enterConCodigoAgregaYSumaCantidad', async () => {
    await abrir()
    await agregar()
    expect(screen.getByLabelText('Cantidad de Tornillo M6')).toHaveValue('1')
    await agregar()
    expect(screen.getByLabelText('Cantidad de Tornillo M6')).toHaveValue('2')
    expect(screen.getAllByRole('row', { name: /tornillo m6/i })).toHaveLength(1)
    expect(total()).toHaveTextContent(pesos(23800))
  })

  it('elFocoVuelveAlBuscador: al cargar, después de agregar y al cerrar un diálogo', async () => {
    await abrir()
    expect(buscador()).toHaveFocus()
    await agregar()
    await waitFor(() => expect(buscador()).toHaveFocus())
    await userEvent.type(buscador(), '000{Enter}')
    await userEvent.click(await screen.findByRole('button', { name: /crear producto/i }))
    expect(await screen.findByRole('dialog', { name: /producto nuevo/i })).toBeInTheDocument()
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(buscador()).toHaveFocus())
  })

  it('noEncontradoOfreceBuscarYCrear', async () => {
    await abrir()
    await userEvent.type(buscador(), '000{Enter}')
    expect(await screen.findByText(/no encontramos ese código/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /buscar por nombre/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /crear producto \(requiere administrador\)/i })).toBeInTheDocument()
  })

  it('productoNuevoSeAgregaAlCarritoSinCrearloYSeEnviaEnLaVenta', async () => {
    await abrir()
    await userEvent.type(buscador(), '7705550001{Enter}')
    await userEvent.click(await screen.findByRole('button', { name: /crear producto/i }))
    const dialogo = await screen.findByRole('dialog', { name: /producto nuevo/i })
    await userEvent.type(within(dialogo).getByLabelText(/^nombre/i), 'Llave de paso')
    await userEvent.type(within(dialogo).getByLabelText(/precio de venta/i), '5000')
    const fecha = sumarDias(hoyISO(), 10)
    await userEvent.type(within(dialogo).getByLabelText(/fecha de entrega/i), fecha)
    await userEvent.type(within(dialogo).getByLabelText(/motivo del encargo/i), 'Llega con el proveedor')
    await userEvent.click(within(dialogo).getByRole('button', { name: /agregar al carrito/i }))
    // No se crea nada en el servidor: solo hay una línea marcada.
    expect(screen.getByText('Producto nuevo — encargo')).toBeInTheDocument()
    expect(registrarVenta).not.toHaveBeenCalled()
    expect(total()).toHaveTextContent(pesos(5000))
    await pagarTodo()
    await userEvent.click(cobrar())
    await waitFor(() => expect(registrarVenta).toHaveBeenCalledTimes(1))
    const solicitud = registrarVenta.mock.calls[0][0]
    expect(solicitud.lineas).toEqual([{
      productoNuevo: { nombre: 'Llave de paso', precioVenta: 5000, codigoBarras: '7705550001' },
      cantidad: 1, entregaEstimada: fecha,
    }])
    expect(solicitud.motivoEncargo).toBe('Llega con el proveedor')
    expect(solicitud.autorizacion).toBeUndefined()
  })

  it('el carrito nunca manda un precio de un producto registrado', async () => {
    await abrir()
    await agregar()
    await pagarTodo()
    await userEvent.click(cobrar())
    await waitFor(() => expect(registrarVenta).toHaveBeenCalled())
    expect(JSON.stringify(registrarVenta.mock.calls[0][0])).not.toMatch(/precio/i)
    expect(registrarVenta.mock.calls[0][0].lineas).toEqual([{ productoId: 30, cantidad: 1 }])
  })

  it('un producto con más cantidad que existencia no se cobra hasta pedir el encargo', async () => {
    await abrir()
    await agregar(PINTURA)
    await userEvent.clear(screen.getByLabelText('Cantidad de Pintura blanca'))
    await userEvent.type(screen.getByLabelText('Cantidad de Pintura blanca'), '5')
    expect(screen.getByText(/no hay existencia suficiente de «pintura blanca»/i)).toBeInTheDocument()
    expect(cobrar()).toBeDisabled()
  })
})

describe('caja: cliente y domicilio', () => {
  it('consumidorFinalPorDefecto', async () => {
    await abrir()
    expect(screen.getByRole('button', { name: 'Consumidor final' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('domicilioPideSusDatos y no se cobra hasta completarlos', async () => {
    await abrir()
    await agregar()
    await userEvent.click(screen.getByLabelText('Domicilio'))
    expect(screen.getByLabelText(/quién recibe/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/¿paga contra entrega\?/i)).toBeInTheDocument()
    expect(screen.getAllByText(/necesita un cliente identificado/i).length).toBeGreaterThan(0)
    await pagarTodo()
    expect(cobrar()).toBeDisabled()
  })

  it('saldoAFavorSoloConClienteIdentificadoConSaldo', async () => {
    saldoDeCliente.mockResolvedValue({ saldo: 5000 })
    await abrir()
    await agregar()
    const medio = () => screen.getByLabelText(/medio de pago/i)
    expect(within(medio()).queryByRole('option', { name: /saldo a favor/i })).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: /identificar cliente/i }))
    await userEvent.type(screen.getByLabelText(/^identificación/i), ANA.identificacion)
    await userEvent.click(screen.getByRole('button', { name: /buscar cliente/i }))
    await userEvent.click(await screen.findByRole('button', { name: /usar este cliente/i }))
    expect(saldoDeCliente).toHaveBeenCalledWith(7)
    expect(await within(medio()).findByRole('option', { name: /saldo a favor/i })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Consumidor final' }))
    await waitFor(() => expect(within(medio()).queryByRole('option', { name: /saldo a favor/i })).toBeNull())
  })

  it('un cliente con saldo cero no ve la opción', async () => {
    await abrir()
    await agregar()
    await userEvent.click(screen.getByRole('button', { name: /identificar cliente/i }))
    await userEvent.type(screen.getByLabelText(/^identificación/i), ANA.identificacion)
    await userEvent.click(screen.getByRole('button', { name: /buscar cliente/i }))
    await userEvent.click(await screen.findByRole('button', { name: /usar este cliente/i }))
    await waitFor(() => expect(saldoDeCliente).toHaveBeenCalled())
    expect(within(screen.getByLabelText(/medio de pago/i)).queryByRole('option', { name: /saldo a favor/i })).toBeNull()
  })

  it('una venta a domicilio contra entrega se registra sin pagos', async () => {
    await abrir()
    await agregar()
    await userEvent.click(screen.getByRole('button', { name: /identificar cliente/i }))
    await userEvent.type(screen.getByLabelText(/^identificación/i), ANA.identificacion)
    await userEvent.click(screen.getByRole('button', { name: /buscar cliente/i }))
    await userEvent.click(await screen.findByRole('button', { name: /usar este cliente/i }))
    await userEvent.click(screen.getByLabelText('Domicilio'))
    await userEvent.type(screen.getByLabelText(/quién recibe/i), 'Ana')
    await userEvent.click(screen.getByLabelText(/¿paga contra entrega\?/i))
    expect(screen.queryByLabelText(/medio de pago/i)).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /registrar para cobrar en la entrega/i }))
    await waitFor(() => expect(registrarVenta).toHaveBeenCalled())
    const s = registrarVenta.mock.calls[0][0]
    expect(s.clienteId).toBe(7)
    expect(s.pagos).toEqual([])
    expect(s.domicilio).toEqual({
      direccion: 'Calle 1 # 2-3', ciudad: 'Cali', telefono: '3001112233', recibe: 'Ana', contraEntrega: true,
    })
  })
})

describe('caja: cobro', () => {
  it('cambioEnEfectivo', async () => {
    await abrir()
    await agregar()
    await pagarTodo({ recibido: 50000 })
    expect(screen.getByText(/^cambio/i)).toHaveTextContent(pesos(38100))
  })

  it('cobra con la clave de idempotencia, el cliente nulo (Consumidor final) y los pagos', async () => {
    await abrir()
    await agregar()
    await pagarTodo({ recibido: 20000 })
    await userEvent.click(cobrar())
    await waitFor(() => expect(registrarVenta).toHaveBeenCalledTimes(1))
    const s = registrarVenta.mock.calls[0][0]
    expect(s.claveIdempotencia).toMatch(/^[0-9a-f-]{36}$/)
    expect(s.clienteId).toBeUndefined()
    expect(s.pagos).toEqual([{ medioId: 1, monto: 11900, recibido: 20000 }])
    expect(s.domicilio).toBeUndefined()
  })

  it('una venta con total cero no se permite', async () => {
    await abrir()
    await agregar()
    await userEvent.type(screen.getByLabelText('Descuento de Tornillo M6'), '100')
    expect(screen.getByText(/una venta no puede quedar en cero/i)).toBeInTheDocument()
    expect(cobrar()).toBeDisabled()
  })

  it('un descuento mayor que el valor se dice y no se cobra', async () => {
    await abrir()
    await agregar()
    await userEvent.type(screen.getByLabelText('Descuento de Tornillo M6'), '101')
    expect(screen.getByText(/no puede pasar de 100/i)).toBeInTheDocument()
    expect(cobrar()).toBeDisabled()
  })

  it('un descuento con más de 2 decimales se dice y no se cobra', async () => {
    await abrir()
    await agregar()
    await userEvent.type(screen.getByLabelText('Descuento de Tornillo M6'), '7,555')
    expect(screen.getByText(/hasta 2 decimales/i)).toBeInTheDocument()
    expect(cobrar()).toBeDisabled()
  })

  it('al registrar muestra el recibo con el importe del servidor, Imprimir y Descargar PDF', async () => {
    window.print = vi.fn()
    await abrir()
    await agregar()
    await pagarTodo({ recibido: 20000 })
    await userEvent.click(cobrar())
    expect(await screen.findByText(/venta registrada/i)).toBeInTheDocument()
    expect(screen.getByText(/V-0001/)).toBeInTheDocument()
    expect(await screen.findByText('Black Steel Parts')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /^imprimir$/i }))
    expect(window.print).toHaveBeenCalledTimes(1)
    expect(document.head.querySelector('style[data-impresion-recibo]')?.textContent).toMatch(/@page\s*\{\s*size:\s*80mm\s+\d/)
    window.dispatchEvent(new Event('afterprint'))
    await userEvent.click(screen.getByRole('button', { name: /descargar pdf/i }))
    expect(window.print).toHaveBeenCalledTimes(2)
    // Nueva venta: carrito vacío y el foco en el buscador.
    await userEvent.click(screen.getByRole('button', { name: /nueva venta/i }))
    expect(screen.queryByLabelText('Cantidad de Tornillo M6')).not.toBeInTheDocument()
    await waitFor(() => expect(buscador()).toHaveFocus())
  })
})

describe('caja: autorización con PIN', () => {
  async function conDescuento() {
    await agregar()
    await userEvent.type(screen.getByLabelText('Descuento de Tornillo M6'), '10')
    expect(total()).toHaveTextContent(pesos(10710))
    await pagarTodo()
  }
  async function autorizar(pin = '1234') {
    const dialogo = await screen.findByRole('dialog', { name: /autorización del administrador/i })
    await screen.findByRole('option', { name: 'Laura Pérez' })
    await userEvent.selectOptions(within(dialogo).getByLabelText(/^administrador$/i), 'Laura Pérez')
    await userEvent.type(within(dialogo).getByLabelText(/^pin$/i), pin)
    await userEvent.click(within(dialogo).getByRole('button', { name: /autorizar y cobrar/i }))
  }

  it('vendedorConDescuentoAbrePin', async () => {
    entrar('VENDEDOR')
    await abrir()
    await conDescuento()
    await userEvent.click(cobrar())
    const dialogo = await screen.findByRole('dialog', { name: /autorización del administrador/i })
    expect(within(dialogo).getByText(/descuento/i, { selector: 'li' })).toBeInTheDocument()
    expect(registrarVenta).not.toHaveBeenCalled()
    await autorizar()
    await waitFor(() => expect(registrarVenta).toHaveBeenCalledTimes(1))
    const s = registrarVenta.mock.calls[0][0]
    expect(s.autorizacion).toEqual({ administradorId: 3, pin: '1234' })
    expect(s.lineas[0].descuento).toEqual({ tipo: 'PORCENTAJE', valor: 10 })
    expect(await screen.findByText(/venta registrada/i)).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('vendedorConProductoNuevoAbreUnSoloPin: un diálogo y un campo de PIN para todo', async () => {
    entrar('VENDEDOR')
    await abrir()
    await userEvent.type(buscador(), '7705550001{Enter}')
    await userEvent.click(await screen.findByRole('button', { name: /crear producto/i }))
    const nuevo = await screen.findByRole('dialog', { name: /producto nuevo/i })
    await userEvent.type(within(nuevo).getByLabelText(/^nombre/i), 'Llave de paso')
    await userEvent.type(within(nuevo).getByLabelText(/precio de venta/i), '5000')
    await userEvent.type(within(nuevo).getByLabelText(/fecha de entrega/i), sumarDias(hoyISO(), 5))
    await userEvent.type(within(nuevo).getByLabelText(/motivo del encargo/i), 'Llega con el proveedor')
    await userEvent.click(within(nuevo).getByRole('button', { name: /agregar al carrito/i }))
    await pagarTodo()
    await userEvent.click(cobrar())
    expect(await screen.findAllByRole('dialog')).toHaveLength(1)
    expect(screen.getAllByLabelText(/^pin$/i)).toHaveLength(1)
    const lista = screen.getByRole('list', { name: /se va a autorizar/i })
    expect(within(lista).getAllByRole('listitem').length).toBeGreaterThanOrEqual(2)
    await autorizar()
    await waitFor(() => expect(registrarVenta).toHaveBeenCalledTimes(1))
  })

  it('adminConDescuentoNoPidePin', async () => {
    entrar('ADMINISTRADOR')
    await abrir()
    await conDescuento()
    await userEvent.click(cobrar())
    await waitFor(() => expect(registrarVenta).toHaveBeenCalledTimes(1))
    expect(screen.queryByRole('dialog', { name: /autorización/i })).not.toBeInTheDocument()
    expect(registrarVenta.mock.calls[0][0].autorizacion).toBeUndefined()
  })

  it('un vendedor sin nada que autorizar tampoco ve el PIN', async () => {
    entrar('VENDEDOR')
    await abrir()
    await agregar()
    await pagarTodo()
    await userEvent.click(cobrar())
    await waitFor(() => expect(registrarVenta).toHaveBeenCalledTimes(1))
    expect(registrarVenta.mock.calls[0][0].autorizacion).toBeUndefined()
  })

  it('pinErradoMuestraErrorSinCerrar y se puede volver a intentar con la misma clave', async () => {
    entrar('VENDEDOR')
    registrarVenta.mockRejectedValueOnce(errorApi(403, 'El PIN no es correcto.'))
    await abrir()
    await conDescuento()
    await userEvent.click(cobrar())
    await autorizar('0000')
    const dialogo = screen.getByRole('dialog', { name: /autorización del administrador/i })
    expect(await within(dialogo).findByRole('alert')).toHaveTextContent('El PIN no es correcto.')
    await userEvent.type(within(dialogo).getByLabelText(/^pin$/i), '1234')
    await userEvent.click(within(dialogo).getByRole('button', { name: /autorizar y cobrar/i }))
    await waitFor(() => expect(registrarVenta).toHaveBeenCalledTimes(2))
    expect(registrarVenta.mock.calls[1][0].claveIdempotencia).toBe(registrarVenta.mock.calls[0][0].claveIdempotencia)
    expect(await screen.findByText(/venta registrada/i)).toBeInTheDocument()
  })
})

describe('caja: reintentos y conexión', () => {
  it('reintentoUsaLaMismaClave: tras un fallo de red el reintento manda el mismo cuerpo con la misma clave, y la venta siguiente usa otra', async () => {
    registrarVenta.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await abrir()
    await agregar()
    await pagarTodo()
    await userEvent.click(cobrar())
    expect(await screen.findByText(/no sabemos si se registró\. reintenta: no se cobrará dos veces/i)).toBeInTheDocument()
    expect(document.body).not.toHaveTextContent(/no se cobró nada/i)
    expect(cobrar()).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: /reintentar el cobro/i }))
    await waitFor(() => expect(registrarVenta).toHaveBeenCalledTimes(2))
    expect(registrarVenta.mock.calls[1][0]).toEqual(registrarVenta.mock.calls[0][0])
    expect(registrarVenta.mock.calls[1][0].pagos).toHaveLength(1)
    expect(await screen.findByText(/venta registrada/i)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /nueva venta/i }))
    await agregar()
    await pagarTodo()
    await userEvent.click(cobrar())
    await waitFor(() => expect(registrarVenta).toHaveBeenCalledTimes(3))
    expect(registrarVenta.mock.calls[2][0].claveIdempotencia).not.toBe(registrarVenta.mock.calls[0][0].claveIdempotencia)
  })

  it.each([502, 504, 500])('un %i también deja la venta en estado ambiguo', async (estado) => {
    registrarVenta.mockRejectedValueOnce(errorApi(estado, 'Falló'))
    await abrir()
    await agregar()
    await pagarTodo()
    await userEvent.click(cobrar())
    expect(await screen.findByText(/no sabemos si se registró/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /reintentar el cobro/i })).toBeEnabled()
  })

  it('con la venta ambigua el carrito, el cliente y los pagos quedan congelados', async () => {
    registrarVenta.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await abrir()
    await agregar()
    await pagarTodo()
    await userEvent.click(cobrar())
    await screen.findByText(/no sabemos si se registró/i)
    expect(screen.getByLabelText('Cantidad de Tornillo M6')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Quitar Tornillo M6' })).toBeDisabled()
    expect(buscador()).toBeDisabled()
    expect(screen.getByRole('button', { name: /identificar cliente/i })).toBeDisabled()
    expect(screen.getByRole('button', { name: /quitar pago de efectivo/i })).toBeDisabled()
  })

  it('abandonar una venta ambigua pide confirmar el riesgo, y solo entonces empieza otra con clave nueva', async () => {
    registrarVenta.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await abrir()
    await agregar()
    await pagarTodo()
    await userEvent.click(cobrar())
    await screen.findByText(/no sabemos si se registró/i)
    await userEvent.click(screen.getByRole('button', { name: /abandonar esta venta/i }))
    expect(screen.getByText(/podría quedar cobrado/i)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /seguir reintentando/i }))
    expect(screen.getByLabelText('Cantidad de Tornillo M6')).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: /abandonar esta venta/i }))
    await userEvent.click(screen.getByRole('button', { name: /sí, abandonar/i }))
    expect(screen.queryByLabelText('Cantidad de Tornillo M6')).not.toBeInTheDocument()
    await agregar()
    await pagarTodo()
    await userEvent.click(cobrar())
    await waitFor(() => expect(registrarVenta).toHaveBeenCalledTimes(2))
    expect(registrarVenta.mock.calls[1][0].claveIdempotencia).not.toBe(registrarVenta.mock.calls[0][0].claveIdempotencia)
  })

  it('abandonar no se puede confirmar mientras un reintento está en vuelo: la clave y el cuerpo guardado se conservan', async () => {
    registrarVenta.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await abrir()
    await agregar()
    await pagarTodo()
    await userEvent.click(cobrar())
    await screen.findByText(/no sabemos si se registró/i)
    await userEvent.click(screen.getByRole('button', { name: /abandonar esta venta/i }))
    let rechazar
    registrarVenta.mockImplementationOnce(() => new Promise((_resolver, rej) => { rechazar = rej }))
    await userEvent.click(screen.getByRole('button', { name: /reintentar el cobro/i }))
    await waitFor(() => expect(registrarVenta).toHaveBeenCalledTimes(2))
    const confirmar = screen.getByRole('button', { name: /sí, abandonar/i })
    expect(confirmar).toBeDisabled()
    await userEvent.click(confirmar)
    expect(screen.getByLabelText('Cantidad de Tornillo M6')).toBeInTheDocument()
    rechazar(new TypeError('Failed to fetch'))
    await screen.findByText(/no sabemos si se registró/i)
    await userEvent.click(screen.getByRole('button', { name: /reintentar el cobro/i }))
    await waitFor(() => expect(registrarVenta).toHaveBeenCalledTimes(3))
    expect(registrarVenta.mock.calls[2][0]).toEqual(registrarVenta.mock.calls[0][0])
    expect(registrarVenta.mock.calls[1][0].claveIdempotencia).toBe(registrarVenta.mock.calls[0][0].claveIdempotencia)
  })

  it('reintentar respeta la conexión: sin red no se puede', async () => {
    registrarVenta.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    const enLinea = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true)
    await abrir()
    await agregar()
    await pagarTodo()
    await userEvent.click(cobrar())
    await screen.findByText(/no sabemos si se registró/i)
    enLinea.mockReturnValue(false)
    window.dispatchEvent(new Event('offline'))
    await waitFor(() => expect(screen.getByRole('button', { name: /reintentar el cobro/i })).toBeDisabled())
  })

  it('un vendedor que reintenta una venta con descuento vuelve a escribir el PIN, con el mismo cuerpo y la misma clave', async () => {
    entrar('VENDEDOR')
    registrarVenta.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await abrir()
    await agregar()
    await userEvent.type(screen.getByLabelText('Descuento de Tornillo M6'), '10')
    await pagarTodo()
    await userEvent.click(cobrar())
    let dialogo = await screen.findByRole('dialog', { name: /autorización del administrador/i })
    await screen.findByRole('option', { name: 'Laura Pérez' })
    await userEvent.selectOptions(within(dialogo).getByLabelText(/^administrador$/i), 'Laura Pérez')
    await userEvent.type(within(dialogo).getByLabelText(/^pin$/i), '1234')
    await userEvent.click(within(dialogo).getByRole('button', { name: /autorizar y cobrar/i }))
    expect(await within(dialogo).findByText(/no sabemos si se registró/i)).toBeInTheDocument()
    expect(within(dialogo).getByLabelText(/^pin$/i)).toHaveValue('')
    await userEvent.click(within(dialogo).getByRole('button', { name: /cancelar/i }))
    await userEvent.click(screen.getByRole('button', { name: /reintentar el cobro/i }))
    dialogo = await screen.findByRole('dialog', { name: /autorización del administrador/i })
    await screen.findByRole('option', { name: 'Laura Pérez' })
    await userEvent.selectOptions(within(dialogo).getByLabelText(/^administrador$/i), 'Laura Pérez')
    await userEvent.type(within(dialogo).getByLabelText(/^pin$/i), '4321')
    await userEvent.click(within(dialogo).getByRole('button', { name: /autorizar y cobrar/i }))
    await waitFor(() => expect(registrarVenta).toHaveBeenCalledTimes(2))
    const [primera, segunda] = registrarVenta.mock.calls.map((c) => c[0])
    expect(segunda.autorizacion).toEqual({ administradorId: 3, pin: '4321' })
    expect({ ...segunda, autorizacion: undefined }).toEqual({ ...primera, autorizacion: undefined })
  })

  it('el 409 de clave en uso se explica sin hablar de claves y ofrece empezar otra venta', async () => {
    registrarVenta.mockRejectedValueOnce(errorApi(409, 'La clave ya se usó con otra carga.', CLAVE_EN_USO))
    await abrir()
    await agregar()
    await pagarTodo()
    await userEvent.click(cobrar())
    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('Esta venta ya se registró con otros datos. Revisa las ventas del turno.')
    expect(document.body).not.toHaveTextContent(/clave|idempotencia/i)
    expect(screen.queryByRole('button', { name: /reintentar el cobro/i })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /empezar otra venta/i }))
    expect(screen.queryByLabelText('Cantidad de Tornillo M6')).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('el contraejemplo de la revisión: dos llaves con distinto IVA y 1 peso de descuento se cobran sin un peso de diferencia', async () => {
    await abrir()
    await agregar(LLAVE_A)
    await agregar(LLAVE_B)
    await userEvent.type(screen.getByLabelText('Descuento de toda la compra'), '1')
    await userEvent.selectOptions(screen.getByLabelText('Tipo de descuento de toda la compra'), 'PESOS')
    expect(total()).toHaveTextContent(pesos(2195))
    await pagarTodo()
    await userEvent.click(cobrar())
    // El servidor simulado rechaza (400) si los pagos no suman exactamente su total.
    expect(await screen.findByText(/venta registrada/i)).toBeInTheDocument()
  })

  it('el recibo empareja cada nombre con sus importes aunque el servidor devuelva las líneas ordenadas por producto', async () => {
    await abrir()
    await agregar(PINTURA)
    await agregar(TORNILLO)
    await pagarTodo()
    await userEvent.click(cobrar())
    await screen.findByText(/venta registrada/i)
    const recibo = screen.getByRole('article', { name: /recibo de venta/i })
    const filaPintura = within(recibo).getByText('Pintura blanca').closest('li')
    const filaTornillo = within(recibo).getByText('Tornillo M6').closest('li')
    expect(filaPintura).toHaveTextContent(pesos(23800))
    expect(filaTornillo).toHaveTextContent(pesos(11900))
  })

  it('tras registrar, el foco va al encabezado del recibo, que se anuncia', async () => {
    await abrir()
    await agregar()
    await pagarTodo()
    await userEvent.click(cobrar())
    const titulo = await screen.findByRole('heading', { name: /venta registrada/i })
    await waitFor(() => expect(titulo).toHaveFocus())
  })

  it('vaciar el carrito tras una venta confirmada renueva la clave', async () => {
    await abrir()
    await agregar()
    await pagarTodo()
    await userEvent.click(cobrar())
    await screen.findByText(/venta registrada/i)
    await userEvent.click(screen.getByRole('button', { name: /nueva venta/i }))
    await agregar()
    await pagarTodo()
    await userEvent.click(cobrar())
    await waitFor(() => expect(registrarVenta).toHaveBeenCalledTimes(2))
    expect(registrarVenta.mock.calls[1][0].claveIdempotencia).not.toBe(registrarVenta.mock.calls[0][0].claveIdempotencia)
  })

  it('al empezar otra venta no queda ningún dato del cliente anterior', async () => {
    await abrir()
    await agregar()
    await userEvent.click(screen.getByRole('button', { name: /identificar cliente/i }))
    await userEvent.type(screen.getByLabelText(/^identificación/i), ANA.identificacion)
    await userEvent.click(screen.getByRole('button', { name: /buscar cliente/i }))
    await screen.findByText('Ana Pérez')
    await userEvent.click(screen.getByRole('button', { name: /usar este cliente/i }))
    await pagarTodo()
    await userEvent.click(cobrar())
    await screen.findByText(/venta registrada/i)
    await userEvent.click(screen.getByRole('button', { name: /nueva venta/i }))
    expect(screen.queryByText('Ana Pérez')).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/^identificación/i)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Consumidor final' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('al cambiar de cliente se quitan los pagos con saldo a favor', async () => {
    saldoDeCliente.mockResolvedValue({ saldo: 20000 })
    await abrir()
    await agregar()
    await userEvent.click(screen.getByRole('button', { name: /identificar cliente/i }))
    await userEvent.type(screen.getByLabelText(/^identificación/i), ANA.identificacion)
    await userEvent.click(screen.getByRole('button', { name: /buscar cliente/i }))
    await userEvent.click(await screen.findByRole('button', { name: /usar este cliente/i }))
    await waitFor(() => within(screen.getByLabelText(/medio de pago/i)).getByRole('option', { name: /saldo a favor/i }))
    await userEvent.selectOptions(screen.getByLabelText(/medio de pago/i), 'Saldo a favor')
    await userEvent.click(screen.getByRole('button', { name: /agregar pago/i }))
    expect(screen.getByRole('button', { name: /quitar pago de saldo a favor/i })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Consumidor final' }))
    await waitFor(() => expect(screen.queryByRole('button', { name: /quitar pago de saldo a favor/i })).not.toBeInTheDocument())
  })

  it('sinConexionNoCobra: con el navegador sin red avisa y no deja cobrar', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    await abrir()
    await agregar()
    await pagarTodo()
    expect(screen.getByText(/sin conexión\. no podrás guardar cambios/i)).toBeInTheDocument()
    expect(cobrar()).toBeDisabled()
    expect(registrarVenta).not.toHaveBeenCalled()
  })

  it('un error del servidor que no es de red se muestra y deja volver a intentar', async () => {
    registrarVenta.mockRejectedValueOnce(errorApi(409, 'No hay existencia suficiente de Tornillo M6.'))
    await abrir()
    await agregar()
    await pagarTodo()
    await userEvent.click(cobrar())
    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('No hay existencia suficiente de Tornillo M6.')
    expect(alerta).not.toHaveTextContent(/otra vez/i)
    expect(cobrar()).toBeEnabled()
  })
})

describe('caja: mientras el envío está en vuelo', () => {
  /** Un envío que no responde hasta que la prueba lo decida. */
  function envioPendiente() {
    const control = {}
    registrarVenta.mockImplementationOnce(() => new Promise((resolver, rechazar) => {
      control.resolver = resolver
      control.rechazar = rechazar
    }))
    return control
  }
  async function cobrarYEsperar() {
    await abrir()
    await agregar()
    await pagarTodo()
    const control = envioPendiente()
    await userEvent.click(cobrar())
    await waitFor(() => expect(registrarVenta).toHaveBeenCalledTimes(1))
    return control
  }

  it('el carrito, el cliente, los descuentos y los pagos quedan quietos: quitar o vaciar no hace nada', async () => {
    const control = await cobrarYEsperar()
    expect(screen.getByLabelText('Cantidad de Tornillo M6')).toBeDisabled()
    expect(screen.getByLabelText('Descuento de Tornillo M6')).toBeDisabled()
    expect(screen.getByLabelText('Descuento de toda la compra')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Quitar Tornillo M6' })).toBeDisabled()
    expect(screen.getByRole('button', { name: /identificar cliente/i })).toBeDisabled()
    expect(screen.getByRole('button', { name: /quitar pago de efectivo/i })).toBeDisabled()
    expect(buscador()).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Quitar Tornillo M6' }))
    await userEvent.type(screen.getByLabelText('Cantidad de Tornillo M6'), '5')
    expect(screen.getByLabelText('Cantidad de Tornillo M6')).toHaveValue('1')
    expect(total()).toHaveTextContent(pesos(11900))
    control.resolver(ventaDelServidor(registrarVenta.mock.calls[0][0]))
    expect(await screen.findByText(/venta registrada/i)).toBeInTheDocument()
  })

  it('si el envío en vuelo falla por red, «Reintentar» está habilitado y manda el mismo cuerpo con la misma clave', async () => {
    const control = await cobrarYEsperar()
    await userEvent.type(screen.getByLabelText('Cantidad de Tornillo M6'), '5')
    control.rechazar(new TypeError('Failed to fetch'))
    await screen.findByText(/no sabemos si se registró/i)
    expect(screen.getByLabelText('Cantidad de Tornillo M6')).toHaveValue('1')
    const reintentar = screen.getByRole('button', { name: /reintentar el cobro/i })
    expect(reintentar).toBeEnabled()
    await userEvent.click(reintentar)
    await waitFor(() => expect(registrarVenta).toHaveBeenCalledTimes(2))
    expect(registrarVenta.mock.calls[1][0]).toEqual(registrarVenta.mock.calls[0][0])
    expect(await screen.findByText(/venta registrada/i)).toBeInTheDocument()
  })

  it('un código leído justo antes de «Cobrar» entra al carrito antes del envío: mientras la cola no está vacía no se cobra', async () => {
    await abrir()
    await agregar()
    await pagarTodo()
    let soltar
    productoPorCodigo.mockImplementationOnce(() => new Promise((resolver) => { soltar = () => resolver(PINTURA) }))
    await userEvent.type(buscador(), `${PINTURA.codigoBarras}{Enter}`)
    expect(cobrar()).toBeDisabled()
    soltar()
    await screen.findByLabelText('Cantidad de Pintura blanca')
    // La línea nueva cambió el total: los pagos se borraron y hay que volver a cuadrar.
    expect(cobrar()).toBeDisabled()
    expect(registrarVenta).not.toHaveBeenCalled()
    await pagarTodo()
    expect(cobrar()).toBeEnabled()
  })
})

describe('caja: lo que no se ve', () => {
  it('noHayCostoEnPantalla: aunque el servidor lo mande, ni el vendedor ni el administrador lo ven', async () => {
    for (const rol of ['VENDEDOR', 'ADMINISTRADOR']) {
      entrar(rol)
      const conCosto = { ...TORNILLO, costo: 7777, margen: 31, utilidad: 2223 }
      const { productoPorCodigo } = await import('../../servicios/api/caja.js')
      productoPorCodigo.mockResolvedValue(conCosto)
      const { unmount } = render(<MemoryRouter><Caja /></MemoryRouter>)
      await screen.findByLabelText(/buscar producto/i)
      await userEvent.type(buscador(), 'x{Enter}')
      await screen.findByLabelText('Cantidad de Tornillo M6')
      expect(document.body).not.toHaveTextContent(/costo|margen|utilidad/i)
      expect(document.body).not.toHaveTextContent(/7\.777|2\.223/)
      unmount()
    }
  })

  it('no guarda nada de la venta en el almacenamiento del navegador', async () => {
    await abrir()
    await agregar()
    await pagarTodo()
    const sesion = JSON.stringify({ ...sessionStorage })
    expect(sesion).not.toMatch(/Tornillo|claveIdempotencia/)
    expect(Object.keys(localStorage)).toEqual([])
  })
})

describe('caja: el aviso de abandonar no reaparece solo (T12-P1)', () => {
  it('tras un error definitivo del reintento, un nuevo resultado ambiguo no trae de vuelta la confirmación', async () => {
    registrarVenta.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await abrir()
    await agregar()
    await pagarTodo()
    await userEvent.click(cobrar())
    await screen.findByText(/no sabemos si se registró/i)
    await userEvent.click(screen.getByRole('button', { name: /abandonar esta venta/i }))
    expect(screen.getByText(/podría quedar cobrado/i)).toBeInTheDocument()

    registrarVenta.mockRejectedValueOnce(errorApi(400, 'Los datos de la venta no son válidos.'))
    await userEvent.click(screen.getByRole('button', { name: /reintentar el cobro/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Los datos de la venta no son válidos.')
    expect(screen.queryByText(/podría quedar cobrado/i)).not.toBeInTheDocument()

    registrarVenta.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await userEvent.click(cobrar())
    await screen.findByText(/no sabemos si se registró/i)
    expect(screen.getByRole('button', { name: /abandonar esta venta/i })).toBeInTheDocument()
    expect(screen.queryByText(/podría quedar cobrado/i)).not.toBeInTheDocument()
  })
})

describe('caja: estructura de la pantalla', () => {
  it('lleva un encabezado propio con el título «Caja» como h1', async () => {
    await abrir()
    expect(screen.getByRole('heading', { level: 1, name: 'Caja' })).toBeInTheDocument()
    expect(screen.getByText('Ventas')).toBeInTheDocument()
  })

  it('las secciones Cliente y Productos son encabezados con su región; el buscador y el carrito viven en Productos', async () => {
    await abrir()
    expect(screen.getByRole('heading', { level: 2, name: /^cliente$/i })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: /^productos$/i })).toBeInTheDocument()
    const productos = screen.getByRole('region', { name: /^productos$/i })
    expect(within(productos).getByLabelText(/buscar producto/i)).toBeInTheDocument()
    await agregar()
    expect(within(screen.getByRole('region', { name: /^productos$/i })).getByLabelText('Cantidad de Tornillo M6')).toBeInTheDocument()
    const cliente = screen.getByRole('region', { name: /^cliente$/i })
    expect(within(cliente).getByRole('button', { name: 'Consumidor final' })).toBeInTheDocument()
  })

  it('el resumen tiene nombre, muestra el total y contiene «Cobrar»', async () => {
    await abrir()
    await agregar()
    const resumen = screen.getByRole('region', { name: /resumen de la venta/i })
    expect(within(resumen).getByTestId('total-a-cobrar')).toHaveTextContent(pesos(11900))
    expect(within(resumen).getByRole('button', { name: /^cobrar$/i })).toBeInTheDocument()
    expect(within(resumen).getByText(/^IVA$/)).toBeInTheDocument()
    expect(within(resumen).getByLabelText(/medio de pago/i)).toBeInTheDocument()
  })
})


describe('caja: el medio del saldo a favor sale del saldo del cliente', () => {
  it('el medio de saldo a favor usa el medioId que responde el saldo, aunque la lista de medios no lo traiga', async () => {
    vi.mocked(mediosDePago).mockResolvedValue(MEDIOS.filter((m) => m.tipo !== 'SALDO_A_FAVOR'))
    saldoDeCliente.mockResolvedValue({ saldo: 5000, medioId: 41 })
    await abrir()
    await agregar()
    await userEvent.click(screen.getByRole('button', { name: /identificar cliente/i }))
    await userEvent.type(screen.getByLabelText(/^identificación/i), ANA.identificacion)
    await userEvent.click(screen.getByRole('button', { name: /buscar cliente/i }))
    await userEvent.click(await screen.findByRole('button', { name: /usar este cliente/i }))
    const opcion = await within(screen.getByLabelText(/medio de pago/i)).findByRole('option', { name: /saldo a favor/i })
    expect(opcion).toHaveValue('41')
    await userEvent.selectOptions(screen.getByLabelText(/medio de pago/i), '41')
    await userEvent.clear(screen.getByLabelText(/^monto$/i))
    await userEvent.type(screen.getByLabelText(/^monto$/i), '5000')
    await userEvent.click(screen.getByRole('button', { name: /agregar pago/i }))
    await userEvent.selectOptions(screen.getByLabelText(/medio de pago/i), '1')
    await userEvent.click(screen.getByRole('button', { name: /agregar pago/i }))
    await userEvent.click(cobrar())
    await waitFor(() => expect(registrarVenta).toHaveBeenCalled())
    expect(registrarVenta.mock.calls[0][0].pagos[0]).toEqual({ medioId: 41, monto: 5000 })
  })
})

describe('caja: no se cobra con una búsqueda por nombre a medias', () => {
  it('Cobrar queda apagado mientras la búsqueda por nombre de un código desconocido sigue en vuelo', async () => {
    await abrir()
    await agregar()
    await pagarTodo()
    expect(cobrar()).toBeEnabled()
    let soltar
    vi.mocked(productosPorTexto).mockImplementationOnce(() => new Promise((resolver) => { soltar = resolver }))
    await userEvent.type(buscador(), 'celular{Enter}')
    await waitFor(() => expect(productosPorTexto).toHaveBeenCalledWith('celular'))
    expect(cobrar()).toBeDisabled()
    await act(async () => { soltar([]) })
    await waitFor(() => expect(cobrar()).toBeEnabled())
  })
})

describe('caja: enlaces al cierre y a los cierres propios', () => {
  it('con un turno abierto ofrece cerrar el turno y ver mis cierres', async () => {
    await abrir()
    expect(screen.getByRole('link', { name: /cerrar turno/i })).toHaveAttribute('href', '/caja/cierre')
    expect(screen.getByRole('link', { name: /mis cierres/i })).toHaveAttribute('href', '/caja/mis-cierres')
  })

  it('sin turno abierto no ofrece cerrarlo', async () => {
    turnoActual.mockResolvedValue(null)
    render(<MemoryRouter><Caja /></MemoryRouter>)
    await screen.findByRole('heading', { name: /abrir turno/i })
    expect(screen.queryByRole('link', { name: /cerrar turno/i })).not.toBeInTheDocument()
  })
})

describe('caja: una venta ambigua sobrevive a recargar la pestaña (M12-R1)', () => {
  async function dejarAmbigua() {
    registrarVenta.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    const vista = render(<MemoryRouter><Caja /></MemoryRouter>)
    await screen.findByLabelText(/buscar producto/i)
    await agregar()
    await pagarTodo()
    await userEvent.click(cobrar())
    await screen.findByText(/no sabemos si se registró/i)
    vista.unmount()
  }

  it('al recargar sigue la venta sin confirmar, y reintentar manda el mismo cuerpo y la misma clave', async () => {
    await dejarAmbigua()
    const primero = registrarVenta.mock.calls[0][0]
    expect(sessionStorage.getItem(CLAVE_VENTA_PENDIENTE)).not.toBeNull()

    render(<MemoryRouter><Caja /></MemoryRouter>)
    expect(await screen.findByText(/no sabemos si se registró/i)).toBeInTheDocument()
    expect(screen.queryByLabelText('Cantidad de Tornillo M6')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /reintentar el cobro/i }))
    expect(await screen.findByRole('heading', { name: /venta registrada/i })).toBeInTheDocument()
    expect(registrarVenta.mock.calls[1][0]).toEqual(primero)
    expect(screen.getByText(/V-0001/)).toBeInTheDocument()
    expect(sessionStorage.getItem(CLAVE_VENTA_PENDIENTE)).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: /nueva venta/i }))
    expect(await screen.findByLabelText(/buscar producto/i)).toBeInTheDocument()
  })

  it('abandonarla, con confirmación, borra lo guardado y deja vender', async () => {
    await dejarAmbigua()
    render(<MemoryRouter><Caja /></MemoryRouter>)
    await screen.findByText(/no sabemos si se registró/i)
    await userEvent.click(screen.getByRole('button', { name: /abandonar esta venta/i }))
    await userEvent.click(screen.getByRole('button', { name: /sí, abandonar/i }))
    expect(sessionStorage.getItem(CLAVE_VENTA_PENDIENTE)).toBeNull()
    expect(screen.queryByText(/no sabemos si se registró/i)).not.toBeInTheDocument()
    await agregar()
  })

  it('un vendedor con una venta restaurada que lleva descuento debe pedir el PIN otra vez', async () => {
    entrar('VENDEDOR')
    sessionStorage.setItem(CLAVE_VENTA_PENDIENTE, JSON.stringify({
      clave: 'k-1',
      cuerpo: { claveIdempotencia: 'k-1', lineas: [{ cantidad: 1, productoId: 30, descuento: { tipo: 'PORCENTAJE', valor: 10 } }], pagos: [] },
      motivos: ['Descuento en la venta'],
    }))
    render(<MemoryRouter><Caja /></MemoryRouter>)
    await screen.findByText(/no sabemos si se registró/i)
    await userEvent.click(screen.getByRole('button', { name: /reintentar el cobro/i }))
    const dialogo = await screen.findByRole('dialog', { name: /autorización del administrador/i })
    expect(within(dialogo).getByText('Descuento en la venta')).toBeInTheDocument()
    expect(registrarVenta).not.toHaveBeenCalled()
    await userEvent.selectOptions(within(dialogo).getByLabelText(/administrador/i), '3')
    await userEvent.type(within(dialogo).getByLabelText(/^pin$/i), '4821')
    await userEvent.click(within(dialogo).getByRole('button', { name: /autorizar y cobrar/i }))
    await waitFor(() => expect(registrarVenta).toHaveBeenCalled())
    expect(registrarVenta.mock.calls[0][0].autorizacion).toEqual({ administradorId: 3, pin: '4821' })
    expect(registrarVenta.mock.calls[0][0].claveIdempotencia).toBe('k-1')
  })
})

describe('caja: una venta restaurada que da un 403 que no es de PIN', () => {
  it('cierra el diálogo, borra lo guardado y muestra el mensaje, sin quedar en bucle', async () => {
    entrar('VENDEDOR')
    sessionStorage.setItem(CLAVE_VENTA_PENDIENTE, JSON.stringify({
      clave: 'k-2',
      cuerpo: { claveIdempotencia: 'k-2', lineas: [{ cantidad: 1, productoId: 30, descuento: { tipo: 'PORCENTAJE', valor: 10 } }], pagos: [] },
      motivos: ['Descuento en la venta'],
    }))
    registrarVenta.mockRejectedValueOnce(errorApi(403, 'Tu empresa está en modo de consulta.', 'urn:sgip:empresa:solo-consulta'))
    render(<MemoryRouter><Caja /></MemoryRouter>)
    await screen.findByText(/no sabemos si se registró/i)
    await userEvent.click(screen.getByRole('button', { name: /reintentar el cobro/i }))
    const dialogo = await screen.findByRole('dialog', { name: /autorización del administrador/i })
    await userEvent.selectOptions(within(dialogo).getByLabelText(/administrador/i), '3')
    await userEvent.type(within(dialogo).getByLabelText(/^pin$/i), '4821')
    await userEvent.click(within(dialogo).getByRole('button', { name: /autorizar y cobrar/i }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(screen.getByRole('alert')).toHaveTextContent('Tu empresa está en modo de consulta.')
    expect(sessionStorage.getItem(CLAVE_VENTA_PENDIENTE)).toBeNull()
    expect(screen.queryByText(/no sabemos si se registró/i)).not.toBeInTheDocument()
    expect(await screen.findByLabelText(/buscar producto/i)).toBeInTheDocument()
  })
})

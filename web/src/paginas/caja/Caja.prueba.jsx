import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { hoyISO, sumarDias } from '../../servicios/fechas.js'
import {
  abrirTurno, registrarVenta, saldoDeCliente, turnoActual,
} from '../../servicios/api/caja.js'
import { ANA, PINTURA, TORNILLO, entrar, errorApi, pesos, prepararApi } from './ayuda.js'
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
  render(<Caja />)
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
    render(<Caja />)
    expect(await screen.findByRole('heading', { name: /abrir turno/i })).toBeInTheDocument()
    expect(screen.queryByLabelText(/buscar producto/i)).not.toBeInTheDocument()
    await userEvent.type(screen.getByLabelText(/base de efectivo/i), '50000')
    await userEvent.click(screen.getByRole('button', { name: /^abrir turno$/i }))
    expect(abrirTurno).toHaveBeenCalledWith(50000)
    expect(await screen.findByLabelText(/buscar producto/i)).toBeInTheDocument()
  })

  it('la base es obligatoria y entera', async () => {
    turnoActual.mockResolvedValue(null)
    render(<Caja />)
    await screen.findByRole('heading', { name: /abrir turno/i })
    await userEvent.click(screen.getByRole('button', { name: /^abrir turno$/i }))
    expect(screen.getByText(/escribe la base de efectivo/i)).toBeInTheDocument()
    expect(abrirTurno).not.toHaveBeenCalled()
  })

  it('un error al abrir el turno se anuncia', async () => {
    turnoActual.mockResolvedValue(null)
    abrirTurno.mockRejectedValue(errorApi(409, 'Ya tienes un turno abierto.'))
    render(<Caja />)
    await screen.findByRole('heading', { name: /abrir turno/i })
    await userEvent.type(screen.getByLabelText(/base de efectivo/i), '0')
    await userEvent.click(screen.getByRole('button', { name: /^abrir turno$/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Ya tienes un turno abierto.')
  })

  it('un fallo al leer el turno se muestra con reintento', async () => {
    turnoActual.mockRejectedValueOnce(errorApi(500, 'Se cayó'))
    render(<Caja />)
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
  it('reintentoUsaLaMismaClave: tras un fallo de red el reintento repite la clave, y la venta siguiente usa otra', async () => {
    registrarVenta.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await abrir()
    await agregar()
    await pagarTodo()
    await userEvent.click(cobrar())
    expect(await screen.findByText(/sin conexión con el servidor/i)).toBeInTheDocument()
    expect(cobrar()).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: /reintentar el cobro/i }))
    await waitFor(() => expect(registrarVenta).toHaveBeenCalledTimes(2))
    const [primera, segunda] = registrarVenta.mock.calls.map((c) => c[0].claveIdempotencia)
    expect(segunda).toBe(primera)
    expect(await screen.findByText(/venta registrada/i)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /nueva venta/i }))
    await agregar()
    await pagarTodo()
    await userEvent.click(cobrar())
    await waitFor(() => expect(registrarVenta).toHaveBeenCalledTimes(3))
    expect(registrarVenta.mock.calls[2][0].claveIdempotencia).not.toBe(primera)
  })

  it('si la carga cambia tras un fallo de red la clave se mantiene y un 409 se explica sin cobrar otra vez', async () => {
    registrarVenta.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    registrarVenta.mockRejectedValueOnce(errorApi(409, 'Esta venta ya se registró con otra carga.'))
    await abrir()
    await agregar()
    await pagarTodo()
    await userEvent.click(cobrar())
    await screen.findByText(/sin conexión con el servidor/i)
    await userEvent.type(screen.getByLabelText('Cantidad de Tornillo M6'), '2')
    await userEvent.click(screen.getByRole('button', { name: /reintentar el cobro/i }))
    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('Esta venta ya se registró con otra carga.')
    expect(alerta).toHaveTextContent(/no se cobró otra vez/i)
    expect(registrarVenta.mock.calls[1][0].claveIdempotencia).toBe(registrarVenta.mock.calls[0][0].claveIdempotencia)
  })

  it('vaciar el carrito renueva la clave', async () => {
    registrarVenta.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await abrir()
    await agregar()
    await pagarTodo()
    await userEvent.click(cobrar())
    await screen.findByText(/sin conexión con el servidor/i)
    await userEvent.click(screen.getByRole('button', { name: 'Quitar Tornillo M6' }))
    await agregar()
    await pagarTodo()
    await userEvent.click(cobrar())
    await waitFor(() => expect(registrarVenta).toHaveBeenCalledTimes(2))
    expect(registrarVenta.mock.calls[1][0].claveIdempotencia).not.toBe(registrarVenta.mock.calls[0][0].claveIdempotencia)
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
    expect(await screen.findByRole('alert')).toHaveTextContent('No hay existencia suficiente de Tornillo M6.')
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
      const { unmount } = render(<Caja />)
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

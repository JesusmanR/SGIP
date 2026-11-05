import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ErrorApi } from '../../servicios/api.js'
import { recibirOrden, verOrden } from '../../servicios/api/compras.js'
import { ORDEN, montar } from './ayuda.jsx'

vi.mock('../../servicios/api/compras.js', () => ({
  listarOrdenes: vi.fn(async () => []), verOrden: vi.fn(), crearOrden: vi.fn(),
  recibirOrden: vi.fn(), buscarProductos: vi.fn(), PRODUCTOS_POR_BUSQUEDA: 8,
}))
vi.mock('../../servicios/api/proveedores.js', () => ({ listarProveedores: vi.fn(async () => []) }))

beforeEach(() => vi.resetAllMocks())

const parcial = {
  ...ORDEN,
  estado: 'PARCIAL',
  items: [
    { ...ORDEN.items[0], cantidadRecibida: 5, pendiente: 15 },
    { ...ORDEN.items[1], cantidadRecibida: 12, pendiente: 0 },
  ],
}

async function abrir(orden = ORDEN) {
  verOrden.mockResolvedValue(orden)
  montar(`/compras/${orden.ordenId}`)
  await screen.findByRole('heading', { level: 1, name: /OC-0012/ })
}
const llegan = (producto) => screen.getByLabelText(`Llegan ahora: ${producto}`)
const revisar = () => userEvent.click(screen.getByRole('button', { name: /revisar recepción/i }))

describe('detalle de una orden', () => {
  it('muestra los datos y las líneas tal como las calculó el servidor', async () => {
    await abrir()
    expect(verOrden).toHaveBeenCalledWith('12')
    expect(screen.getByText('Aceros del Valle S.A.S.')).toBeInTheDocument()
    expect(screen.getByText('Laura Pérez')).toBeInTheDocument()
    expect(screen.getByText('Entrega en bodega 2')).toBeInTheDocument()
    expect(screen.getByText('Pendiente', { selector: '.insignia' })).toBeInTheDocument()
    const tabla = screen.getByRole('table', { name: /productos de la orden/i })
    const fila = within(tabla).getByRole('rowheader', { name: /Tornillo M6/ }).closest('tr')
    expect(within(fila).getByText('20', { selector: '[data-etiqueta="Pedido"]' })).toBeInTheDocument()
    expect(fila.querySelector('[data-etiqueta="Pendiente"]')).toHaveTextContent('20')
    expect(fila.querySelector('[data-etiqueta="Subtotal"]')).toHaveTextContent(/30\.000/)
    expect(screen.getByText(/total de la orden/i).closest('p')).toHaveTextContent(/73\.200/)
  })

  it('un 404 dice que no existe, sin reintento', async () => {
    verOrden.mockRejectedValue(new ErrorApi(404, 'x'))
    montar('/compras/99')
    expect(await screen.findByText(/esa orden no existe/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /volver a órdenes de compra/i })).toHaveAttribute('href', '/compras')
    expect(screen.queryByRole('button', { name: /reintentar/i })).not.toBeInTheDocument()
  })

  it('un error reintenta y un 403 muestra sin permiso', async () => {
    verOrden.mockRejectedValueOnce(new ErrorApi(500, 'Falló'))
    verOrden.mockResolvedValueOnce(ORDEN)
    montar('/compras/12')
    expect(await screen.findByRole('alert')).toHaveTextContent('Falló')
    await userEvent.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByRole('heading', { level: 1, name: /OC-0012/ })).toBeInTheDocument()
  })

  it('un 403 muestra sin permiso', async () => {
    verOrden.mockRejectedValue(new ErrorApi(403, 'no'))
    montar('/compras/12')
    expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument()
  })

  it('una orden recibida o anulada no ofrece recibir y dice por qué', async () => {
    await abrir({ ...ORDEN, estado: 'RECIBIDA' })
    expect(screen.getByText(/ya se recibió completa/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /revisar recepción/i })).not.toBeInTheDocument()
  })

  it('una orden anulada no admite recepciones', async () => {
    await abrir({ ...ORDEN, estado: 'ANULADA' })
    expect(screen.getByText(/está anulada y no admite recepciones/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /revisar recepción/i })).not.toBeInTheDocument()
  })

  it('no ofrece anular ni editar: el servidor no tiene esas rutas', async () => {
    await abrir()
    expect(screen.queryByRole('button', { name: /anular|editar|eliminar/i })).not.toBeInTheDocument()
  })
})

describe('registrar una recepción', () => {
  it('explica que se escribe solo lo que llegó y muestra lo pendiente de cada línea', async () => {
    await abrir()
    expect(screen.getByText(/escribe solo lo que llegó/i)).toBeInTheDocument()
    expect(llegan('Tornillo M6')).toBeInTheDocument()
    expect(llegan('Tuerca M6')).toBeInTheDocument()
    expect(screen.getByText(/pendiente: 20/i)).toBeInTheDocument()
    expect(screen.getByText(/pendiente: 10/i)).toBeInTheDocument()
  })

  it('una línea sin nada pendiente no recibe cantidad', async () => {
    await abrir(parcial)
    expect(llegan('Tornillo M6')).toBeInTheDocument()
    expect(screen.queryByLabelText('Llegan ahora: Tuerca M6')).not.toBeInTheDocument()
  })

  it('sin escribir nada no pasa a confirmar y enfoca el primer campo', async () => {
    await abrir()
    await revisar()
    expect(recibirOrden).not.toHaveBeenCalled()
    expect(screen.queryByText(/confirma la recepción/i)).not.toBeInTheDocument()
    expect(llegan('Tornillo M6')).toHaveFocus()
    expect(llegan('Tornillo M6')).toHaveAccessibleDescription(/al menos un producto/i)
  })

  it('más de lo pendiente, decimales o negativos no pasan, y dicen cuánto falta', async () => {
    await abrir()
    await userEvent.type(llegan('Tornillo M6'), '21')
    await userEvent.type(llegan('Tuerca M6'), '1.5')
    await revisar()
    expect(llegan('Tornillo M6')).toHaveAccessibleDescription(/solo faltan 20/i)
    expect(llegan('Tuerca M6')).toHaveAccessibleDescription(/número entero/i)
    expect(llegan('Tornillo M6')).toHaveFocus()
    expect(screen.queryByText(/confirma la recepción/i)).not.toBeInTheDocument()
  })

  it('«Recibir todo lo pendiente» rellena con lo pendiente que dice el servidor', async () => {
    await abrir()
    await userEvent.click(screen.getByRole('button', { name: /recibir todo lo pendiente/i }))
    expect(llegan('Tornillo M6')).toHaveValue('20')
    expect(llegan('Tuerca M6')).toHaveValue('10')
  })

  it('antes de confirmar dice qué va a pasar con la existencia y con el costo, línea por línea', async () => {
    await abrir()
    await userEvent.type(llegan('Tornillo M6'), '8')
    await revisar()
    expect(screen.getByRole('heading', { name: /confirma la recepción/i })).toBeInTheDocument()
    expect(screen.getByText(/Tornillo M6/, { selector: 'li' })).toHaveTextContent(/entran 8 unidades/i)
    expect(screen.queryByText(/Tuerca M6/, { selector: 'li' })).not.toBeInTheDocument()
    expect(screen.getByText(/la existencia de cada producto sube/i)).toBeInTheDocument()
    expect(screen.getByText(/el costo de los productos no cambia/i)).toBeInTheDocument()
    expect(screen.getByText(/ajuste de inventario/i)).toBeInTheDocument()
    expect(recibirOrden).not.toHaveBeenCalled()
  })

  it('«Volver a editar» regresa al formulario con lo escrito, sin enviar', async () => {
    await abrir()
    await userEvent.type(llegan('Tornillo M6'), '8')
    await revisar()
    await userEvent.click(screen.getByRole('button', { name: /volver a editar/i }))
    expect(llegan('Tornillo M6')).toHaveValue('8')
    expect(recibirOrden).not.toHaveBeenCalled()
  })

  it('confirmar envía solo las líneas mayores que cero, con documento y observación, y repinta con la respuesta', async () => {
    recibirOrden.mockResolvedValue(parcial)
    await abrir()
    await userEvent.type(llegan('Tornillo M6'), '5')
    await userEvent.type(llegan('Tuerca M6'), '0')
    await userEvent.type(screen.getByLabelText(/documento del proveedor/i), ' REM-77 ')
    await revisar()
    await userEvent.click(screen.getByRole('button', { name: /confirmar recepción/i }))
    await waitFor(() => expect(recibirOrden).toHaveBeenCalledTimes(1))
    expect(recibirOrden).toHaveBeenCalledWith(12, {
      items: [{ productoId: 30, cantidadRecibida: 5 }],
      observacion: null,
      documento: 'REM-77',
    })
    expect(await screen.findByText(/recepción registrada/i)).toBeInTheDocument()
    expect(screen.getByText(/la orden quedó: recibida en parte/i)).toBeInTheDocument()
    expect(screen.getByText('Recibida en parte', { selector: '.insignia' })).toBeInTheDocument()
    // El formulario vuelve vacío y sin la línea que ya no tiene pendiente.
    expect(llegan('Tornillo M6')).toHaveValue('')
    expect(screen.queryByLabelText('Llegan ahora: Tuerca M6')).not.toBeInTheDocument()
  })

  it('si la respuesta deja la orden recibida, ya no se ofrece recibir', async () => {
    recibirOrden.mockResolvedValue({ ...ORDEN, estado: 'RECIBIDA' })
    await abrir()
    await userEvent.click(screen.getByRole('button', { name: /recibir todo lo pendiente/i }))
    await revisar()
    await userEvent.click(screen.getByRole('button', { name: /confirmar recepción/i }))
    expect(await screen.findByText(/la orden quedó: recibida/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /revisar recepción/i })).not.toBeInTheDocument()
  })

  it('un 400 del servidor se muestra tal cual, vuelve al formulario con lo escrito y deja reintentar', async () => {
    recibirOrden.mockRejectedValue(new ErrorApi(400, 'Del producto 30 se pidieron 20: solo caben 15.'))
    await abrir()
    await userEvent.type(llegan('Tornillo M6'), '8')
    await revisar()
    await userEvent.click(screen.getByRole('button', { name: /confirmar recepción/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Del producto 30 se pidieron 20: solo caben 15.')
    expect(llegan('Tornillo M6')).toHaveValue('8')
    expect(screen.getByRole('button', { name: /revisar recepción/i })).toBeEnabled()
  })

  it('un 404 al recibir también se dice con palabras', async () => {
    recibirOrden.mockRejectedValue(new ErrorApi(404, 'x'))
    await abrir()
    await userEvent.type(llegan('Tornillo M6'), '8')
    await revisar()
    await userEvent.click(screen.getByRole('button', { name: /confirmar recepción/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/ya no existe/i)
  })

  it('mientras envía no se puede confirmar otra vez', async () => {
    let resolver
    recibirOrden.mockReturnValue(new Promise((r) => { resolver = r }))
    await abrir()
    await userEvent.type(llegan('Tornillo M6'), '8')
    await revisar()
    await userEvent.click(screen.getByRole('button', { name: /confirmar recepción/i }))
    const enviando = screen.getByRole('button', { name: /registrando/i })
    expect(enviando).toBeDisabled()
    await userEvent.click(enviando)
    expect(recibirOrden).toHaveBeenCalledTimes(1)
    resolver(parcial)
  })

  it('el documento de más de 60 caracteres no pasa a confirmar', async () => {
    await abrir()
    await userEvent.type(llegan('Tornillo M6'), '1')
    await userEvent.type(screen.getByLabelText(/documento del proveedor/i), 'D'.repeat(61))
    await revisar()
    expect(screen.queryByText(/confirma la recepción/i)).not.toBeInTheDocument()
    expect(screen.getByLabelText(/documento del proveedor/i)).toHaveAccessibleDescription(/60 caracteres/i)
  })
})

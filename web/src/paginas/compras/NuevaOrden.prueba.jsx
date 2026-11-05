import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ErrorApi } from '../../servicios/api.js'
import { buscarProductos, crearOrden, verOrden } from '../../servicios/api/compras.js'
import { listarProveedores } from '../../servicios/api/proveedores.js'
import { ORDEN, PRODUCTO_A, PRODUCTO_B, PROVEEDORES, montar } from './ayuda.jsx'

vi.mock('../../servicios/api/compras.js', () => ({
  listarOrdenes: vi.fn(async () => []), verOrden: vi.fn(), crearOrden: vi.fn(),
  recibirOrden: vi.fn(), buscarProductos: vi.fn(),
}))
vi.mock('../../servicios/api/proveedores.js', () => ({ listarProveedores: vi.fn() }))

beforeEach(() => {
  vi.resetAllMocks()
  listarProveedores.mockResolvedValue(PROVEEDORES)
})

async function abrir() {
  montar('/compras/nueva')
  await screen.findByLabelText(/proveedor/i)
}
const guardar = () => userEvent.click(screen.getByRole('button', { name: /guardar orden/i }))

async function agregar(...productos) {
  buscarProductos.mockResolvedValue(productos)
  await userEvent.type(screen.getByLabelText(/buscar producto/i), 'algo')
  await userEvent.click(screen.getByRole('button', { name: /^buscar$/i }))
  for (const p of productos) {
    await userEvent.click(await screen.findByRole('button', { name: `Agregar ${p.nombre}` }))
  }
}

describe('nueva orden de compra', () => {
  it('carga los proveedores una vez y muestra los campos con etiqueta', async () => {
    await abrir()
    expect(listarProveedores).toHaveBeenCalledTimes(1)
    const select = screen.getByLabelText(/proveedor/i)
    expect(within(select).getByRole('option', { name: 'Pinturas Sol' })).toBeInTheDocument()
    expect(screen.getByLabelText(/número de la orden/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/observación/i)).toBeInTheDocument()
  })

  it('sin proveedores manda a crear uno primero', async () => {
    listarProveedores.mockResolvedValue([])
    montar('/compras/nueva')
    expect(await screen.findByText(/primero crea un proveedor/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /crear un proveedor/i })).toHaveAttribute('href', '/proveedores/nuevo')
  })

  it('un error al cargar proveedores reintenta; un 403 muestra sin permiso', async () => {
    listarProveedores.mockRejectedValueOnce(new ErrorApi(500, 'Falló'))
    montar('/compras/nueva')
    expect(await screen.findByRole('alert')).toHaveTextContent('Falló')
    await userEvent.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByLabelText(/proveedor/i)).toBeInTheDocument()
  })

  it('un 403 al cargar proveedores muestra sin permiso', async () => {
    listarProveedores.mockRejectedValue(new ErrorApi(403, 'no'))
    montar('/compras/nueva')
    expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument()
  })

  it('vacío no envía nada, marca cada campo con su motivo y enfoca el primero', async () => {
    await abrir()
    await guardar()
    expect(crearOrden).not.toHaveBeenCalled()
    const proveedor = screen.getByLabelText(/proveedor/i)
    expect(proveedor).toHaveFocus()
    expect(proveedor).toHaveAccessibleDescription(/elige un proveedor/i)
    expect(screen.getByLabelText(/número de la orden/i)).toHaveAccessibleDescription(/obligatorio/i)
    expect(screen.getByText(/agrega al menos un producto/i)).toBeInTheDocument()
  })

  it('buscar sin texto no hace ninguna petición', async () => {
    await abrir()
    await userEvent.click(screen.getByRole('button', { name: /^buscar$/i }))
    expect(buscarProductos).not.toHaveBeenCalled()
  })

  it('buscar pide al servidor solo al pulsar o dar Enter, nunca por cada tecla', async () => {
    buscarProductos.mockResolvedValue([PRODUCTO_A])
    await abrir()
    await userEvent.type(screen.getByLabelText(/buscar producto/i), 'torn')
    expect(buscarProductos).not.toHaveBeenCalled()
    await userEvent.type(screen.getByLabelText(/buscar producto/i), '{Enter}')
    expect(buscarProductos).toHaveBeenCalledTimes(1)
    expect(buscarProductos).toHaveBeenCalledWith('torn')
    expect(await screen.findByText(/TOR-6/)).toBeInTheDocument()
    expect(screen.getByText(/costo actual/i)).toBeInTheDocument()
  })

  it('sin resultados lo dice, y un error de búsqueda se avisa sin tumbar la pantalla', async () => {
    await abrir()
    buscarProductos.mockResolvedValueOnce([])
    await userEvent.type(screen.getByLabelText(/buscar producto/i), 'zzz{Enter}')
    expect(await screen.findByText(/no encontramos productos/i)).toBeInTheDocument()
    buscarProductos.mockRejectedValueOnce(new ErrorApi(500, 'Búsqueda caída'))
    await userEvent.type(screen.getByLabelText(/buscar producto/i), 'x{Enter}')
    expect(await screen.findByText(/búsqueda caída/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/número de la orden/i)).toBeInTheDocument()
  })

  it('agregar un producto crea su línea y no se puede agregar dos veces', async () => {
    await abrir()
    await agregar(PRODUCTO_A)
    expect(screen.getByLabelText('Cantidad de Tornillo M6')).toBeInTheDocument()
    expect(screen.getByLabelText('Costo unitario de Tornillo M6')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Agregar Tornillo M6' })).toBeDisabled()
  })

  it('quitar una línea la saca de la orden', async () => {
    await abrir()
    await agregar(PRODUCTO_A, PRODUCTO_B)
    await userEvent.click(screen.getByRole('button', { name: 'Quitar Tornillo M6' }))
    expect(screen.queryByLabelText('Cantidad de Tornillo M6')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Cantidad de Tuerca M6')).toBeInTheDocument()
  })

  it('la suma de vista previa se marca como tal y sale de lo escrito', async () => {
    await abrir()
    await agregar(PRODUCTO_A, PRODUCTO_B)
    await userEvent.type(screen.getByLabelText('Cantidad de Tornillo M6'), '2')
    await userEvent.type(screen.getByLabelText('Costo unitario de Tornillo M6'), '1500')
    await userEvent.type(screen.getByLabelText('Cantidad de Tuerca M6'), '3')
    await userEvent.type(screen.getByLabelText('Costo unitario de Tuerca M6'), '250')
    const total = screen.getByText(/total de la vista previa/i).closest('p')
    expect(total).toHaveTextContent(/3\.750/)
    expect(screen.getByText(/el total oficial lo calcula el sistema al guardar/i)).toBeInTheDocument()
  })

  it('cantidad y costo inválidos no se envían y se dicen en su línea', async () => {
    await abrir()
    await agregar(PRODUCTO_A)
    await userEvent.selectOptions(screen.getByLabelText(/proveedor/i), '4')
    await userEvent.type(screen.getByLabelText(/número de la orden/i), 'OC-1')
    await userEvent.type(screen.getByLabelText('Cantidad de Tornillo M6'), '0')
    await userEvent.type(screen.getByLabelText('Costo unitario de Tornillo M6'), '12.345')
    await guardar()
    expect(crearOrden).not.toHaveBeenCalled()
    const cantidad = screen.getByLabelText('Cantidad de Tornillo M6')
    expect(cantidad).toHaveAttribute('aria-invalid', 'true')
    expect(cantidad).toHaveFocus()
    expect(cantidad).toHaveAccessibleDescription(/entera de 1 o más/i)
    expect(screen.getByLabelText('Costo unitario de Tornillo M6')).toHaveAccessibleDescription(/hasta 2 decimales/i)
  })

  it('el consecutivo de más de 20 caracteres no se envía', async () => {
    await abrir()
    await agregar(PRODUCTO_A)
    await userEvent.selectOptions(screen.getByLabelText(/proveedor/i), '4')
    await userEvent.type(screen.getByLabelText(/número de la orden/i), 'X'.repeat(21))
    await userEvent.type(screen.getByLabelText('Cantidad de Tornillo M6'), '1')
    await userEvent.type(screen.getByLabelText('Costo unitario de Tornillo M6'), '10')
    await guardar()
    expect(crearOrden).not.toHaveBeenCalled()
    expect(screen.getByLabelText(/número de la orden/i)).toHaveAccessibleDescription(/20 caracteres/i)
  })

  async function llenarValida() {
    await abrir()
    await agregar(PRODUCTO_A)
    await userEvent.selectOptions(screen.getByLabelText(/proveedor/i), '4')
    await userEvent.type(screen.getByLabelText(/número de la orden/i), '  OC-0012 ')
    await userEvent.type(screen.getByLabelText('Cantidad de Tornillo M6'), '20')
    await userEvent.type(screen.getByLabelText('Costo unitario de Tornillo M6'), '1500')
  }

  it('con datos válidos envía el cuerpo que espera el servidor y abre la orden creada', async () => {
    crearOrden.mockResolvedValue(ORDEN)
    verOrden.mockResolvedValue(ORDEN)
    await llenarValida()
    await guardar()
    await waitFor(() => expect(crearOrden).toHaveBeenCalledTimes(1))
    expect(crearOrden).toHaveBeenCalledWith({
      proveedorId: 4,
      consecutivo: 'OC-0012',
      observacion: null,
      items: [{ productoId: 30, cantidad: 20, costoUnitario: 1500 }],
    })
    expect(await screen.findByRole('heading', { level: 1, name: /OC-0012/ })).toBeInTheDocument()
    expect(verOrden).toHaveBeenCalledWith('12')
    expect(screen.getByRole('status')).toHaveTextContent(/orden creada.*no cambia el inventario/i)
  })

  it('el consecutivo repetido (409) se dice junto a ese campo', async () => {
    crearOrden.mockRejectedValue(new ErrorApi(409, 'Ya hay una orden de compra con el consecutivo OC-0012.'))
    await llenarValida()
    await guardar()
    await waitFor(() => {
      expect(screen.getByLabelText(/número de la orden/i)).toHaveAccessibleDescription(/ya hay una orden/i)
    })
    expect(screen.getByLabelText(/número de la orden/i)).toHaveFocus()
  })

  it('un 422 o un 400 del servidor salen completos en un aviso y no se pierde lo escrito', async () => {
    crearOrden.mockRejectedValue(new ErrorApi(422, 'El proveedor indicado no existe en esta empresa.'))
    await llenarValida()
    await guardar()
    expect(await screen.findByRole('alert')).toHaveTextContent('El proveedor indicado no existe en esta empresa.')
    expect(screen.getByLabelText('Cantidad de Tornillo M6')).toHaveValue('20')
    expect(screen.getByRole('button', { name: /guardar orden/i })).toBeEnabled()
  })

  it('mientras guarda el botón está deshabilitado y no se envía dos veces', async () => {
    let resolver
    crearOrden.mockReturnValue(new Promise((r) => { resolver = r }))
    verOrden.mockResolvedValue(ORDEN)
    await llenarValida()
    await guardar()
    const enviando = screen.getByRole('button', { name: /guardando/i })
    expect(enviando).toBeDisabled()
    await userEvent.click(enviando)
    expect(crearOrden).toHaveBeenCalledTimes(1)
    resolver(ORDEN)
  })
})

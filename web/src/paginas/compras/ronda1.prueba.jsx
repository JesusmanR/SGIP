/** Ronda 1 de arreglos: foco, ids únicos, centavos, aviso de más resultados, recarga y errores. */
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ErrorApi } from '../../servicios/api.js'
import { buscarProductos, crearOrden, recibirOrden, verOrden } from '../../servicios/api/compras.js'
import { listarProveedores } from '../../servicios/api/proveedores.js'
import Campo from '../../componentes/comun/Campo.jsx'
import { ORDEN, PRODUCTO_A, PRODUCTO_B, PROVEEDORES, montar } from './ayuda.jsx'

vi.mock('../../servicios/api/compras.js', () => ({
  listarOrdenes: vi.fn(async () => []), verOrden: vi.fn(), crearOrden: vi.fn(),
  recibirOrden: vi.fn(), buscarProductos: vi.fn(), PRODUCTOS_POR_BUSQUEDA: 8,
}))
vi.mock('../../servicios/api/proveedores.js', () => ({ listarProveedores: vi.fn() }))

beforeEach(() => {
  vi.resetAllMocks()
  listarProveedores.mockResolvedValue(PROVEEDORES)
})

const parcial = {
  ...ORDEN,
  estado: 'PARCIAL',
  items: [
    { ...ORDEN.items[0], cantidadRecibida: 5, pendiente: 15 },
    { ...ORDEN.items[1], cantidadRecibida: 12, pendiente: 0 },
  ],
}
const llegan = (producto) => screen.getByLabelText(`Llegan ahora: ${producto}`)

async function abrirOrden(orden = ORDEN) {
  verOrden.mockResolvedValue(orden)
  montar(`/compras/${orden.ordenId}`)
  await screen.findByRole('heading', { level: 1, name: /OC-0012/ })
}

async function abrirNueva() {
  montar('/compras/nueva')
  await screen.findByLabelText(/proveedor/i)
}

async function agregar(...productos) {
  buscarProductos.mockResolvedValue(productos)
  await userEvent.type(screen.getByLabelText(/buscar producto/i), 'algo{Enter}')
  for (const [i, p] of productos.entries()) {
    const botones = await screen.findAllByRole('button', { name: `Agregar ${p.nombre}` })
    // Con nombres repetidos, el de este producto es el que sigue habilitado.
    await userEvent.click(botones.filter((b) => !b.disabled)[0] ?? botones[i])
  }
}

describe('foco en la recepción', () => {
  it('al pasar a confirmar el foco va al encabezado del paso, no se pierde', async () => {
    await abrirOrden()
    await userEvent.type(llegan('Tornillo M6'), '8')
    await userEvent.click(screen.getByRole('button', { name: /revisar recepción/i }))
    expect(screen.getByRole('heading', { name: /confirma la recepción/i })).toHaveFocus()
  })

  it('al volver a editar el foco va al encabezado del formulario', async () => {
    await abrirOrden()
    await userEvent.type(llegan('Tornillo M6'), '8')
    await userEvent.click(screen.getByRole('button', { name: /revisar recepción/i }))
    await userEvent.click(screen.getByRole('button', { name: /volver a editar/i }))
    expect(screen.getByRole('heading', { name: /registrar una recepción/i })).toHaveFocus()
  })

  it('tras recibir el foco va al aviso de éxito', async () => {
    recibirOrden.mockResolvedValue(parcial)
    await abrirOrden()
    await userEvent.type(llegan('Tornillo M6'), '5')
    await userEvent.click(screen.getByRole('button', { name: /revisar recepción/i }))
    await userEvent.click(screen.getByRole('button', { name: /confirmar recepción/i }))
    const aviso = (await screen.findByText(/recepción registrada/i)).closest('[tabindex="-1"]')
    expect(aviso).not.toBeNull()
    await waitFor(() => expect(aviso).toHaveFocus())
  })
})

describe('ids de los mensajes de línea', () => {
  it('dos productos con el mismo nombre no repiten ids ni aria-describedby', async () => {
    await abrirNueva()
    await agregar(
      { ...PRODUCTO_A, nombre: 'Tornillo', productoId: 1 },
      { ...PRODUCTO_B, nombre: 'Tornillo', productoId: 2 },
    )
    await userEvent.click(screen.getByRole('button', { name: /guardar orden/i }))
    const entradas = screen.getAllByLabelText('Cantidad de Tornillo')
    expect(entradas).toHaveLength(2)
    const ids = entradas.map((e) => e.getAttribute('aria-describedby'))
    expect(ids.every(Boolean)).toBe(true)
    expect(new Set(ids).size).toBe(2)
    for (const id of ids) expect(document.querySelectorAll(`[id="${id}"]`)).toHaveLength(1)
  })
})

describe('costo con centavos', () => {
  it('la vista previa los conserva y no arrastra errores de coma flotante', async () => {
    await abrirNueva()
    await agregar(PRODUCTO_A)
    await userEvent.type(screen.getByLabelText('Cantidad de Tornillo M6'), '3')
    await userEvent.type(screen.getByLabelText('Costo unitario de Tornillo M6'), '1500,10')
    const total = screen.getByText(/total de la vista previa/i).closest('p')
    expect(total).toHaveTextContent(/4\.500,30/)
    expect(total).toHaveTextContent(/4\.500,30\s*$/)
    expect(screen.getByRole('cell', { name: /4\.500,30/ })).toBeInTheDocument()
  })

  it('envía el costo con punto decimal, como lo pide el servidor', async () => {
    crearOrden.mockResolvedValue(ORDEN)
    verOrden.mockResolvedValue(ORDEN)
    await abrirNueva()
    await agregar(PRODUCTO_A)
    await userEvent.selectOptions(screen.getByLabelText(/proveedor/i), '4')
    await userEvent.type(screen.getByLabelText(/número de la orden/i), 'OC-9')
    await userEvent.type(screen.getByLabelText('Cantidad de Tornillo M6'), '3')
    await userEvent.type(screen.getByLabelText('Costo unitario de Tornillo M6'), '1500,10')
    await userEvent.click(screen.getByRole('button', { name: /guardar orden/i }))
    await waitFor(() => expect(crearOrden).toHaveBeenCalled())
    expect(crearOrden.mock.calls[0][0].items[0].costoUnitario).toBe(1500.1)
  })

  it('el detalle muestra los centavos que trae el servidor', async () => {
    await abrirOrden({
      ...ORDEN,
      total: 30010.5,
      items: [{ ...ORDEN.items[0], costoUnitario: 1500.5, subtotal: 30010 }],
    })
    expect(screen.getAllByText(/1\.500,50/).length).toBeGreaterThan(0)
    expect(screen.getByText(/total de la orden/i).closest('p')).toHaveTextContent(/30\.010,50/)
  })
})

describe('búsqueda de productos', () => {
  const ocho = Array.from({ length: 8 }, (_, i) => ({ productoId: 100 + i, codigo: `C-${i}`, nombre: `Pieza ${i}`, costo: 10 }))

  it('si llegan 8 avisa de que puede haber más y pide afinar', async () => {
    await abrirNueva()
    buscarProductos.mockResolvedValue(ocho)
    await userEvent.type(screen.getByLabelText(/buscar producto/i), 'pieza{Enter}')
    expect(await screen.findByText(/puede haber más productos/i)).toHaveTextContent(/afina la búsqueda/i)
  })

  it('con menos de 8 no avisa', async () => {
    await abrirNueva()
    buscarProductos.mockResolvedValue(ocho.slice(0, 3))
    await userEvent.type(screen.getByLabelText(/buscar producto/i), 'pieza{Enter}')
    await screen.findByText('Pieza 0')
    expect(screen.queryByText(/puede haber más productos/i)).not.toBeInTheDocument()
  })
})

describe('recepción rechazada por el servidor', () => {
  async function intentar(recarga) {
    await abrirOrden()
    if (recarga) verOrden.mockResolvedValue(recarga)
    await userEvent.type(llegan('Tornillo M6'), '8')
    await userEvent.click(screen.getByRole('button', { name: /revisar recepción/i }))
    await userEvent.click(screen.getByRole('button', { name: /confirmar recepción/i }))
  }

  it.each([400, 409])('ante un %i recarga la orden y conserva el mensaje del servidor', async (estado) => {
    recibirOrden.mockRejectedValue(new ErrorApi(estado, 'La orden ya cambió.'))
    const alRecargar = { ...ORDEN, estado: 'PARCIAL', items: [{ ...ORDEN.items[0], cantidadRecibida: 5, pendiente: 15 }] }
    await intentar(alRecargar)
    expect(await screen.findByRole('alert')).toHaveTextContent('La orden ya cambió.')
    await waitFor(() => expect(verOrden).toHaveBeenCalledTimes(2))
    expect(await screen.findByText(/pendiente: 15/i)).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('La orden ya cambió.')
  })

  it('un 500 no recarga y muestra el error', async () => {
    recibirOrden.mockRejectedValue(new ErrorApi(500, 'Falla del servidor.'))
    await intentar()
    expect(await screen.findByRole('alert')).toHaveTextContent('Falla del servidor.')
    expect(verOrden).toHaveBeenCalledTimes(1)
  })

  it('un 403 muestra el error y deja corregir', async () => {
    recibirOrden.mockRejectedValue(new ErrorApi(403, 'No tienes permiso.'))
    await intentar()
    expect(await screen.findByRole('alert')).toHaveTextContent('No tienes permiso.')
    expect(llegan('Tornillo M6')).toHaveValue('8')
  })
})

describe('errores al crear una orden', () => {
  async function llenar() {
    await abrirNueva()
    await agregar(PRODUCTO_A)
    await userEvent.selectOptions(screen.getByLabelText(/proveedor/i), '4')
    await userEvent.type(screen.getByLabelText(/número de la orden/i), 'OC-1')
    await userEvent.type(screen.getByLabelText('Cantidad de Tornillo M6'), '2')
    await userEvent.type(screen.getByLabelText('Costo unitario de Tornillo M6'), '10')
    await userEvent.click(screen.getByRole('button', { name: /guardar orden/i }))
  }

  it('un 409 de producto repetido sale en el aviso y no en el número de la orden', async () => {
    crearOrden.mockRejectedValue(new ErrorApi(409, 'El producto 30 aparece en dos líneas de la orden.'))
    await llenar()
    expect(await screen.findByRole('alert')).toHaveTextContent('El producto 30 aparece en dos líneas de la orden.')
    expect(screen.getByLabelText(/número de la orden/i)).not.toHaveAttribute('aria-invalid')
  })

  it.each([403, 500])('un %i se muestra completo y se puede reintentar', async (estado) => {
    crearOrden.mockRejectedValue(new ErrorApi(estado, `Error ${estado} del servidor.`))
    await llenar()
    expect(await screen.findByRole('alert')).toHaveTextContent(`Error ${estado} del servidor.`)
    expect(screen.getByRole('button', { name: /guardar orden/i })).toBeEnabled()
  })

  it('un segundo envío mientras guarda no llega al servidor (guarda interna, sin botón)', async () => {
    let resolver
    crearOrden.mockReturnValue(new Promise((r) => { resolver = r }))
    verOrden.mockResolvedValue(ORDEN)
    await llenar()
    fireEvent.submit(screen.getByRole('button', { name: /guardando/i }).closest('form'))
    expect(crearOrden).toHaveBeenCalledTimes(1)
    resolver(ORDEN)
  })
})

describe('Campo', () => {
  it('la ayuda neutra no usa la clase de advertencia; el error sí es error', () => {
    const { rerender } = render(<Campo etiqueta="X" ayuda="Una pista"><input /></Campo>)
    expect(screen.getByText('Una pista')).not.toHaveClass('advertencia')
    rerender(<Campo etiqueta="X" motivo="Falló"><input /></Campo>)
    expect(screen.getByText('Falló')).toHaveClass('error')
  })
})

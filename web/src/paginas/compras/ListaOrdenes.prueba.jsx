import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ErrorApi } from '../../servicios/api.js'
import { listarOrdenes } from '../../servicios/api/compras.js'
import { ORDEN, montar } from './ayuda.jsx'

vi.mock('../../servicios/api/compras.js', () => ({
  listarOrdenes: vi.fn(), verOrden: vi.fn(), crearOrden: vi.fn(),
  recibirOrden: vi.fn(), buscarProductos: vi.fn(), PRODUCTOS_POR_BUSQUEDA: 8,
}))
vi.mock('../../servicios/api/proveedores.js', () => ({ listarProveedores: vi.fn(async () => []) }))

beforeEach(() => vi.resetAllMocks())

const muchas = (n) => Array.from({ length: n }, (_, i) => ({
  ...ORDEN, ordenId: 1000 + i, consecutivo: `OC-${1000 + i}`,
}))

describe('lista de órdenes de compra', () => {
  it('mientras carga lo dice', () => {
    listarOrdenes.mockReturnValue(new Promise(() => {}))
    montar()
    expect(screen.getByRole('status')).toHaveTextContent(/cargando/i)
  })

  it('muestra cada orden con su proveedor, estado en texto y el total que calculó el servidor', async () => {
    listarOrdenes.mockResolvedValue([
      ORDEN,
      { ...ORDEN, ordenId: 13, consecutivo: 'OC-0013', estado: 'PARCIAL', total: 5000 },
      { ...ORDEN, ordenId: 14, consecutivo: 'OC-0014', estado: 'RECIBIDA' },
      { ...ORDEN, ordenId: 15, consecutivo: 'OC-0015', estado: 'ANULADA' },
    ])
    montar()
    const tabla = await screen.findByRole('table', { name: /órdenes de compra/i })
    expect(within(tabla).getByRole('link', { name: 'OC-0012' })).toHaveAttribute('href', '/compras/12')
    expect(within(tabla).getAllByText('Aceros del Valle S.A.S.')).toHaveLength(4)
    expect(within(tabla).getByText('Pendiente')).toBeInTheDocument()
    expect(within(tabla).getByText('Recibida en parte')).toBeInTheDocument()
    expect(within(tabla).getByText('Recibida')).toBeInTheDocument()
    expect(within(tabla).getByText('Anulada')).toBeInTheDocument()
    expect(within(tabla).getAllByText(/73.200/)).toHaveLength(3)
    expect(within(tabla).getByText(/5\.000/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /nueva orden/i })).toHaveAttribute('href', '/compras/nueva')
  })

  it('la primera vez pide las 50 más recientes sin filtro', async () => {
    listarOrdenes.mockResolvedValue([ORDEN])
    montar()
    await screen.findByRole('table')
    expect(listarOrdenes).toHaveBeenCalledTimes(1)
    expect(listarOrdenes).toHaveBeenCalledWith({ estado: '', limite: 50 })
  })

  it('filtrar por estado vuelve a pedir al servidor con ese estado', async () => {
    listarOrdenes.mockResolvedValue([ORDEN])
    montar()
    await screen.findByRole('table')
    await userEvent.selectOptions(screen.getByLabelText(/estado/i), 'PARCIAL')
    await screen.findByRole('table')
    expect(listarOrdenes).toHaveBeenLastCalledWith({ estado: 'PARCIAL', limite: 50 })
  })

  it('si llegaron tantas como el límite ofrece ver más, y pide más al servidor', async () => {
    listarOrdenes.mockResolvedValue(muchas(50))
    montar()
    await screen.findByRole('table')
    listarOrdenes.mockResolvedValue(muchas(70))
    await userEvent.click(screen.getByRole('button', { name: /ver más órdenes/i }))
    await screen.findByRole('table')
    expect(listarOrdenes).toHaveBeenLastCalledWith({ estado: '', limite: 100 })
    expect(screen.queryByRole('button', { name: /ver más órdenes/i })).not.toBeInTheDocument()
  })

  it('en el tope de 200 dice que hay más y sugiere filtrar', async () => {
    listarOrdenes.mockResolvedValue(muchas(50))
    montar()
    await screen.findByRole('table')
    listarOrdenes.mockResolvedValue(muchas(100))
    await userEvent.click(screen.getByRole('button', { name: /ver más órdenes/i }))
    await screen.findByRole('table')
    listarOrdenes.mockResolvedValue(muchas(200))
    await userEvent.click(screen.getByRole('button', { name: /ver más órdenes/i }))
    await screen.findByRole('table')
    expect(screen.getByText(/200 más recientes/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /ver más órdenes/i })).not.toBeInTheDocument()
  })

  it('sin órdenes ofrece crear la primera', async () => {
    listarOrdenes.mockResolvedValue([])
    montar()
    expect(await screen.findByText(/todavía no hay órdenes de compra/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /crear la primera orden/i })).toHaveAttribute('href', '/compras/nueva')
  })

  it('con un filtro sin resultados lo dice y deja quitarlo', async () => {
    listarOrdenes.mockResolvedValueOnce([ORDEN])
    montar()
    await screen.findByRole('table')
    listarOrdenes.mockResolvedValue([])
    await userEvent.selectOptions(screen.getByLabelText(/estado/i), 'ANULADA')
    expect(await screen.findByText(/no hay órdenes con ese estado/i)).toBeInTheDocument()
    listarOrdenes.mockResolvedValue([ORDEN])
    await userEvent.click(screen.getByRole('button', { name: /quitar el filtro/i }))
    expect(await screen.findByRole('table')).toBeInTheDocument()
    expect(listarOrdenes).toHaveBeenLastCalledWith({ estado: '', limite: 50 })
  })

  it('un error dice qué pasó y reintenta', async () => {
    listarOrdenes.mockRejectedValueOnce(new ErrorApi(500, 'Se cayó'))
    listarOrdenes.mockResolvedValueOnce([ORDEN])
    montar()
    expect(await screen.findByRole('alert')).toHaveTextContent('Se cayó')
    await userEvent.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByRole('table')).toBeInTheDocument()
  })

  it('un 403 muestra sin permiso', async () => {
    listarOrdenes.mockRejectedValue(new ErrorApi(403, 'no'))
    montar()
    expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument()
  })

  it('recuerda que el inventario solo sube al registrar una recepción', async () => {
    listarOrdenes.mockResolvedValue([ORDEN])
    montar()
    await screen.findByRole('table')
    expect(screen.getByText(/solo aumenta al registrar una recepción/i)).toBeInTheDocument()
  })
})

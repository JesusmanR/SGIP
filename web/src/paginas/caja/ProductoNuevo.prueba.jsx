import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { hoyISO, sumarDias } from '../../servicios/fechas.js'
import ProductoNuevo from './ProductoNuevo.jsx'

const alAgregar = vi.fn()
const alCerrar = vi.fn()
beforeEach(() => vi.clearAllMocks())

const montar = (props = {}) =>
  render(<ProductoNuevo codigoInicial="7705550001" alAgregar={alAgregar} alCerrar={alCerrar} {...props} />)

describe('producto no registrado', () => {
  it('es un diálogo con los datos mínimos y trae el código escaneado', () => {
    montar()
    expect(screen.getByRole('dialog', { name: /producto nuevo/i })).toBeInTheDocument()
    expect(screen.getByLabelText(/^nombre/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/precio de venta \(sin iva\)/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/código de barras/i)).toHaveValue('7705550001')
    expect(screen.getByLabelText(/fecha de entrega estimada/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/motivo del encargo/i)).toBeInTheDocument()
  })

  it('sin los datos obligatorios dice qué falta y no agrega nada', async () => {
    montar()
    await userEvent.click(screen.getByRole('button', { name: /agregar al carrito/i }))
    expect(screen.getByText(/escribe el nombre del producto/i)).toBeInTheDocument()
    expect(screen.getByText(/precio mayor que cero/i)).toBeInTheDocument()
    expect(screen.getByText(/fecha de entrega/i, { selector: '.campo-mensaje' })).toBeInTheDocument()
    expect(screen.getByText(/escribe el motivo/i)).toBeInTheDocument()
    expect(alAgregar).not.toHaveBeenCalled()
  })

  it('con todo completo entrega los datos y no llama al servidor', async () => {
    montar()
    await userEvent.type(screen.getByLabelText(/^nombre/i), 'Llave de paso')
    await userEvent.type(screen.getByLabelText(/precio de venta/i), '15000')
    const fecha = sumarDias(hoyISO(), 10)
    await userEvent.type(screen.getByLabelText(/fecha de entrega/i), fecha)
    await userEvent.type(screen.getByLabelText(/motivo del encargo/i), 'Llega con el proveedor')
    await userEvent.click(screen.getByRole('button', { name: /agregar al carrito/i }))
    expect(alAgregar).toHaveBeenCalledWith({
      nombre: 'Llave de paso', precioVenta: 15000, codigoBarras: '7705550001',
      entregaEstimada: fecha, motivo: 'Llega con el proveedor',
    })
  })

  it('Escape y Cancelar cierran', async () => {
    montar()
    await userEvent.keyboard('{Escape}')
    expect(alCerrar).toHaveBeenCalledTimes(1)
    await userEvent.click(screen.getByRole('button', { name: /cancelar/i }))
    expect(alCerrar).toHaveBeenCalledTimes(2)
  })
})

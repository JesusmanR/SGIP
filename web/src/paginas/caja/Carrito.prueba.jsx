import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { calcularVistaPrevia } from './calculo.js'
import { PINTURA, TORNILLO, pesos } from './ayuda.js'
import Carrito from './Carrito.jsx'
import { descuentoDe, lineaDeProducto, lineasParaVista } from './venta.js'

const alCambiar = vi.fn()
const alQuitar = vi.fn()
const alCambiarMotivo = vi.fn()
const alCambiarDescuentoTotal = vi.fn()
beforeEach(() => vi.clearAllMocks())

function montar(lineas, extra = {}) {
  const vista = calcularVistaPrevia(lineasParaVista(lineas), descuentoDe(extra.descuentoTotal ?? null))
  return render(
    <Carrito
      lineas={lineas} vista={vista} alCambiar={alCambiar} alQuitar={alQuitar}
      descuentoTotal={{ tipo: 'PORCENTAJE', valor: '' }} alCambiarDescuentoTotal={alCambiarDescuentoTotal}
      motivoEncargo="" alCambiarMotivo={alCambiarMotivo} {...extra}
    />,
  )
}

describe('carrito', () => {
  it('vacío invita a escanear', () => {
    montar([])
    expect(screen.getByText(/escanea o busca un producto/i)).toBeInTheDocument()
  })

  it('muestra el producto, el precio sin IVA y el total de la línea con IVA', () => {
    montar([{ ...lineaDeProducto(TORNILLO), cantidad: '2' }])
    const fila = screen.getByRole('row', { name: /tornillo m6/i })
    expect(within(fila).getByText(pesos(10000))).toBeInTheDocument()
    expect(within(fila).getByText(pesos(23800))).toBeInTheDocument()
  })

  it('cambiar la cantidad y quitar avisan a la pantalla', async () => {
    const [linea] = [lineaDeProducto(TORNILLO)]
    montar([linea])
    await userEvent.type(screen.getByLabelText('Cantidad de Tornillo M6'), '3')
    expect(alCambiar).toHaveBeenCalledWith(linea.clave, { cantidad: '13' })
    await userEvent.click(screen.getByRole('button', { name: 'Quitar Tornillo M6' }))
    expect(alQuitar).toHaveBeenCalledWith(linea.clave)
  })

  it('el descuento de una línea se pide con tipo y valor', async () => {
    const linea = lineaDeProducto(TORNILLO)
    montar([linea])
    await userEvent.selectOptions(screen.getByLabelText('Tipo de descuento de Tornillo M6'), 'PESOS')
    expect(alCambiar).toHaveBeenCalledWith(linea.clave, { descuentoTipo: 'PESOS' })
    await userEvent.type(screen.getByLabelText('Descuento de Tornillo M6'), '5')
    expect(alCambiar).toHaveBeenCalledWith(linea.clave, { descuentoValor: '5' })
  })

  it('si piden más de lo que hay, avisa y ofrece vender como encargo', async () => {
    const linea = { ...lineaDeProducto(PINTURA), cantidad: '5' }
    montar([linea])
    expect(screen.getByText(/hay 3 en existencia; faltan 2/i)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /vender como encargo/i }))
    expect(alCambiar).toHaveBeenCalledWith(linea.clave, { encargo: true })
  })

  it('un encargo pide la fecha de entrega y el motivo', async () => {
    const linea = { ...lineaDeProducto(PINTURA), cantidad: '5', encargo: true }
    montar([linea])
    expect(screen.getByLabelText('Entrega estimada de Pintura blanca')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText(/motivo del encargo/i), 'x')
    expect(alCambiarMotivo).toHaveBeenCalledWith('x')
  })

  it('un producto nuevo se marca como encargo y aclara que el IVA lo pone el servidor', () => {
    const nuevo = {
      clave: 'n1', nuevo: { nombre: 'Llave de paso', precioVenta: 15000, codigoBarras: '' },
      cantidad: '1', descuentoTipo: 'PORCENTAJE', descuentoValor: '', encargo: true, entregaEstimada: '2026-12-01',
    }
    montar([nuevo])
    expect(screen.getByText('Producto nuevo — encargo')).toBeInTheDocument()
    expect(screen.getByText(/el iva lo calcula el servidor/i)).toBeInTheDocument()
  })

  it('el descuento de toda la compra tiene su propio campo', async () => {
    montar([lineaDeProducto(TORNILLO)])
    await userEvent.type(screen.getByLabelText('Descuento de toda la compra'), '1')
    expect(alCambiarDescuentoTotal).toHaveBeenCalledWith({ tipo: 'PORCENTAJE', valor: '1' })
  })
})

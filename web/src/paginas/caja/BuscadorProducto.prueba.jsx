import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { productoPorCodigo, productosPorTexto } from '../../servicios/api/caja.js'
import { PINTURA, SIN_PRECIO, TORNILLO, prepararApi } from './ayuda.js'
import BuscadorProducto from './BuscadorProducto.jsx'

vi.mock('../../servicios/api/caja.js')

const alAgregar = vi.fn()
const alCrearProducto = vi.fn()
const campo = () => screen.getByLabelText(/buscar producto/i)

beforeEach(() => {
  vi.resetAllMocks()
  prepararApi()
})
afterEach(() => {
  delete window.BarcodeDetector
  delete navigator.mediaDevices
})

const montar = () => render(<BuscadorProducto alAgregar={alAgregar} alCrearProducto={alCrearProducto} />)

describe('buscador de producto', () => {
  it('Enter con un código lo busca por código, lo agrega y deja el campo limpio y con el foco', async () => {
    montar()
    await userEvent.type(campo(), '7701234567890{Enter}')
    expect(productoPorCodigo).toHaveBeenCalledWith('7701234567890')
    await waitFor(() => expect(alAgregar).toHaveBeenCalledWith(TORNILLO))
    expect(campo()).toHaveValue('')
    expect(campo()).toHaveFocus()
  })

  it('un código que no existe dice «No encontramos ese código» y ofrece buscar por nombre o crear', async () => {
    montar()
    await userEvent.type(campo(), '000{Enter}')
    expect(await screen.findByText(/no encontramos ese código/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /buscar por nombre/i })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /crear producto \(requiere administrador\)/i }))
    expect(alCrearProducto).toHaveBeenCalledWith('000')
  })

  it('buscar por nombre lista los resultados y cada uno se agrega', async () => {
    montar()
    await userEvent.type(campo(), 'pintura{Enter}')
    await userEvent.click(await screen.findByRole('button', { name: /buscar por nombre/i }))
    expect(productosPorTexto).toHaveBeenCalledWith('pintura')
    await userEvent.click(await screen.findByRole('button', { name: 'Agregar Pintura blanca' }))
    expect(alAgregar).toHaveBeenCalledWith(PINTURA)
    expect(campo()).toHaveFocus()
  })

  it('un producto no vendible muestra el mensaje del servidor y no se agrega', async () => {
    montar()
    await userEvent.type(campo(), `${SIN_PRECIO.codigoBarras}{Enter}`)
    expect(await screen.findByText('Este producto no tiene precio de venta.')).toBeInTheDocument()
    expect(alAgregar).not.toHaveBeenCalled()
  })

  it('un fallo del servidor se anuncia', async () => {
    productoPorCodigo.mockRejectedValue(new Error('Se cayó'))
    montar()
    await userEvent.type(campo(), '1{Enter}')
    expect(await screen.findByRole('alert')).toHaveTextContent('Se cayó')
  })

  it('la cámara no se ofrece si el navegador no sabe leer códigos', () => {
    montar()
    expect(screen.queryByRole('button', { name: /escanear con la cámara/i })).not.toBeInTheDocument()
  })

  describe('con cámara', () => {
    const parar = vi.fn()
    beforeEach(() => {
      parar.mockClear()
      Object.defineProperty(navigator, 'mediaDevices', {
        configurable: true,
        value: { getUserMedia: vi.fn(async () => ({ getTracks: () => [{ stop: parar }] })) },
      })
    })

    it('se ofrece, abre un diálogo que se cierra con Escape y apaga la cámara', async () => {
      window.BarcodeDetector = class { detect = vi.fn(async () => []) }
      montar()
      await userEvent.click(screen.getByRole('button', { name: /escanear con la cámara/i }))
      const dialogo = await screen.findByRole('dialog', { name: /escanear con la cámara/i })
      expect(dialogo).toBeInTheDocument()
      await userEvent.keyboard('{Escape}')
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
      await waitFor(() => expect(parar).toHaveBeenCalled())
    })

    it('un código leído por la cámara se busca como si se hubiera escrito', async () => {
      window.BarcodeDetector = class { detect = vi.fn(async () => [{ rawValue: '7701234567890' }]) }
      montar()
      await userEvent.click(screen.getByRole('button', { name: /escanear con la cámara/i }))
      await waitFor(() => expect(alAgregar).toHaveBeenCalledWith(TORNILLO), { timeout: 3000 })
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })

    it('si no se puede abrir la cámara lo dice y deja cerrar', async () => {
      window.BarcodeDetector = class { detect = vi.fn(async () => []) }
      navigator.mediaDevices.getUserMedia.mockRejectedValue(new Error('negado'))
      montar()
      await userEvent.click(screen.getByRole('button', { name: /escanear con la cámara/i }))
      expect(await screen.findByText(/no pudimos abrir la cámara/i)).toBeInTheDocument()
      await act(async () => {})
      await userEvent.click(screen.getByRole('button', { name: /cerrar/i }))
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
  })
})

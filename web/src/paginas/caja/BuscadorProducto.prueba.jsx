import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { BusquedaAgotada, productoPorCodigo, productosPorTexto } from '../../servicios/api/caja.js'
import { PINTURA, PRODUCTOS as PRODUCTOS_DE_PRUEBA, SIN_PRECIO, TORNILLO, prepararApi } from './ayuda.js'
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

  it('dos Enter seguidos con una respuesta lenta no pierden ningún código: se vacía el campo al instante y se procesan en orden', async () => {
    let soltarPrimero
    productoPorCodigo.mockImplementationOnce(() => new Promise((resolver) => { soltarPrimero = () => resolver(TORNILLO) }))
    productoPorCodigo.mockImplementationOnce(async () => PINTURA)
    montar()
    await userEvent.type(campo(), '7701234567890{Enter}7709990001112{Enter}')
    // El segundo código ya se leyó entero y el campo quedó vacío, aunque el primero siga buscándose.
    expect(campo()).toHaveValue('')
    expect(productoPorCodigo).toHaveBeenCalledTimes(1)
    soltarPrimero()
    await waitFor(() => expect(alAgregar).toHaveBeenCalledTimes(2))
    expect(productoPorCodigo.mock.calls.map((c) => c[0])).toEqual(['7701234567890', '7709990001112'])
    expect(alAgregar.mock.calls.map((c) => c[0])).toEqual([TORNILLO, PINTURA])
  })

  it('un código desconocido sigue a la vista, con su código, aunque otro escaneo llegue detrás', async () => {
    montar()
    await userEvent.type(campo(), '000{Enter}7709990001112{Enter}')
    await waitFor(() => expect(alAgregar).toHaveBeenCalledWith(PINTURA))
    expect(screen.getByText(/no encontramos ese código: 000/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /crear producto \(requiere administrador\)/i })).toBeInTheDocument()
    // Se atiende (o se descarta) a propósito, no se borra solo.
    await userEvent.click(screen.getByRole('button', { name: /descartar el código 000/i }))
    expect(screen.queryByText(/no encontramos ese código/i)).not.toBeInTheDocument()
  })

  it('un código que no se pudo consultar (tiempo agotado) queda a la vista, la cola sigue y se puede reintentar', async () => {
    productoPorCodigo.mockRejectedValueOnce(new BusquedaAgotada())
    montar()
    await userEvent.type(campo(), '111{Enter}7709990001112{Enter}')
    await waitFor(() => expect(alAgregar).toHaveBeenCalledWith(PINTURA))
    expect(screen.getByText(/no se pudo consultar el código 111/i)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /reintentar el código 111/i }))
    await waitFor(() => expect(productoPorCodigo).toHaveBeenCalledTimes(3))
    expect(productoPorCodigo).toHaveBeenLastCalledWith('111')
    expect(screen.queryByText(/no se pudo consultar el código 111/i)).not.toBeInTheDocument()
  })

  it('varios códigos desconocidos se listan todos', async () => {
    montar()
    await userEvent.type(campo(), '111{Enter}222{Enter}')
    expect(await screen.findByText(/no encontramos ese código: 111/i)).toBeInTheDocument()
    expect(await screen.findByText(/no encontramos ese código: 222/i)).toBeInTheDocument()
    await userEvent.click(screen.getAllByRole('button', { name: /crear producto/i })[1])
    expect(alCrearProducto).toHaveBeenCalledWith('222')
  })

  it('avisa a la pantalla cuántos escaneos siguen pendientes', async () => {
    const alCambiarPendientes = vi.fn()
    let soltar
    productoPorCodigo.mockImplementationOnce(() => new Promise((resolver) => { soltar = () => resolver(TORNILLO) }))
    render(<BuscadorProducto alAgregar={alAgregar} alCrearProducto={alCrearProducto} alCambiarPendientes={alCambiarPendientes} />)
    await userEvent.type(campo(), '7701234567890{Enter}')
    expect(alCambiarPendientes).toHaveBeenLastCalledWith(1)
    soltar()
    await waitFor(() => expect(alCambiarPendientes).toHaveBeenLastCalledWith(0))
  })

  it('un código que no existe dice «No encontramos ese código» y ofrece buscar por nombre o crear', async () => {
    montar()
    await userEvent.type(campo(), '000{Enter}')
    expect(await screen.findByText(/no encontramos ese código/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /buscar por nombre/i })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /crear producto \(requiere administrador\)/i }))
    expect(alCrearProducto).toHaveBeenCalledWith('000')
  })

  describe('lo que no es un código se busca por nombre sin otro clic', () => {
    it('un nombre muestra los productos que coinciden, con una línea neutra y sin la advertencia', async () => {
      montar()
      await userEvent.type(campo(), 'pintura{Enter}')
      expect(await screen.findByRole('button', { name: 'Agregar Pintura blanca' })).toBeInTheDocument()
      expect(productoPorCodigo).toHaveBeenCalledWith('pintura')
      expect(productosPorTexto).toHaveBeenCalledWith('pintura')
      expect(screen.getByText('No es un código; estos productos coinciden con "pintura".')).toBeInTheDocument()
      expect(screen.queryByText(/no encontramos ese código/i)).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /crear producto/i })).not.toBeInTheDocument()
    })

    it('el botón «Buscar» hace lo mismo que Enter', async () => {
      montar()
      await userEvent.type(campo(), 'pintura')
      await userEvent.click(screen.getByRole('button', { name: /^buscar$/i }))
      expect(await screen.findByRole('button', { name: 'Agregar Pintura blanca' })).toBeInTheDocument()
    })

    it('cada resultado se agrega, quita la línea neutra y devuelve el foco al buscador', async () => {
      montar()
      await userEvent.type(campo(), 'pintura{Enter}')
      await userEvent.click(await screen.findByRole('button', { name: 'Agregar Pintura blanca' }))
      expect(alAgregar).toHaveBeenCalledWith(PINTURA)
      expect(screen.queryByText(/no es un código/i)).not.toBeInTheDocument()
      expect(campo()).toHaveFocus()
    })

    it('los resultados se pueden descartar', async () => {
      montar()
      await userEvent.type(campo(), 'pintura{Enter}')
      await screen.findByRole('button', { name: 'Agregar Pintura blanca' })
      await userEvent.click(screen.getByRole('button', { name: /descartar los resultados de pintura/i }))
      expect(screen.queryByText(/no es un código/i)).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Agregar Pintura blanca' })).not.toBeInTheDocument()
    })

    it('si tampoco hay productos con ese nombre, avisa «No encontramos ese código» y sigue ofreciendo buscar y crear', async () => {
      montar()
      await userEvent.type(campo(), '000{Enter}')
      expect(await screen.findByText(/no encontramos ese código: 000/i)).toBeInTheDocument()
      expect(productosPorTexto).toHaveBeenCalledWith('000')
      expect(screen.queryByText(/no es un código/i)).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: /crear producto \(requiere administrador\)/i })).toBeInTheDocument()
      await userEvent.click(screen.getByRole('button', { name: /descartar el código 000/i }))
      expect(screen.queryByText(/no encontramos ese código/i)).not.toBeInTheDocument()
    })

    it('si la búsqueda por nombre falla, queda la advertencia del código para atenderla a mano', async () => {
      productosPorTexto.mockRejectedValueOnce(new Error('Se cayó'))
      montar()
      await userEvent.type(campo(), 'pintura{Enter}')
      expect(await screen.findByText(/no encontramos ese código: pintura/i)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /buscar por nombre el código pintura/i })).toBeInTheDocument()
    })

    it('un código que existe se agrega al instante y no pide ninguna búsqueda por nombre', async () => {
      montar()
      await userEvent.type(campo(), '7701234567890{Enter}')
      await waitFor(() => expect(alAgregar).toHaveBeenCalledWith(TORNILLO))
      expect(productosPorTexto).not.toHaveBeenCalled()
    })

    it('dos códigos desconocidos con la primera búsqueda en vuelo: ninguno se pierde (resultados o advertencia)', async () => {
      let soltarAlfa
      productosPorTexto.mockImplementation(async (texto) => {
        if (texto === 'alfa') return new Promise((resolver) => { soltarAlfa = resolver })
        return PRODUCTOS_DE_PRUEBA.filter((p) => p.nombre.toLowerCase().includes(texto))
      })
      montar()
      await userEvent.type(campo(), 'alfa{Enter}pintura{Enter}')
      expect(await screen.findByRole('button', { name: 'Agregar Pintura blanca' })).toBeInTheDocument()
      // Cuando llega la de «alfa», sin coincidencias, queda como código sin encontrar.
      await act(async () => { soltarAlfa([]) })
      expect(await screen.findByText(/no encontramos ese código: alfa/i)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Agregar Pintura blanca' })).toBeInTheDocument()
    })

    it('dos nombres con coincidencias se muestran los dos, aunque el primero llegue tarde', async () => {
      let soltarAlfa
      productosPorTexto.mockImplementation(async (texto) => {
        if (texto === 'alfa') return new Promise((resolver) => { soltarAlfa = resolver })
        return [PINTURA]
      })
      montar()
      await userEvent.type(campo(), 'alfa{Enter}pintura{Enter}')
      await screen.findByRole('button', { name: 'Agregar Pintura blanca' })
      await act(async () => { soltarAlfa([TORNILLO]) })
      expect(await screen.findByRole('button', { name: 'Agregar Tornillo M6' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Agregar Pintura blanca' })).toBeInTheDocument()
    })

    it('descartar los resultados con una búsqueda del mismo nombre en vuelo: el resultado tardío no reaparece', async () => {
      montar()
      await userEvent.type(campo(), 'pintura{Enter}')
      await screen.findByRole('button', { name: 'Agregar Pintura blanca' })
      let soltar
      productosPorTexto.mockImplementationOnce(() => new Promise((resolver) => { soltar = resolver }))
      await userEvent.type(campo(), 'pintura{Enter}')
      await userEvent.click(screen.getByRole('button', { name: /descartar los resultados de pintura/i }))
      await act(async () => { soltar([PINTURA]) })
      expect(screen.queryByRole('button', { name: 'Agregar Pintura blanca' })).not.toBeInTheDocument()
    })

    it('un resultado atrasado nunca pisa a uno más nuevo del mismo nombre', async () => {
      let soltarViejo
      productosPorTexto.mockImplementationOnce(() => new Promise((resolver) => { soltarViejo = resolver }))
      productosPorTexto.mockImplementationOnce(async () => [PINTURA])
      montar()
      await userEvent.type(campo(), 'pintura{Enter}pintura{Enter}')
      await screen.findByRole('button', { name: 'Agregar Pintura blanca' })
      await act(async () => { soltarViejo([TORNILLO]) })
      expect(screen.queryByRole('button', { name: 'Agregar Tornillo M6' })).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Agregar Pintura blanca' })).toBeInTheDocument()
    })

    it('mientras la búsqueda por nombre sigue en vuelo cuenta como pendiente: no se cobra con ella a medias', async () => {
      const alCambiarPendientes = vi.fn()
      let soltar
      productosPorTexto.mockImplementationOnce(() => new Promise((resolver) => { soltar = resolver }))
      render(<BuscadorProducto alAgregar={alAgregar} alCrearProducto={alCrearProducto} alCambiarPendientes={alCambiarPendientes} />)
      await userEvent.type(campo(), 'pintura{Enter}')
      await waitFor(() => expect(productoPorCodigo).toHaveBeenCalled())
      await act(async () => {})
      expect(alCambiarPendientes).toHaveBeenLastCalledWith(1)
      await act(async () => { soltar([PINTURA]) })
      await waitFor(() => expect(alCambiarPendientes).toHaveBeenLastCalledWith(0))
    })

    it('el aviso de resultados vive en una región de estado que siempre está en la página, vacía si no hay nada', async () => {
      montar()
      const region = screen.getByRole('status', { name: /búsqueda por nombre/i })
      expect(region).toBeEmptyDOMElement()
      await userEvent.type(campo(), 'pintura{Enter}')
      await screen.findByRole('button', { name: 'Agregar Pintura blanca' })
      expect(screen.getByRole('status', { name: /búsqueda por nombre/i })).toHaveTextContent('No es un código; estos productos coinciden con "pintura".')
    })

    it('que lleguen resultados no le quita el foco al buscador', async () => {
      montar()
      await userEvent.type(campo(), 'pintura{Enter}')
      await screen.findByRole('button', { name: 'Agregar Pintura blanca' })
      expect(campo()).toHaveFocus()
    })

    it('el botón «Buscar por nombre» de un código sin encontrar sigue funcionando a mano', async () => {
      productosPorTexto.mockRejectedValueOnce(new Error('Se cayó'))
      montar()
      await userEvent.type(campo(), 'pintura{Enter}')
      await userEvent.click(await screen.findByRole('button', { name: /buscar por nombre el código pintura/i }))
      expect(productosPorTexto).toHaveBeenCalledTimes(2)
      await userEvent.click(await screen.findByRole('button', { name: 'Agregar Pintura blanca' }))
      expect(alAgregar).toHaveBeenCalledWith(PINTURA)
      expect(screen.queryByText(/no encontramos ese código/i)).not.toBeInTheDocument()
      expect(screen.queryByText(/no es un código/i)).not.toBeInTheDocument()
      expect(campo()).toHaveFocus()
    })

    it('un nombre lento no frena los escaneos que vienen detrás', async () => {
      productosPorTexto.mockImplementationOnce(() => new Promise(() => {}))
      montar()
      await userEvent.type(campo(), 'pintura{Enter}7701234567890{Enter}')
      await waitFor(() => expect(alAgregar).toHaveBeenCalledWith(TORNILLO))
    })
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

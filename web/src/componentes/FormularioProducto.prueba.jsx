/**
 * Edición de un producto (RF-15).
 *
 * La primera prueba de aquí protege una ausencia, que es lo que ninguna
 * fotografía ni ningún lint va a echar en falta: el formulario no tiene
 * campo de existencia, y no es un olvido. La existencia solo cambia por
 * movimientos de inventario, que registran el valor anterior y el
 * posterior; un formulario que la dejara escribir produciría un salto
 * sin explicación en la única tabla que permite reconstruir qué pasó con
 * una referencia. Alguien lo va a querer «completar» algún día.
 */
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import FormularioProducto from './FormularioProducto.jsx'
import { api } from '../servicios/api.js'
import { CATEGORIAS, PC, PROVEEDORES } from '../prueba/productos.js'

vi.mock('../servicios/api.js', () => ({
  api: { editarProducto: vi.fn() },
  ErrorApi: class extends Error {},
}))

function montar(extra = {}) {
  const onGuardado = vi.fn()
  const onCerrar = vi.fn()
  render(
    <FormularioProducto
      producto={{ ...PC, ...extra }}
      categorias={CATEGORIAS}
      proveedores={PROVEEDORES}
      onCerrar={onCerrar}
      onGuardado={onGuardado}
    />)
  return { onGuardado, onCerrar }
}

describe('formulario de producto', () => {
  afterEach(() => vi.clearAllMocks())

  it('no deja escribir la existencia', () => {
    montar()
    // Se muestra para que quien edita sepa cuál es, pero sin control.
    const campos = screen.getAllByRole('textbox')
      .concat(screen.getAllByRole('spinbutton'))
    for (const c of campos) {
      expect(c.getAttribute('name') ?? '').not.toMatch(/^existencia$/)
    }
    expect(screen.queryByLabelText(/^existencia$/i)).not.toBeInTheDocument()
  })

  it('pero sí la existencia mínima, que es una decisión y no un hecho', () => {
    montar()
    expect(screen.getByLabelText(/existencia mínima/i)).toBeInTheDocument()
  })

  it('manda números donde los selectores dan cadenas', async () => {
    const teclado = userEvent.setup()
    api.editarProducto.mockResolvedValue(PC)
    const { onGuardado } = montar()

    await teclado.selectOptions(screen.getByLabelText(/categoría/i), '2')
    await teclado.click(screen.getByRole('button', { name: /guardar/i }))

    await waitFor(() => expect(api.editarProducto).toHaveBeenCalled())
    const [, datos] = api.editarProducto.mock.calls[0]
    expect(datos.categoriaId).toBe(2)
    expect(datos.proveedorId).toBe(1)
    expect(typeof datos.categoriaId).toBe('number')
    expect(onGuardado).toHaveBeenCalled()
  })

  it('una existencia mínima vacía viaja como nula, no como cero', async () => {
    const teclado = userEvent.setup()
    api.editarProducto.mockResolvedValue(PC)
    montar({ existenciaMinima: null })

    await teclado.click(screen.getByRole('button', { name: /guardar/i }))
    await waitFor(() => expect(api.editarProducto).toHaveBeenCalled())
    // Cero significa «no me avises nunca»; vacío significa «no lo he
    // decidido». Convertir el segundo en el primero apaga el aviso de
    // existencia crítica sin que nadie lo haya pedido.
    expect(api.editarProducto.mock.calls[0][1].existenciaMinima).toBeNull()
  })

  it('el motivo del rechazo es el del servidor, que es el que sirve', async () => {
    const teclado = userEvent.setup()
    api.editarProducto.mockRejectedValue(
      new Error('Ya hay un producto con el código PC-001 en esta empresa.'))
    const { onGuardado } = montar()

    await teclado.click(screen.getByRole('button', { name: /guardar/i }))
    await waitFor(() => expect(screen.getByRole('alert'))
      .toHaveTextContent('Ya hay un producto con el código PC-001'))
    expect(onGuardado).not.toHaveBeenCalled()
  })

  it('tras un rechazo se puede corregir y reintentar', async () => {
    const teclado = userEvent.setup()
    api.editarProducto.mockRejectedValue(new Error('Código repetido.'))
    montar()

    await teclado.click(screen.getByRole('button', { name: /guardar/i }))
    await screen.findByRole('alert')
    // Si el botón se quedara inhabilitado, el único camino sería cerrar
    // el panel y perder lo escrito.
    expect(screen.getByRole('button', { name: /guardar/i })).toBeEnabled()
  })

  it('cancelar no guarda nada', async () => {
    const teclado = userEvent.setup()
    const { onCerrar } = montar()
    await teclado.click(screen.getByRole('button', { name: /cancelar/i }))
    expect(onCerrar).toHaveBeenCalled()
    expect(api.editarProducto).not.toHaveBeenCalled()
  })
})

/**
 * El porcentaje y el precio de venta (ADR-0025).
 *
 * PC cuesta 2.100.000 y se vende al 20 % de contado y al 25 % a crédito.
 * Lo que aquí se calcula es solo vista previa; lo que se protege es qué
 * viaja al servidor, porque es ahí donde se decide el precio: una regla
 * propia que nadie pidió saca al producto de la regla de su categoría.
 */
describe('el porcentaje y el precio de venta', () => {
  afterEach(() => vi.clearAllMocks())

  const porcentaje = () => screen.getByLabelText('% de ganancia')
  const precio = () => screen.getByLabelText('Precio de venta')
  const porcentajeCredito = () => screen.getByLabelText('% de ganancia a crédito')
  const precioCredito = () => screen.getByLabelText('Precio de venta a crédito')
  const casilla = () => screen.getByLabelText('Usar otro precio a crédito')
  const costo = () => screen.getByLabelText('Costo')

  /** Un producto sin precio a crédito propio: los dos precios coinciden. */
  const IGUALES = {
    precioCredito: 2520000, margenCredito: 20, porcentajeCredito: 20, totalCredito: 2520000,
  }
  /** Un producto vendido a una cifra escrita, sin porcentaje. */
  const ESCRITO = {
    ...IGUALES,
    precioContado: 2500000, margenContado: 19.05, porcentajeContado: null,
    precioFijoContado: 2500000, baseContado: null,
    precioCredito: 2500000, margenCredito: 19.05, porcentajeCredito: null,
    precioFijoCredito: 2500000, baseCredito: null,
  }

  async function reescribir(teclado, campo, texto) {
    await teclado.clear(campo)
    await teclado.type(campo, texto)
  }

  async function guardarYLeer(teclado) {
    api.editarProducto.mockResolvedValue(PC)
    await teclado.click(screen.getByRole('button', { name: /guardar/i }))
    await waitFor(() => expect(api.editarProducto).toHaveBeenCalled())
    return api.editarProducto.mock.calls[0][1]
  }

  it('al escribir el porcentaje se completa el precio', async () => {
    const teclado = userEvent.setup()
    montar()
    await reescribir(teclado, porcentaje(), '30')
    expect(precio()).toHaveValue(2730000)
  })

  it('al escribir el precio se completa el porcentaje', async () => {
    const teclado = userEvent.setup()
    montar()
    await reescribir(teclado, precio(), '2625000')
    expect(porcentaje()).toHaveValue(25)
  })

  it('al cambiar el costo se recalcula el que no se escribió: el precio si se escribió el porcentaje', async () => {
    const teclado = userEvent.setup()
    montar()
    await reescribir(teclado, porcentaje(), '20')
    await reescribir(teclado, costo(), '1500')
    expect(porcentaje()).toHaveValue(20)
    expect(precio()).toHaveValue(1800)
  })

  it('al cambiar el costo se recalcula el que no se escribió: el porcentaje si se escribió el precio', async () => {
    const teclado = userEvent.setup()
    montar()
    await reescribir(teclado, costo(), '1000')
    await reescribir(teclado, precio(), '1250')
    await reescribir(teclado, costo(), '1100')
    expect(precio()).toHaveValue(1250)
    expect(porcentaje()).toHaveValue(13.64)
  })

  it('lo mismo con lo guardado: un producto a precio escrito conserva la cifra si cambia el costo', async () => {
    const teclado = userEvent.setup()
    montar(ESCRITO)
    await reescribir(teclado, costo(), '2000000')
    expect(precio()).toHaveValue(2500000)
    expect(porcentaje()).toHaveValue(25)
  })

  it('lo mismo con lo guardado: un producto por porcentaje conserva el porcentaje si cambia el costo', async () => {
    const teclado = userEvent.setup()
    montar()
    await reescribir(teclado, costo(), '2000000')
    expect(porcentaje()).toHaveValue(20)
    expect(precio()).toHaveValue(2400000)
  })

  it('un precio por debajo del costo avisa con palabras y deja guardar', async () => {
    const teclado = userEvent.setup()
    montar()
    await reescribir(teclado, precio(), '1890000')
    expect(porcentaje()).toHaveValue(-10)

    expect(screen.getByRole('status')).toHaveTextContent(/por debajo del costo/i)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()

    const datos = await guardarYLeer(teclado)
    expect(datos.contado).toEqual({ precio: 1890000 })
  })

  it('un precio igual al costo no avisa de nada: no se pierde', async () => {
    const teclado = userEvent.setup()
    montar()
    await reescribir(teclado, precio(), '2100000')
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('al editar se abre con lo guardado', () => {
    montar()
    expect(porcentaje()).toHaveValue(20)
    expect(precio()).toHaveValue(2520000)
    expect(casilla()).toBeChecked()
    expect(porcentajeCredito()).toHaveValue(25)
    expect(precioCredito()).toHaveValue(2625000)
  })

  it('al editar, el porcentaje es el declarado y no el que resulta de redondear', () => {
    // Con un costo de 7 al 20 %, el precio redondea a 8 y el margen real es
    // 14,29 %. Abrir con 14,29 y guardar cambiaría el precio sin motivo.
    montar({ costo: 7, precioContado: 8, margenContado: 14.29, porcentajeContado: 20 })
    expect(porcentaje()).toHaveValue(20)
    expect(precio()).toHaveValue(8)
  })

  it('al editar un precio escrito, muestra la cifra y el margen que da', () => {
    montar(ESCRITO)
    expect(precio()).toHaveValue(2500000)
    expect(porcentaje()).toHaveValue(19.05)
    expect(casilla()).not.toBeChecked()
  })

  it('un producto sin regla abre vacío: nunca con cero', () => {
    montar({
      precioContado: null, precioCredito: null, margenContado: null, margenCredito: null,
      porcentajeContado: null, porcentajeCredito: null,
    })
    expect(porcentaje()).toHaveValue(null)
    expect(precio()).toHaveValue(null)
    expect(casilla()).not.toBeChecked()
  })

  it('con el crédito igual al contado, sus campos no se muestran', () => {
    montar(IGUALES)
    expect(casilla()).not.toBeChecked()
    expect(screen.queryByLabelText('Precio de venta a crédito')).not.toBeInTheDocument()
  })

  it('solo se envía lo que se escribió: el porcentaje', async () => {
    const teclado = userEvent.setup()
    montar()
    await reescribir(teclado, porcentaje(), '30')
    const datos = await guardarYLeer(teclado)
    expect(datos.contado).toEqual({ porcentaje: 30 })
  })

  it('solo se envía lo que se escribió: el precio', async () => {
    const teclado = userEvent.setup()
    montar()
    await reescribir(teclado, precio(), '2625000')
    const datos = await guardarYLeer(teclado)
    expect(datos.contado).toEqual({ precio: 2625000 })
  })

  it('el último que se escribió es el que manda', async () => {
    const teclado = userEvent.setup()
    montar()
    await reescribir(teclado, precio(), '2625000')
    await reescribir(teclado, porcentaje(), '30')
    const datos = await guardarYLeer(teclado)
    expect(datos.contado).toEqual({ porcentaje: 30 })
  })

  it('guardar sin tocar los precios no manda ningún precio', async () => {
    // Si mandara el porcentaje con el que abrió, cambiar el nombre sacaría
    // al producto de la regla de su categoría.
    const teclado = userEvent.setup()
    montar()
    await reescribir(teclado, screen.getByLabelText('Nombre'), 'Otro nombre')
    const datos = await guardarYLeer(teclado)
    expect(datos).not.toHaveProperty('contado')
    expect(datos).not.toHaveProperty('credito')
    expect(datos).not.toHaveProperty('creditoIgualAlContado')
  })

  it('cambiar solo el costo tampoco: el recalculado no lo escribió nadie', async () => {
    const teclado = userEvent.setup()
    montar()
    await reescribir(teclado, costo(), '2000000')
    const datos = await guardarYLeer(teclado)
    expect(datos).not.toHaveProperty('contado')
    expect(datos).not.toHaveProperty('credito')
    expect(datos).not.toHaveProperty('creditoIgualAlContado')
  })

  it('con la casilla sin marcar, el crédito sigue al contado sin mandar nada propio', async () => {
    const teclado = userEvent.setup()
    montar(IGUALES)
    await reescribir(teclado, porcentaje(), '30')
    const datos = await guardarYLeer(teclado)
    expect(datos.contado).toEqual({ porcentaje: 30 })
    expect(datos).not.toHaveProperty('credito')
    expect(datos).not.toHaveProperty('creditoIgualAlContado')
  })

  it('con la casilla marcada se envía lo que se escribió en el crédito', async () => {
    const teclado = userEvent.setup()
    montar(IGUALES)
    await teclado.click(casilla())
    await reescribir(teclado, precioCredito(), '2800000')
    const datos = await guardarYLeer(teclado)
    expect(datos.credito).toEqual({ precio: 2800000 })
    expect(datos).not.toHaveProperty('contado')
    expect(datos).not.toHaveProperty('creditoIgualAlContado')
  })

  it('el crédito se completa igual que el contado', async () => {
    const teclado = userEvent.setup()
    montar()
    await reescribir(teclado, porcentajeCredito(), '30')
    expect(precioCredito()).toHaveValue(2730000)
  })

  it('cambiar el contado con la casilla marcada no mueve el crédito', async () => {
    // Lo que se ve en el crédito es lo que debe quedar: sin esto, el
    // servidor lo igualaría al contado por omisión.
    const teclado = userEvent.setup()
    montar()
    await reescribir(teclado, porcentaje(), '30')
    const datos = await guardarYLeer(teclado)
    expect(datos.contado).toEqual({ porcentaje: 30 })
    expect(datos).not.toHaveProperty('credito')
    expect(datos.creditoIgualAlContado).toBe(false)
  })

  it('desmarcar la casilla iguala el crédito al contado aunque el contado no cambie', async () => {
    const teclado = userEvent.setup()
    montar()
    await teclado.click(casilla())
    const datos = await guardarYLeer(teclado)
    expect(datos).not.toHaveProperty('contado')
    expect(datos).not.toHaveProperty('credito')
    expect(datos.creditoIgualAlContado).toBe(true)
  })

  it('lo escrito en el crédito no viaja si después se desmarca la casilla', async () => {
    const teclado = userEvent.setup()
    montar()
    await reescribir(teclado, porcentajeCredito(), '40')
    await teclado.click(casilla())
    const datos = await guardarYLeer(teclado)
    expect(datos).not.toHaveProperty('credito')
    expect(datos.creditoIgualAlContado).toBe(true)
  })

  describe('lo que no se puede guardar se dice al lado del campo', () => {
    /** El mensaje al que apunta el campo con aria-describedby. */
    function mensajeDe(campo) {
      const id = campo.getAttribute('aria-describedby')
      return id ? document.getElementById(id) : null
    }

    async function noSeEnvia(teclado) {
      await teclado.click(screen.getByRole('button', { name: /guardar/i }))
      expect(api.editarProducto).not.toHaveBeenCalled()
    }

    it('un porcentaje de cero remite a escribir el precio', async () => {
      const teclado = userEvent.setup()
      montar()
      await reescribir(teclado, porcentaje(), '0')
      expect(precio()).toHaveValue(null)
      expect(porcentaje()).toBeInvalid()
      expect(mensajeDe(porcentaje())).toHaveTextContent(/mayor que cero.*escribe el precio/i)
      await noSeEnvia(teclado)
    })

    it('un porcentaje negativo también', async () => {
      const teclado = userEvent.setup()
      montar()
      await reescribir(teclado, porcentaje(), '-5')
      expect(mensajeDe(porcentaje())).toHaveTextContent(/mayor que cero/i)
      await noSeEnvia(teclado)
    })

    it('un porcentaje con tres decimales', async () => {
      const teclado = userEvent.setup()
      montar()
      await reescribir(teclado, porcentaje(), '20.555')
      expect(mensajeDe(porcentaje())).toHaveTextContent(/dos decimales/i)
      await noSeEnvia(teclado)
    })

    it('un porcentaje que no cabe', async () => {
      const teclado = userEvent.setup()
      montar()
      await reescribir(teclado, porcentaje(), '9900')
      expect(mensajeDe(porcentaje())).toHaveTextContent(/9\.899,99/)
      await noSeEnvia(teclado)
    })

    it('un porcentaje que redondea a cero pesos', async () => {
      const teclado = userEvent.setup()
      montar({ costo: 0.40 })
      await reescribir(teclado, porcentaje(), '20')
      expect(mensajeDe(porcentaje())).toHaveTextContent(/0 pesos.*escribe el precio/i)
      await noSeEnvia(teclado)
    })

    it('un precio de cero', async () => {
      const teclado = userEvent.setup()
      montar()
      await reescribir(teclado, precio(), '0')
      expect(porcentaje()).toHaveValue(null)
      expect(mensajeDe(precio())).toHaveTextContent(/mayor que cero/i)
      await noSeEnvia(teclado)
    })

    it('un precio con centavos', async () => {
      const teclado = userEvent.setup()
      montar()
      await reescribir(teclado, precio(), '1250.50')
      expect(mensajeDe(precio())).toHaveTextContent(/pesos enteros/i)
      await noSeEnvia(teclado)
    })

    it('borrar lo que se escribió pide uno de los dos', async () => {
      const teclado = userEvent.setup()
      montar()
      await teclado.clear(precio())
      expect(mensajeDe(precio())).toHaveTextContent(/escribe el porcentaje o el precio/i)
      await noSeEnvia(teclado)
    })

    it('el error del crédito va en el crédito', async () => {
      const teclado = userEvent.setup()
      montar()
      await reescribir(teclado, porcentajeCredito(), '0')
      expect(mensajeDe(porcentajeCredito())).toHaveTextContent(/mayor que cero/i)
      expect(mensajeDe(porcentaje())).not.toBeInTheDocument()
      await noSeEnvia(teclado)
    })

    it('un error en el crédito no impide guardar si la casilla se desmarcó', async () => {
      const teclado = userEvent.setup()
      montar()
      await reescribir(teclado, porcentajeCredito(), '0')
      await teclado.click(casilla())
      await guardarYLeer(teclado)
      expect(api.editarProducto).toHaveBeenCalled()
    })

    it('al corregirlo el mensaje se va y se puede guardar', async () => {
      const teclado = userEvent.setup()
      montar()
      await reescribir(teclado, porcentaje(), '0')
      await reescribir(teclado, porcentaje(), '20')
      expect(porcentaje()).toBeValid()
      expect(mensajeDe(porcentaje())).not.toBeInTheDocument()
      const datos = await guardarYLeer(teclado)
      expect(datos.contado).toEqual({ porcentaje: 20 })
    })

    it('el rechazo del servidor sigue saliendo arriba y conserva lo escrito', async () => {
      const teclado = userEvent.setup()
      api.editarProducto.mockRejectedValue(new Error('El precio no cabe.'))
      montar()
      await reescribir(teclado, precio(), '2625000')
      await teclado.click(screen.getByRole('button', { name: /guardar/i }))
      expect(await screen.findByRole('alert')).toHaveTextContent('El precio no cabe.')
      expect(precio()).toHaveValue(2625000)
    })
  })

  it('cada campo tiene su etiqueta visible', () => {
    montar()
    const formulario = screen.getByRole('dialog')
    for (const texto of ['% de ganancia', 'Precio de venta', 'Usar otro precio a crédito',
      '% de ganancia a crédito', 'Precio de venta a crédito']) {
      expect(within(formulario).getByText(texto)).toBeVisible()
    }
  })
})

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
  api: { editarProducto: vi.fn(), crearProducto: vi.fn(), producto: vi.fn() },
  ErrorApi: class extends Error {},
}))

function montar(extra = {}) {
  const onGuardado = vi.fn()
  const onCerrar = vi.fn()
  const onActualizado = vi.fn()
  render(
    <FormularioProducto
      producto={{ ...PC, ...extra }}
      categorias={CATEGORIAS}
      proveedores={PROVEEDORES}
      onCerrar={onCerrar}
      onGuardado={onGuardado}
      onActualizado={onActualizado}
    />)
  return { onGuardado, onCerrar, onActualizado }
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

  describe('si el producto cambió mientras se editaba (I1)', () => {
    /** Lo que `pedir` lanza ante un 409: el mensaje del servidor y el estado. */
    const conflicto = () => Object.assign(
      new Error('Otra persona o un proceso modificó este producto antes de que guardaras.'),
      { estado: 409, tipo: 'urn:sgip:producto-cambio' })

    it('manda la versión de la ficha que se abrió', async () => {
      const teclado = userEvent.setup()
      api.editarProducto.mockResolvedValue(PC)
      montar({ version: 7 })

      await teclado.click(screen.getByRole('button', { name: /guardar/i }))

      await waitFor(() => expect(api.editarProducto).toHaveBeenCalled())
      expect(api.editarProducto.mock.calls[0][1].version).toBe(7)
    })

    it('lo explica con palabras sencillas y no pierde lo escrito', async () => {
      const teclado = userEvent.setup()
      api.editarProducto.mockRejectedValue(conflicto())
      const { onGuardado } = montar()
      const nombre = screen.getByLabelText(/nombre/i)
      await teclado.clear(nombre)
      await teclado.type(nombre, 'Portátil corregido')

      await teclado.click(screen.getByRole('button', { name: /guardar/i }))

      const aviso = await screen.findByRole('alert')
      expect(aviso).toHaveTextContent(/cambió mientras lo editabas/i)
      expect(aviso).toHaveTextContent(/lo que escribiste sigue aquí/i)
      expect(screen.getByLabelText(/nombre/i)).toHaveValue('Portátil corregido')
      expect(onGuardado).not.toHaveBeenCalled()
      expect(screen.getByRole('button', { name: /guardar/i })).toBeEnabled()
    })

    it('ofrece ver los datos actuales y, al hacerlo, el formulario los muestra y guarda con su versión', async () => {
      const teclado = userEvent.setup()
      api.editarProducto.mockRejectedValueOnce(conflicto())
      api.producto.mockResolvedValue({ ...PC, nombre: 'Portátil del servidor', costo: 2500000, version: 4 })
      montar()
      await teclado.click(screen.getByRole('button', { name: /guardar/i }))
      await screen.findByRole('alert')

      await teclado.click(screen.getByRole('button', { name: /ver los datos actuales/i }))

      await waitFor(() => expect(screen.getByLabelText(/nombre/i)).toHaveValue('Portátil del servidor'))
      expect(api.producto).toHaveBeenCalledWith(PC.productoId)
      expect(screen.getByLabelText(/costo/i)).toHaveValue(2500000)
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()

      api.editarProducto.mockResolvedValueOnce(PC)
      await teclado.click(screen.getByRole('button', { name: /guardar/i }))
      await waitFor(() => expect(api.editarProducto).toHaveBeenCalledTimes(2))
      expect(api.editarProducto.mock.calls[1][1].version).toBe(4)
    })

    it('tras traer los datos actuales el foco queda en el formulario y no se pierde en la página', async () => {
      const teclado = userEvent.setup()
      api.editarProducto.mockRejectedValueOnce(conflicto())
      api.producto.mockResolvedValue({ ...PC, nombre: 'Portátil del servidor', version: 4 })
      montar()
      await teclado.click(screen.getByRole('button', { name: /guardar/i }))
      await screen.findByRole('alert')

      await teclado.click(screen.getByRole('button', { name: /ver los datos actuales/i }))

      // El botón vivía dentro del aviso: al desaparecer el aviso, el foco caía a <body>.
      await waitFor(() => expect(screen.getByLabelText(/nombre/i)).toHaveFocus())
    })

    it('avisa de los datos actuales a quien lo abrió, para que el catálogo no muestre los viejos', async () => {
      const teclado = userEvent.setup()
      api.editarProducto.mockRejectedValueOnce(conflicto())
      const actual = { ...PC, nombre: 'Portátil del servidor', version: 4 }
      api.producto.mockResolvedValue(actual)
      const { onActualizado } = montar()
      await teclado.click(screen.getByRole('button', { name: /guardar/i }))
      await screen.findByRole('alert')

      await teclado.click(screen.getByRole('button', { name: /ver los datos actuales/i }))

      await waitFor(() => expect(onActualizado).toHaveBeenCalledWith(actual))
    })

    it('si no se pueden traer los datos actuales, no avisa de nada', async () => {
      const teclado = userEvent.setup()
      api.editarProducto.mockRejectedValue(conflicto())
      api.producto.mockRejectedValue(new Error('El producto no existe.'))
      const { onActualizado } = montar()
      await teclado.click(screen.getByRole('button', { name: /guardar/i }))
      await screen.findByRole('alert')

      await teclado.click(screen.getByRole('button', { name: /ver los datos actuales/i }))

      await screen.findByText(/el producto no existe/i)
      expect(onActualizado).not.toHaveBeenCalled()
    })

    it('un 409 por un código repetido dice el motivo del servidor y no habla de cambios', async () => {
      const teclado = userEvent.setup()
      api.editarProducto.mockRejectedValue(Object.assign(
        new Error('Ya existe un producto con el código PC-002.'), { estado: 409, tipo: 'about:blank' }))
      montar()

      await teclado.click(screen.getByRole('button', { name: /guardar/i }))

      const aviso = await screen.findByRole('alert')
      expect(aviso).toHaveTextContent('Ya existe un producto con el código PC-002.')
      expect(aviso).not.toHaveTextContent(/cambió mientras lo editabas/i)
      expect(screen.queryByRole('button', { name: /ver los datos actuales/i })).not.toBeInTheDocument()
    })

    it('un rechazo que no es de versión no ofrece recargar', async () => {
      const teclado = userEvent.setup()
      api.editarProducto.mockRejectedValue(new Error('Código repetido.'))
      montar()

      await teclado.click(screen.getByRole('button', { name: /guardar/i }))
      await screen.findByRole('alert')

      expect(screen.queryByRole('button', { name: /ver los datos actuales/i })).not.toBeInTheDocument()
    })

    it('si no se pueden traer los datos actuales lo dice y conserva lo escrito', async () => {
      const teclado = userEvent.setup()
      api.editarProducto.mockRejectedValue(conflicto())
      api.producto.mockRejectedValue(new Error('El producto no existe.'))
      montar()
      const nombre = screen.getByLabelText(/nombre/i)
      await teclado.clear(nombre)
      await teclado.type(nombre, 'Lo que escribí')
      await teclado.click(screen.getByRole('button', { name: /guardar/i }))
      await screen.findByRole('alert')

      await teclado.click(screen.getByRole('button', { name: /ver los datos actuales/i }))

      expect(await screen.findByText(/el producto no existe/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/nombre/i)).toHaveValue('Lo que escribí')
    })
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

  const porcentaje = () => screen.getByLabelText('% de ganancia de contado')
  const precio = () => screen.getByLabelText('Precio de venta de contado')
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

  /** Lo que las regiones de aviso están diciendo ahora. */
  const avisos = () => screen.getAllByRole('status').filter((r) => r.textContent !== '')

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

    expect(avisos()).toHaveLength(1)
    expect(avisos()[0]).toHaveTextContent(/por debajo del costo/i)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()

    const datos = await guardarYLeer(teclado)
    expect(datos.contado).toEqual({ precio: 1890000 })
  })

  it('un precio igual al costo no avisa de nada: no se pierde', async () => {
    const teclado = userEvent.setup()
    montar()
    await reescribir(teclado, precio(), '2100000')
    expect(avisos()).toHaveLength(0)
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

  describe('la casilla compara la clase de regla, no solo la cifra', () => {
    const MIL = { costo: 1000, precioContado: 1200, margenContado: 20, porcentajeContado: 20 }

    it('un 20 % y una cifra escrita de 1.200 no son lo mismo aunque hoy coincidan', () => {
      // Si abriera como «igual», cambiar el contado le borraría el precio
      // escrito sin que nadie lo viera.
      montar({
        ...MIL, precioCredito: 1200, margenCredito: 20,
        porcentajeCredito: null, precioFijoCredito: 1200,
      })
      expect(casilla()).toBeChecked()
      expect(precioCredito()).toHaveValue(1200)
    })

    it('al revés, tampoco', () => {
      montar({
        costo: 1000, precioContado: 1200, margenContado: 20, porcentajeContado: null,
        precioFijoContado: 1200, precioCredito: 1200, margenCredito: 20, porcentajeCredito: 20,
        precioFijoCredito: null,
      })
      expect(casilla()).toBeChecked()
    })

    it('dos porcentajes que redondean a la misma cifra tampoco', () => {
      montar({
        ...MIL, precioCredito: 1200, margenCredito: 20,
        porcentajeCredito: 20.01, precioFijoCredito: null,
      })
      expect(casilla()).toBeChecked()
    })

    it('el mismo porcentaje en los dos sí es lo mismo', () => {
      montar({ ...MIL, precioCredito: 1200, margenCredito: 20, porcentajeCredito: 20 })
      expect(casilla()).not.toBeChecked()
    })

    it('la misma cifra escrita en los dos sí es lo mismo', () => {
      montar(ESCRITO)
      expect(casilla()).not.toBeChecked()
    })
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

    it('un precio de 13 cifras no se podría vender', async () => {
      const teclado = userEvent.setup()
      montar()
      await reescribir(teclado, precio(), '1234567890123')
      expect(mensajeDe(precio())).toHaveTextContent(/12 cifras/)
      await noSeEnvia(teclado)
    })

    it('un precio de 12 cifras sí', async () => {
      const teclado = userEvent.setup()
      montar()
      await reescribir(teclado, precio(), '123456789012')
      expect(precio()).toBeValid()
      const datos = await guardarYLeer(teclado)
      expect(datos.contado).toEqual({ precio: 123456789012 })
    })

    it('un porcentaje cuyo precio pasa de 12 cifras tampoco', async () => {
      const teclado = userEvent.setup()
      montar({ costo: 900000000000 })
      await reescribir(teclado, porcentaje(), '200')
      expect(mensajeDe(porcentaje())).toHaveTextContent(/12 cifras/)
      await noSeEnvia(teclado)
    })

    it('el error se anuncia: está en una región viva que ya existía', async () => {
      const teclado = userEvent.setup()
      montar()
      const antes = screen.getAllByRole('status')
      await reescribir(teclado, porcentaje(), '0')
      const region = mensajeDe(porcentaje())
      expect(antes).toContain(region)
      expect(region).toHaveTextContent(/mayor que cero/i)
    })

    it('el aviso bajo el costo se anuncia: su región ya existía', async () => {
      // Una región viva que aparece junto con su texto no se lee en todos
      // los lectores de pantalla: tiene que estar antes, vacía.
      const teclado = userEvent.setup()
      montar()
      const antes = screen.getAllByRole('status')
      await reescribir(teclado, precio(), '1890000')
      expect(antes).toContain(avisos()[0])
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
    for (const texto of ['% de ganancia de contado', 'Precio de venta de contado', 'Usar otro precio a crédito',
      '% de ganancia a crédito', 'Precio de venta a crédito']) {
      expect(within(formulario).getByText(texto)).toBeVisible()
    }
  })
})

/**
 * Crear un producto (P-05): el mismo formulario, con lo que cambia al nacer.
 *
 * `SolicitudProducto` exige categoría, proveedor, código, nombre y costo, y
 * ServicioPrecios exige el contado. La existencia inicial es opcional y es
 * lo único de la existencia que se escribe a mano: el backend la convierte en
 * un movimiento de inventario.
 */
describe('crear un producto', () => {
  afterEach(() => vi.clearAllMocks())

  function montarAlta() {
    const onGuardado = vi.fn()
    render(
      <FormularioProducto
        modo="crear"
        categorias={CATEGORIAS}
        proveedores={PROVEEDORES}
        onCerrar={vi.fn()}
        onGuardado={onGuardado}
      />)
    return { onGuardado }
  }

  const crear = () => screen.getByRole('button', { name: /crear producto/i })
  const campo = (nombre) => screen.getByLabelText(nombre)
  function mensajeDe(control) {
    const id = control.getAttribute('aria-describedby')
    return id ? document.getElementById(id) : null
  }

  async function llenarLoObligatorio(teclado) {
    await teclado.type(campo('Nombre'), 'Mouse óptico')
    await teclado.selectOptions(campo('Categoría'), '2')
    await teclado.selectOptions(campo('Proveedor'), '1')
    await teclado.type(campo('Código'), 'MOU-1')
    await teclado.type(campo('Costo'), '1000')
    await teclado.type(campo('% de ganancia de contado'), '20')
  }

  it('se titula y se nombra por lo que hace', () => {
    montarAlta()
    expect(screen.getByRole('dialog', { name: 'Nuevo producto' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Nuevo producto' })).toBeInTheDocument()
    expect(crear()).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /guardar cambios/i })).not.toBeInTheDocument()
  })

  it('al editar se titula como siempre', () => {
    montar()
    expect(screen.getByRole('dialog', { name: `Editar ${PC.nombre}` })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Editar producto' })).toBeInTheDocument()
  })

  it('abre vacío y sin ningún error', () => {
    montarAlta()
    expect(campo('Nombre')).toHaveValue('')
    expect(campo('Costo')).toHaveValue(null)
    expect(campo('% de ganancia de contado')).toHaveValue(null)
    expect(campo('Precio de venta de contado')).toHaveValue(null)
    expect(campo('Usar otro precio a crédito')).not.toBeChecked()
    expect(screen.getAllByRole('status').filter((r) => r.textContent !== '')).toHaveLength(0)
    expect(document.querySelector('[aria-invalid="true"]')).toBeNull()
  })

  it.each([
    ['Nombre', /nombre/i],
    ['Categoría', /categoría/i],
    ['Proveedor', /proveedor/i],
    ['Código', /código/i],
    ['Costo', /costo/i],
    ['% de ganancia de contado', /porcentaje o el precio/i],
  ])('%s es obligatorio y lo dice con palabras al lado del campo', async (nombre, texto) => {
    const teclado = userEvent.setup()
    montarAlta()
    await teclado.click(crear())
    expect(api.crearProducto).not.toHaveBeenCalled()
    expect(campo(nombre)).toHaveAttribute('aria-invalid', 'true')
    expect(mensajeDe(campo(nombre))).toHaveTextContent(texto)
    expect(mensajeDe(campo(nombre))).toHaveClass('error')
  })

  it('el contado es obligatorio aunque nadie lo haya tocado, con todo lo demás lleno', async () => {
    const teclado = userEvent.setup()
    montarAlta()
    await teclado.type(campo('Nombre'), 'Mouse')
    await teclado.selectOptions(campo('Categoría'), '2')
    await teclado.selectOptions(campo('Proveedor'), '1')
    await teclado.type(campo('Código'), 'MOU-1')
    await teclado.type(campo('Costo'), '1000')
    await teclado.click(crear())
    expect(api.crearProducto).not.toHaveBeenCalled()
    expect(mensajeDe(campo('% de ganancia de contado'))).toHaveTextContent(/porcentaje o el precio/i)
    expect(campo('% de ganancia de contado')).toHaveFocus()
  })

  it('al intentar crear, el foco va al primer campo con problema', async () => {
    const teclado = userEvent.setup()
    montarAlta()
    await teclado.click(crear())
    expect(campo('Nombre')).toHaveFocus()
  })

  it('el mensaje se va al corregir', async () => {
    const teclado = userEvent.setup()
    montarAlta()
    await teclado.click(crear())
    await teclado.type(campo('Nombre'), 'Mouse')
    expect(campo('Nombre')).not.toHaveAttribute('aria-invalid')
    expect(mensajeDe(campo('Nombre'))).toBeNull()
  })

  it.each([['0'], ['-5'], ['1.234']])('un costo de %s se rechaza al lado del campo', async (costo) => {
    const teclado = userEvent.setup()
    montarAlta()
    await teclado.type(campo('Costo'), costo)
    await teclado.click(crear())
    expect(mensajeDe(campo('Costo'))).toHaveTextContent(/costo/i)
    expect(api.crearProducto).not.toHaveBeenCalled()
  })

  it.each([['-1'], ['2.5']])('una existencia inicial de %s se rechaza al lado del campo', async (valor) => {
    const teclado = userEvent.setup()
    montarAlta()
    await llenarLoObligatorio(teclado)
    await teclado.type(campo('Existencia inicial'), valor)
    await teclado.click(crear())
    expect(mensajeDe(campo('Existencia inicial'))).toHaveTextContent(/cantidad entera/i)
    expect(api.crearProducto).not.toHaveBeenCalled()
  })

  it('manda exactamente lo que pide la API: números, lo escrito y sin la bandera de crédito', async () => {
    const teclado = userEvent.setup()
    api.crearProducto.mockResolvedValue(PC)
    const { onGuardado } = montarAlta()
    await llenarLoObligatorio(teclado)
    expect(campo('Precio de venta de contado')).toHaveValue(1200)
    await teclado.type(campo('Existencia inicial'), '7')
    await teclado.click(crear())

    await waitFor(() => expect(onGuardado).toHaveBeenCalledWith(PC))
    expect(api.crearProducto.mock.calls[0][0]).toEqual({
      categoriaId: 2, proveedorId: 1, codigo: 'MOU-1', sku: '', codigoBarras: '',
      nombre: 'Mouse óptico', presentacion: '', descripcion: '', costo: '1000',
      existencia: 7, existenciaMinima: null, contado: { porcentaje: 20 },
    })
    expect(api.editarProducto).not.toHaveBeenCalled()
  })

  it('sin existencia inicial viaja nula: el backend no escribe movimiento', async () => {
    const teclado = userEvent.setup()
    api.crearProducto.mockResolvedValue(PC)
    montarAlta()
    await llenarLoObligatorio(teclado)
    await teclado.click(crear())
    await waitFor(() => expect(api.crearProducto).toHaveBeenCalled())
    expect(api.crearProducto.mock.calls[0][0].existencia).toBeNull()
  })

  it('con la casilla marcada el crédito es obligatorio: el servidor lo igualaría sin avisar', async () => {
    const teclado = userEvent.setup()
    montarAlta()
    await llenarLoObligatorio(teclado)
    await teclado.click(campo('Usar otro precio a crédito'))
    await teclado.click(crear())
    expect(api.crearProducto).not.toHaveBeenCalled()
    expect(mensajeDe(campo('% de ganancia a crédito'))).toHaveTextContent(/porcentaje o el precio/i)
  })

  it('con la casilla marcada y el crédito escrito lo envía', async () => {
    const teclado = userEvent.setup()
    api.crearProducto.mockResolvedValue(PC)
    montarAlta()
    await llenarLoObligatorio(teclado)
    await teclado.click(campo('Usar otro precio a crédito'))
    await teclado.type(campo('Precio de venta a crédito'), '1300')
    await teclado.click(crear())
    await waitFor(() => expect(api.crearProducto).toHaveBeenCalled())
    const datos = api.crearProducto.mock.calls[0][0]
    expect(datos.credito).toEqual({ precio: 1300 })
    expect(datos).not.toHaveProperty('creditoIgualAlContado')
  })

  it('el rechazo del servidor sale arriba y se puede reintentar', async () => {
    const teclado = userEvent.setup()
    api.crearProducto.mockRejectedValue(new Error('Ya existe un producto con el código MOU-1.'))
    montarAlta()
    await llenarLoObligatorio(teclado)
    await teclado.click(crear())
    expect(await screen.findByRole('alert')).toHaveTextContent('Ya existe un producto')
    expect(crear()).toBeEnabled()
    expect(campo('Nombre')).toHaveValue('Mouse óptico')
  })

  it('el rechazo del servidor se trae a la vista y recibe el foco: el botón queda lejos del aviso', async () => {
    // jsdom no tiene scrollIntoView; se pone aquí para comprobar que se pide.
    const desplazar = vi.fn()
    Element.prototype.scrollIntoView = desplazar
    try {
      const teclado = userEvent.setup()
      api.crearProducto.mockRejectedValue(new Error('Ya existe un producto con el código MOU-1.'))
      montarAlta()
      await llenarLoObligatorio(teclado)
      await teclado.click(crear())
      const aviso = await screen.findByRole('alert')
      await waitFor(() => expect(aviso).toHaveFocus())
      expect(desplazar).toHaveBeenCalled()
      expect(campo('Nombre')).toHaveValue('Mouse óptico')
    } finally {
      delete Element.prototype.scrollIntoView
    }
  })

  it('al editar no hay existencia inicial, ni se envía', async () => {
    const teclado = userEvent.setup()
    api.editarProducto.mockResolvedValue(PC)
    montar()
    expect(screen.queryByLabelText('Existencia inicial')).not.toBeInTheDocument()
    await teclado.click(screen.getByRole('button', { name: /guardar/i }))
    await waitFor(() => expect(api.editarProducto).toHaveBeenCalled())
    expect(api.editarProducto.mock.calls[0][1]).not.toHaveProperty('existencia')
  })

  it('sin categorías dice por qué no hay de dónde elegir', () => {
    render(
      <FormularioProducto modo="crear" categorias={[]} proveedores={PROVEEDORES}
        onCerrar={vi.fn()} onGuardado={vi.fn()} />)
    expect(mensajeDe(campo('Categoría'))).toHaveTextContent(/no hay categorías/i)
  })
})

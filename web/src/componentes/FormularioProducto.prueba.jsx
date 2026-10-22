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
import { render, screen, waitFor } from '@testing-library/react'
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

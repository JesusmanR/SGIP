/**
 * El formulario de producto como diálogo: su estructura y el teclado.
 *
 * Las reglas de precio y de versión están en FormularioProducto.prueba.jsx.
 */
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import FormularioProducto from './FormularioProducto.jsx'
import { api } from '../servicios/api.js'
import { CATEGORIAS, PC, PROVEEDORES } from '../prueba/productos.js'

vi.mock('../servicios/api.js', () => ({
  api: { editarProducto: vi.fn(), crearProducto: vi.fn(), producto: vi.fn() },
  ErrorApi: class extends Error {},
}))

function montar(props = {}) {
  const onCerrar = vi.fn()
  render(
    <FormularioProducto
      producto={PC} categorias={CATEGORIAS} proveedores={PROVEEDORES}
      onCerrar={onCerrar} onGuardado={vi.fn()} {...props}
    />)
  return { onCerrar }
}

describe('formulario de producto como diálogo', () => {
  afterEach(() => vi.clearAllMocks())

  it('agrupa los campos en secciones con nombre', () => {
    montar()
    for (const nombre of ['Identificación', 'Precio de venta', 'Existencias']) {
      expect(screen.getByRole('group', { name: nombre })).toBeInTheDocument()
    }
  })

  it('el precio se explica dentro de su sección: porcentaje y precio se completan entre sí', () => {
    montar()
    const precio = screen.getByRole('group', { name: 'Precio de venta' })
    expect(within(precio).getByLabelText('% de ganancia de contado')).toBeInTheDocument()
    expect(within(precio).getByLabelText('Precio de venta de contado')).toBeInTheDocument()
    expect(within(precio).getByText(/el otro se completa/i)).toBeInTheDocument()
    expect(screen.queryByText(/margen automático|margen por defecto/i)).not.toBeInTheDocument()
  })

  it('en el alta, el título del diálogo es «Nuevo producto» y no hay existencia actual', () => {
    montar({ modo: 'crear', producto: undefined })
    expect(screen.getByRole('dialog', { name: 'Nuevo producto' })).toBeInTheDocument()
    expect(screen.getByLabelText('Existencia inicial')).toBeInTheDocument()
  })

  it('Cancelar está junto a Guardar, al pie, y es el único', () => {
    montar()
    const pie = screen.getByRole('button', { name: 'Guardar cambios' }).parentElement
    expect(within(pie).getByRole('button', { name: 'Cancelar' })).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Cancelar' })).toHaveLength(1)
  })

  it('Cancelar cierra sin guardar', async () => {
    const { onCerrar } = montar()
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(onCerrar).toHaveBeenCalledTimes(1)
    expect(api.editarProducto).not.toHaveBeenCalled()
  })

  it('al abrir, el foco entra en el diálogo', () => {
    montar()
    expect(screen.getByRole('dialog')).toContainElement(document.activeElement)
  })

  it('Escape lo cierra', async () => {
    const { onCerrar } = montar()
    await userEvent.keyboard('{Escape}')
    expect(onCerrar).toHaveBeenCalledTimes(1)
  })

  it('Tab y Mayús+Tab dan la vuelta dentro del diálogo', async () => {
    montar()
    const dialogo = screen.getByRole('dialog')
    for (let i = 0; i < 40; i += 1) {
      await userEvent.tab()
      expect(dialogo).toContainElement(document.activeElement)
    }
    await userEvent.tab({ shift: true })
    expect(dialogo).toContainElement(document.activeElement)
  })

  it('el error de guardado se anuncia y no tapa el formulario', async () => {
    api.editarProducto.mockRejectedValue(new Error('El código ya existe.'))
    montar()
    await userEvent.click(screen.getByRole('button', { name: 'Guardar cambios' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('El código ya existe.')
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeEnabled()
  })
})

/**
 * Administración de categorías (RF-19).
 *
 * Desactivar no borra, y el servidor se niega si la categoría todavía
 * sostiene productos activos: ese mensaje es el que sirve y se muestra
 * tal cual.
 */
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import Categorias from './Categorias.jsx'
import { api } from '../servicios/api.js'
import { CATEGORIAS } from '../prueba/productos.js'

vi.mock('../servicios/api.js', () => ({
  api: {
    categorias: vi.fn(),
    crearCategoria: vi.fn(),
    editarCategoria: vi.fn(),
    desactivarCategoria: vi.fn(),
  },
}))

function montar() {
  const onCerrar = vi.fn()
  const onCambio = vi.fn()
  render(<Categorias onCerrar={onCerrar} onCambio={onCambio} />)
  return { onCerrar, onCambio }
}
const filaDe = (nombre) => screen.getAllByRole('row').find((f) => f.textContent.includes(nombre))

describe('categorías', () => {
  beforeEach(() => { api.categorias.mockResolvedValue(CATEGORIAS) })
  afterEach(() => vi.clearAllMocks())

  it('es un diálogo con su título', async () => {
    montar()
    expect(screen.getByRole('dialog', { name: 'Categorías del catálogo' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Categorías' })).toBeInTheDocument()
    await screen.findByText('Monitores')
  })

  it('mientras carga lo dice', async () => {
    api.categorias.mockReturnValue(new Promise(() => {}))
    montar()
    expect(await screen.findByRole('status')).toHaveTextContent(/cargando/i)
  })

  it('lista las categorías y avisa a quien lo abrió', async () => {
    const { onCambio } = montar()
    expect(await screen.findByText('Computadores')).toBeInTheDocument()
    expect(onCambio).toHaveBeenCalledWith(CATEGORIAS)
  })

  it('sin categorías lo dice', async () => {
    api.categorias.mockResolvedValue([])
    montar()
    expect(await screen.findByText(/todavía no hay categorías/i)).toBeInTheDocument()
  })

  it('si no cargan dice por qué y deja reintentar', async () => {
    api.categorias.mockRejectedValueOnce(new Error('No hay conexión.'))
    montar()
    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('No hay conexión.')
    await userEvent.click(within(alerta).getByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('Monitores')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('crea una categoría, vacía el campo y recarga', async () => {
    api.crearCategoria.mockResolvedValue({})
    montar()
    await screen.findByText('Monitores')
    const campo = screen.getByLabelText('Nombre de la categoría nueva')
    await userEvent.type(campo, '  Impresoras ')
    await userEvent.click(screen.getByRole('button', { name: 'Añadir' }))
    await waitFor(() => expect(api.crearCategoria)
      .toHaveBeenCalledWith({ nombre: 'Impresoras', descripcion: null }))
    await waitFor(() => expect(campo).toHaveValue(''))
    expect(api.categorias).toHaveBeenCalledTimes(2)
  })

  it('no deja añadir con el nombre vacío', async () => {
    montar()
    await screen.findByText('Monitores')
    expect(screen.getByRole('button', { name: 'Añadir' })).toBeDisabled()
  })

  it('si crear falla, muestra el mensaje del servidor y conserva lo escrito', async () => {
    api.crearCategoria.mockRejectedValue(new Error('Ya existe una categoría con ese nombre.'))
    montar()
    await screen.findByText('Monitores')
    const campo = screen.getByLabelText('Nombre de la categoría nueva')
    await userEvent.type(campo, 'Monitores')
    await userEvent.click(screen.getByRole('button', { name: 'Añadir' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Ya existe una categoría con ese nombre.')
    expect(campo).toHaveValue('Monitores')
  })

  it('renombra una categoría', async () => {
    api.editarCategoria.mockResolvedValue({})
    montar()
    await screen.findByText('Monitores')
    await userEvent.click(within(filaDe('Monitores')).getByRole('button', { name: /renombrar/i }))
    const campo = screen.getByLabelText('Nuevo nombre de Monitores')
    await userEvent.clear(campo)
    await userEvent.type(campo, 'Pantallas')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))
    await waitFor(() => expect(api.editarCategoria)
      .toHaveBeenCalledWith(3, { nombre: 'Pantallas', descripcion: null }))
    await waitFor(() => expect(screen.queryByLabelText('Nuevo nombre de Monitores')).not.toBeInTheDocument())
  })

  it('cancelar el renombrado no llama al servidor', async () => {
    montar()
    await screen.findByText('Monitores')
    await userEvent.click(within(filaDe('Monitores')).getByRole('button', { name: /renombrar/i }))
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(api.editarCategoria).not.toHaveBeenCalled()
  })

  it('desactiva una categoría y recarga', async () => {
    api.desactivarCategoria.mockResolvedValue({})
    montar()
    await screen.findByText('Monitores')
    await userEvent.click(within(filaDe('Monitores')).getByRole('button', { name: /desactivar/i }))
    await waitFor(() => expect(api.desactivarCategoria).toHaveBeenCalledWith(3))
  })

  it('si desactivar falla porque sostiene productos, dice cuántos tal como lo da el servidor', async () => {
    api.desactivarCategoria.mockRejectedValue(
      new Error('La categoría tiene 4 productos activos: muévelos antes.'))
    montar()
    await screen.findByText('Monitores')
    await userEvent.click(within(filaDe('Monitores')).getByRole('button', { name: /desactivar/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent('tiene 4 productos activos')
    expect(filaDe('Monitores')).toBeInTheDocument()
  })

  describe('teclado', () => {
    it('al abrir, el foco entra en el diálogo', async () => {
      montar()
      await screen.findByText('Monitores')
      expect(screen.getByRole('dialog')).toContainElement(document.activeElement)
    })

    it('Escape lo cierra', async () => {
      const { onCerrar } = montar()
      await screen.findByText('Monitores')
      await userEvent.keyboard('{Escape}')
      expect(onCerrar).toHaveBeenCalledTimes(1)
    })

    it('Enter en el campo nuevo añade, sin tocar el ratón', async () => {
      api.crearCategoria.mockResolvedValue({})
      montar()
      await screen.findByText('Monitores')
      await userEvent.type(screen.getByLabelText('Nombre de la categoría nueva'), 'Redes{Enter}')
      await waitFor(() => expect(api.crearCategoria).toHaveBeenCalled())
    })
  })
})

/**
 * Galería de imágenes de un producto.
 *
 * La principal es la que sale en el catálogo y en el documento
 * exportable; por eso cada cambio avisa a quien abrió la galería con la
 * lista fresca, y no solo con un «algo cambió».
 */
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import Galeria from './Galeria.jsx'
import { api } from '../servicios/api.js'
import { subirImagenes } from '../servicios/subidas.js'

vi.mock('../servicios/api.js', () => ({
  api: { imagenesDe: vi.fn(), hacerPrincipal: vi.fn(), borrarImagen: vi.fn() },
  imagenAutenticada: vi.fn(async () => 'blob:simulada'),
}))
vi.mock('../servicios/subidas.js', () => ({
  MAXIMO_POR_PRODUCTO: 5,
  subirImagenes: vi.fn(),
}))

const IMG1 = { id: 11, orden: 1, principal: true, urlMiniatura: '/api/v1/imagenes/11/miniatura' }
const IMG2 = { id: 12, orden: 2, principal: false, urlMiniatura: '/api/v1/imagenes/12/miniatura' }

function montar(extra = {}) {
  const onCerrar = vi.fn()
  const onCambio = vi.fn()
  render(
    <Galeria
      productoId={1} nombre="Portátil" puedeEditar onCerrar={onCerrar} onCambio={onCambio}
      {...extra}
    />)
  return { onCerrar, onCambio }
}

describe('galería de imágenes', () => {
  beforeEach(() => { api.imagenesDe.mockResolvedValue([IMG1, IMG2]) })
  afterEach(() => vi.clearAllMocks())

  it('es un diálogo con el nombre del producto y su título', async () => {
    montar()
    expect(screen.getByRole('dialog', { name: 'Imágenes de Portátil' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Imágenes de Portátil' })).toBeInTheDocument()
    await screen.findAllByRole('listitem')
  })

  it('mientras carga lo dice', async () => {
    api.imagenesDe.mockReturnValue(new Promise(() => {}))
    montar()
    expect(await screen.findByRole('status')).toHaveTextContent(/cargando/i)
  })

  it('lista las imágenes y marca la principal con texto', async () => {
    montar()
    const items = await screen.findAllByRole('listitem')
    expect(items).toHaveLength(2)
    expect(within(items[0]).getByText('Principal')).toBeInTheDocument()
    expect(within(items[1]).queryByText('Principal')).not.toBeInTheDocument()
  })

  it('sin imágenes lo dice', async () => {
    api.imagenesDe.mockResolvedValue([])
    montar()
    expect(await screen.findByText(/todavía no tiene imágenes/i)).toBeInTheDocument()
  })

  it('si no carga dice por qué y deja reintentar', async () => {
    api.imagenesDe.mockRejectedValueOnce(new Error('No hay conexión.'))
    montar()
    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('No hay conexión.')
    await userEvent.click(within(alerta).getByRole('button', { name: 'Reintentar' }))
    expect(await screen.findAllByRole('listitem')).toHaveLength(2)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('hacer principal llama al servidor y avisa con la lista fresca', async () => {
    api.hacerPrincipal.mockResolvedValue({})
    const { onCambio } = montar()
    const items = await screen.findAllByRole('listitem')
    api.imagenesDe.mockResolvedValue([{ ...IMG1, principal: false }, { ...IMG2, principal: true }])
    await userEvent.click(within(items[1]).getByRole('button', { name: 'Hacer principal' }))
    await waitFor(() => expect(onCambio).toHaveBeenCalled())
    expect(api.hacerPrincipal).toHaveBeenCalledWith(12)
    expect(onCambio.mock.calls[0][0].find((i) => i.principal).id).toBe(12)
  })

  it('si hacer principal falla, se dice y la galería sigue ahí', async () => {
    api.hacerPrincipal.mockRejectedValue(new Error('No se pudo cambiar la principal.'))
    montar()
    const items = await screen.findAllByRole('listitem')
    await userEvent.click(within(items[1]).getByRole('button', { name: 'Hacer principal' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('No se pudo cambiar la principal.')
    expect(screen.getAllByRole('listitem')).toHaveLength(2)
  })

  it('eliminar llama al servidor y refresca', async () => {
    api.borrarImagen.mockResolvedValue({})
    const { onCambio } = montar()
    const items = await screen.findAllByRole('listitem')
    api.imagenesDe.mockResolvedValue([IMG1])
    await userEvent.click(within(items[1]).getByRole('button', { name: 'Eliminar' }))
    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(1))
    expect(api.borrarImagen).toHaveBeenCalledWith(12)
    expect(onCambio).toHaveBeenCalled()
  })

  it('si eliminar falla, se dice', async () => {
    api.borrarImagen.mockRejectedValue(new Error('No se pudo eliminar.'))
    montar()
    const items = await screen.findAllByRole('listitem')
    await userEvent.click(within(items[1]).getByRole('button', { name: 'Eliminar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('No se pudo eliminar.')
  })

  it('sin permiso para editar solo se mira: ningún botón de cambio', async () => {
    montar({ puedeEditar: false })
    await screen.findAllByRole('listitem')
    expect(screen.queryByRole('button', { name: /añadir imágenes/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Hacer principal' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Eliminar' })).not.toBeInTheDocument()
  })

  it('al subir varias dice cuáles no entraron, archivo por archivo', async () => {
    subirImagenes.mockResolvedValue({ creadas: [], problemas: ['a.heic: formato no admitido'] })
    montar()
    await screen.findAllByRole('listitem')
    const entrada = document.querySelector('input[type="file"]')
    await userEvent.setup({ applyAccept: false })
      .upload(entrada, new File(['x'], 'a.heic', { type: 'image/heic' }))
    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('a.heic: formato no admitido')
  })

  it('al subir con éxito avisa con la lista fresca', async () => {
    subirImagenes.mockResolvedValue({ creadas: [{ id: 13 }], problemas: [] })
    const { onCambio } = montar()
    await screen.findAllByRole('listitem')
    await userEvent.upload(document.querySelector('input[type="file"]'),
      new File(['x'], 'a.png', { type: 'image/png' }))
    await waitFor(() => expect(onCambio).toHaveBeenCalled())
  })

  it('con cinco imágenes no deja añadir más y lo explica', async () => {
    api.imagenesDe.mockResolvedValue([1, 2, 3, 4, 5].map((n) => ({ ...IMG2, id: n, orden: n })))
    montar()
    await screen.findAllByRole('listitem')
    expect(screen.getByRole('button', { name: /añadir imágenes/i })).toBeDisabled()
    expect(screen.getByText(/ya hay 5/i)).toBeInTheDocument()
  })

  describe('teclado', () => {
    it('al abrir, el foco entra en el diálogo', async () => {
      montar()
      await screen.findAllByRole('listitem')
      expect(screen.getByRole('dialog')).toContainElement(document.activeElement)
    })

    it('Escape lo cierra', async () => {
      const { onCerrar } = montar()
      await screen.findAllByRole('listitem')
      await userEvent.keyboard('{Escape}')
      expect(onCerrar).toHaveBeenCalledTimes(1)
    })

    it('Tab no saca el foco del diálogo', async () => {
      montar()
      await screen.findAllByRole('listitem')
      const dialogo = screen.getByRole('dialog')
      for (let i = 0; i < 12; i += 1) {
        await userEvent.tab()
        expect(dialogo).toContainElement(document.activeElement)
      }
    })
  })
})

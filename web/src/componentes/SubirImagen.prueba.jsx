/**
 * El recuadro de imagen que además sirve para subir o reemplazar.
 *
 * La imagen se trae con una petición autenticada y se pinta como URL de
 * objeto; una etiqueta <img> directa iría sin cabecera de autorización.
 */
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import SubirImagen from './SubirImagen.jsx'
import { imagenAutenticada } from '../servicios/api.js'

vi.mock('../servicios/api.js', () => ({ imagenAutenticada: vi.fn() }))

const archivo = () => new File(['x'], 'foto.png', { type: 'image/png' })
const entrada = () => document.querySelector('input[type="file"]')

describe('recuadro de imagen', () => {
  afterEach(() => vi.clearAllMocks())

  it('pinta la imagen descargada con su texto alternativo', async () => {
    imagenAutenticada.mockResolvedValue('blob:uno')
    render(<SubirImagen src="/api/v1/imagenes/1/miniatura" alt="Imagen de Portátil" />)
    expect(await screen.findByRole('img', { name: 'Imagen de Portátil' })).toHaveAttribute('src', 'blob:uno')
    expect(imagenAutenticada).toHaveBeenCalledWith('/api/v1/imagenes/1/miniatura')
  })

  it('sin imagen y sin permiso es un recuadro inerte con su nombre', () => {
    render(<SubirImagen src={null} alt="Imagen de Portátil" puedeEditar={false} />)
    const boton = screen.getByRole('button', { name: 'Imagen de Portátil' })
    expect(boton).toBeDisabled()
    expect(imagenAutenticada).not.toHaveBeenCalled()
  })

  it('con permiso, el botón dice qué hace y abre el selector de archivos', async () => {
    const clic = vi.spyOn(HTMLInputElement.prototype, 'click')
    render(<SubirImagen src={null} alt="Imagen" puedeEditar multiple />)
    const boton = screen.getByRole('button', { name: /subir imágenes/i })
    await userEvent.click(boton)
    expect(clic).toHaveBeenCalled()
    clic.mockRestore()
  })

  it('con permiso y sin ayuda, el botón dice la acción y de qué imagen se trata', () => {
    render(<SubirImagen src={null} alt="Logo de Comercial Andina S.A.S." puedeEditar />)
    expect(screen.getByRole('button', { name: 'Subir o reemplazar: Logo de Comercial Andina S.A.S.' })).toBeInTheDocument()
  })

  it('entrega SIEMPRE un arreglo de archivos, también cuando no es múltiple', async () => {
    const onSubir = vi.fn().mockResolvedValue()
    render(<SubirImagen src={null} alt="Logo" puedeEditar onSubir={onSubir} />)
    await userEvent.upload(entrada(), archivo())
    await waitFor(() => expect(onSubir).toHaveBeenCalled())
    expect(Array.isArray(onSubir.mock.calls[0][0])).toBe(true)
    expect(onSubir.mock.calls[0][0]).toHaveLength(1)
  })

  it('mientras sube no deja volver a pulsar', async () => {
    let terminar
    const onSubir = vi.fn(() => new Promise((r) => { terminar = r }))
    render(<SubirImagen src={null} alt="Logo" puedeEditar onSubir={onSubir} />)
    await userEvent.upload(entrada(), archivo())
    expect(screen.getByRole('button')).toBeDisabled()
    terminar()
    await waitFor(() => expect(screen.getByRole('button')).toBeEnabled())
  })

  it('si subir falla, lo dice con el mensaje y no deja una promesa huérfana', async () => {
    const onFallo = vi.fn()
    const onSubir = vi.fn().mockRejectedValue(new Error('Archivo demasiado grande.'))
    render(<SubirImagen src={null} alt="Logo" puedeEditar onSubir={onSubir} onFallo={onFallo} />)
    await userEvent.upload(entrada(), archivo())
    await waitFor(() => expect(onFallo).toHaveBeenCalledWith('Archivo demasiado grande.'))
    expect(screen.getByRole('button')).toBeEnabled()
  })

  it('si la descarga falla, lo marca para que no parezca que no hay imagen', async () => {
    imagenAutenticada.mockRejectedValue(new Error('403'))
    render(<SubirImagen src="/x" alt="Imagen" puedeEditar />)
    expect(await screen.findByText('!')).toBeInTheDocument()
  })

  it('libera la URL de objeto al desmontarse', async () => {
    imagenAutenticada.mockResolvedValue('blob:uno')
    const liberar = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const { unmount } = render(<SubirImagen src="/x" alt="Imagen" />)
    await screen.findByRole('img')
    unmount()
    expect(liberar).toHaveBeenCalledWith('blob:uno')
    liberar.mockRestore()
  })
})

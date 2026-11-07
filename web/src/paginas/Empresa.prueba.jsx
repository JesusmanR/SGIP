/**
 * «Mi empresa» la ven los tres roles de empresa. Lo que se prueba sale de
 * las reglas de producto: solo el administrador cambia el logo, nadie que
 * no sea administrador ve costos ni márgenes, y los impuestos son
 * opcionales —no cobrar IVA no es un error ni una alerta.
 */
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import Empresa from './Empresa.jsx'
import { api } from '../servicios/api.js'
import { guardarSesion } from '../servicios/sesion.js'

vi.mock('../servicios/api.js', () => ({
  api: { empresa: vi.fn(), subirLogo: vi.fn() },
  imagenAutenticada: vi.fn().mockResolvedValue('blob:logo'),
  ErrorApi: class extends Error {},
}))
vi.mock('../servicios/imagenes.js', () => ({
  FORMATOS_ACEPTADOS: 'image/png',
  prepararImagen: vi.fn(async (a) => a),
}))

const EMPRESA = {
  id: 1, nit: '901482310-2', razonSocial: 'Comercial Andina S.A.S.',
  logoUrl: '/api/v1/empresa/logo',
}

function montar() {
  return render(<MemoryRouter><Empresa /></MemoryRouter>)
}

const logo = () => new File(['x'], 'logo.png', { type: 'image/png' })

beforeEach(() => {
  sessionStorage.clear()
  guardarSesion({ token: 't', nombre: 'Ana', rol: 'ADMINISTRADOR' })
  api.empresa.mockResolvedValue(EMPRESA)
})
afterEach(() => vi.clearAllMocks())


describe('Mi empresa', () => {
  it('tiene un único h1', async () => {
    montar()
    await screen.findByText('Comercial Andina S.A.S.')
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(/mi empresa/i)
  })

  it('mientras carga lo anuncia', () => {
    api.empresa.mockReturnValue(new Promise(() => {}))
    montar()
    expect(screen.getByRole('status')).toHaveTextContent(/cargando/i)
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
  })

  it('muestra razón social y NIT', async () => {
    montar()
    expect(await screen.findByText('Comercial Andina S.A.S.')).toBeInTheDocument()
    expect(screen.getByText(/NIT 901482310-2/)).toBeInTheDocument()
  })

  it('un fallo ofrece reintentar y se recupera', async () => {
    const teclado = userEvent.setup()
    api.empresa.mockRejectedValueOnce(new Error('Sin conexión con el servidor.'))
    montar()
    expect(await screen.findByRole('alert')).toHaveTextContent('Sin conexión con el servidor.')
    await teclado.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByText('Comercial Andina S.A.S.')).toBeInTheDocument()
  })

  it('un 403 dice que no hay permiso', async () => {
    api.empresa.mockRejectedValue(Object.assign(new Error('Prohibido'), { estado: 403 }))
    montar()
    expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument()
  })

  it('no habla de costos ni de márgenes a ningún rol', async () => {
    for (const rol of ['ADMINISTRADOR', 'VENDEDOR', 'CLIENTE']) {
      sessionStorage.clear()
      guardarSesion({ token: 't', nombre: 'X', rol })
      const { unmount } = montar()
      await screen.findByText('Comercial Andina S.A.S.')
      expect(screen.queryByText(/costo|margen|ganancia/i)).not.toBeInTheDocument()
      unmount()
    }
  })
})


describe('el logo', () => {
  it('el administrador sube uno nuevo y lo ve confirmado', async () => {
    const teclado = userEvent.setup()
    api.subirLogo.mockResolvedValue(EMPRESA)
    montar()
    await screen.findByText('Comercial Andina S.A.S.')
    await teclado.upload(document.querySelector('input[type="file"]'), logo())
    await waitFor(() => expect(api.subirLogo).toHaveBeenCalled())
    expect(await screen.findByRole('status')).toHaveTextContent(/logo actualizado/i)
  })

  it('si la subida falla muestra el motivo y no dice que salió bien', async () => {
    const teclado = userEvent.setup()
    api.subirLogo.mockRejectedValue(new Error('El archivo supera 2 MB.'))
    montar()
    await screen.findByText('Comercial Andina S.A.S.')
    await teclado.upload(document.querySelector('input[type="file"]'), logo())
    expect(await screen.findByRole('alert')).toHaveTextContent('El archivo supera 2 MB.')
    expect(screen.queryByText(/logo actualizado/i)).not.toBeInTheDocument()
  })

  it('el botón del logo se alcanza con el teclado', async () => {
    montar()
    await screen.findByText('Comercial Andina S.A.S.')
    const boton = screen.getByRole('button', { name: /logo de comercial andina/i })
    boton.focus()
    expect(boton).toHaveFocus()
    expect(boton).toBeEnabled()
  })

  it('quien no es administrador no puede cambiarlo y se le dice', async () => {
    guardarSesion({ token: 't', nombre: 'Ana', rol: 'VENDEDOR' })
    montar()
    await screen.findByText('Comercial Andina S.A.S.')
    expect(screen.getByRole('button', { name: /logo de comercial andina/i })).toBeDisabled()
    expect(screen.getByText(/solo el administrador puede cambiar el logo/i)).toBeInTheDocument()
  })
})


describe('los impuestos son opcionales', () => {
  it('al administrador se le dice que no cobrar IVA es válido y se le enlaza la configuración', async () => {
    montar()
    await screen.findByText('Comercial Andina S.A.S.')
    expect(screen.getByText(/no cobras iva/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /impuestos/i })).toHaveAttribute('href', '/impuestos')
  })

  it('no hay ninguna alerta ni error por no tener impuestos', async () => {
    montar()
    await screen.findByText('Comercial Andina S.A.S.')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('quien no es administrador no ve el enlace a configurar impuestos', async () => {
    guardarSesion({ token: 't', nombre: 'Ana', rol: 'CLIENTE' })
    montar()
    await screen.findByText('Comercial Andina S.A.S.')
    expect(screen.queryByRole('link', { name: /impuestos/i })).not.toBeInTheDocument()
  })
})

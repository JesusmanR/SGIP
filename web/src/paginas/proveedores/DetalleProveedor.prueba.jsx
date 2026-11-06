import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ErrorApi } from '../../servicios/api.js'
import { verProveedor } from '../../servicios/api/proveedores.js'
import { PROVEEDOR, montar } from './ayuda.jsx'

vi.mock('../../servicios/api/proveedores.js', () => ({
  listarProveedores: vi.fn(async () => []), verProveedor: vi.fn(), crearProveedor: vi.fn(),
}))

beforeEach(() => vi.resetAllMocks())

describe('detalle de proveedor', () => {
  it('muestra sus datos y pide el que dice la ruta', async () => {
    verProveedor.mockResolvedValue(PROVEEDOR)
    montar('/proveedores/4')
    expect(await screen.findByRole('heading', { level: 1, name: 'Aceros del Valle S.A.S.' })).toBeInTheDocument()
    expect(verProveedor).toHaveBeenCalledWith('4')
    expect(screen.getByText('900123456-7')).toBeInTheDocument()
    expect(screen.getByText('Marta Ruiz')).toBeInTheDocument()
    expect(screen.getByText('marta@aceros.co')).toBeInTheDocument()
    expect(screen.getByText('3101234567')).toBeInTheDocument()
    expect(screen.getByText('30 días')).toBeInTheDocument()
  })

  it('no finge una acción de editar que el servidor no tiene', async () => {
    verProveedor.mockResolvedValue(PROVEEDOR)
    montar('/proveedores/4')
    await screen.findByRole('heading', { level: 1 })
    expect(screen.queryByRole('button', { name: /editar|borrar|eliminar|desactivar/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /editar/i })).not.toBeInTheDocument()
  })

  it('los datos que faltan se ven como no registrados, no como vacío', async () => {
    verProveedor.mockResolvedValue({ ...PROVEEDOR, contacto: null, correo: null, telefono: null })
    montar('/proveedores/4')
    await screen.findByRole('heading', { level: 1 })
    expect(screen.getAllByText('No registrado')).toHaveLength(3)
  })

  it('un 404 dice que no existe, sin reintento', async () => {
    verProveedor.mockRejectedValue(new ErrorApi(404, 'x'))
    montar('/proveedores/99')
    expect(await screen.findByText(/no existe/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /volver a proveedores/i })).toHaveAttribute('href', '/proveedores')
    expect(screen.queryByRole('button', { name: /reintentar/i })).not.toBeInTheDocument()
  })

  it('un error reintenta', async () => {
    verProveedor.mockRejectedValueOnce(new ErrorApi(500, 'Falló'))
    verProveedor.mockResolvedValueOnce(PROVEEDOR)
    montar('/proveedores/4')
    expect(await screen.findByRole('alert')).toHaveTextContent('Falló')
    await userEvent.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByRole('heading', { level: 1, name: PROVEEDOR.razonSocial })).toBeInTheDocument()
  })

  it('un 403 muestra sin permiso', async () => {
    verProveedor.mockRejectedValue(new ErrorApi(403, 'no'))
    montar('/proveedores/4')
    expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument()
  })

  it('tras crear, avisa que ya se puede usar al crear productos', async () => {
    verProveedor.mockResolvedValue(PROVEEDOR)
    montar({ pathname: '/proveedores/4', state: { creado: true } })
    expect(await screen.findByText(/proveedor creado/i)).toBeInTheDocument()
  })
})

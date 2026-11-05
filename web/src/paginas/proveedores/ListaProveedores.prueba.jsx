import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ErrorApi } from '../../servicios/api.js'
import { listarProveedores } from '../../servicios/api/proveedores.js'
import { PROVEEDOR, montar } from './ayuda.jsx'

vi.mock('../../servicios/api/proveedores.js', () => ({
  listarProveedores: vi.fn(), verProveedor: vi.fn(), crearProveedor: vi.fn(),
}))

beforeEach(() => vi.resetAllMocks())

describe('lista de proveedores', () => {
  it('mientras carga lo dice', () => {
    listarProveedores.mockReturnValue(new Promise(() => {}))
    montar()
    expect(screen.getByRole('status')).toHaveTextContent(/cargando/i)
  })

  it('con datos muestra una fila por proveedor, con plazo en palabras y enlace al detalle', async () => {
    listarProveedores.mockResolvedValue([PROVEEDOR, { ...PROVEEDOR, id: 5, razonSocial: 'Pinturas Sol', nit: '800555111-2', plazoDias: 0 }])
    montar()
    const tabla = await screen.findByRole('table', { name: /proveedores/i })
    expect(within(tabla).getByRole('link', { name: 'Aceros del Valle S.A.S.' })).toHaveAttribute('href', '/proveedores/4')
    expect(within(tabla).getByText('900123456-7')).toBeInTheDocument()
    expect(within(tabla).getByText('30 días')).toBeInTheDocument()
    expect(within(tabla).getByText('De contado')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /nuevo proveedor/i })).toHaveAttribute('href', '/proveedores/nuevo')
  })

  it('sin proveedores explica para qué sirven y ofrece crear el primero', async () => {
    listarProveedores.mockResolvedValue([])
    montar()
    expect(await screen.findByText(/todavía no hay proveedores/i)).toBeInTheDocument()
    expect(screen.getByText(/para crear productos/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /crear el primer proveedor/i })).toHaveAttribute('href', '/proveedores/nuevo')
  })

  it('un error dice qué pasó y reintenta', async () => {
    listarProveedores.mockRejectedValueOnce(new ErrorApi(500, 'Se cayó el servidor'))
    listarProveedores.mockResolvedValueOnce([PROVEEDOR])
    montar()
    expect(await screen.findByRole('alert')).toHaveTextContent('Se cayó el servidor')
    await userEvent.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByRole('link', { name: PROVEEDOR.razonSocial })).toBeInTheDocument()
    expect(listarProveedores).toHaveBeenCalledTimes(2)
  })

  it('un 403 muestra sin permiso y no el error genérico', async () => {
    listarProveedores.mockRejectedValue(new ErrorApi(403, 'Prohibido'))
    montar()
    expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /reintentar/i })).not.toBeInTheDocument()
  })

  it('pide la lista una sola vez', async () => {
    listarProveedores.mockResolvedValue([PROVEEDOR])
    montar()
    await screen.findByRole('table')
    expect(listarProveedores).toHaveBeenCalledTimes(1)
  })
})

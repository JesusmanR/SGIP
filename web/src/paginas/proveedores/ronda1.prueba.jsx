/** Ronda 1 de arreglos: tope del plazo y guarda de doble envío. */
import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { crearProveedor, verProveedor } from '../../servicios/api/proveedores.js'
import { PROVEEDOR, montar } from './ayuda.jsx'

vi.mock('../../servicios/api/proveedores.js', () => ({
  listarProveedores: vi.fn(async () => []), verProveedor: vi.fn(), crearProveedor: vi.fn(),
}))

beforeEach(() => vi.resetAllMocks())

const campo = (n) => screen.getByLabelText(n)

async function llenar(plazo) {
  montar('/proveedores/nuevo')
  await userEvent.type(campo(/razón social/i), 'Acme')
  await userEvent.type(campo(/nit/i), '900')
  await userEvent.clear(campo(/plazo de pago/i))
  await userEvent.type(campo(/plazo de pago/i), plazo)
  await userEvent.click(screen.getByRole('button', { name: /guardar proveedor/i }))
}

describe('plazo de pago', () => {
  it('más de 365 días no se envía y el mensaje junto al campo dice el tope', async () => {
    await llenar('366')
    expect(crearProveedor).not.toHaveBeenCalled()
    expect(campo(/plazo de pago/i)).toHaveAccessibleDescription(/0 a 365/)
    expect(campo(/plazo de pago/i)).toHaveFocus()
  })

  it('365 días sí se envía', async () => {
    crearProveedor.mockResolvedValue({ ...PROVEEDOR, id: 9 })
    verProveedor.mockResolvedValue(PROVEEDOR)
    await llenar('365')
    await waitFor(() => expect(crearProveedor).toHaveBeenCalledTimes(1))
    expect(crearProveedor.mock.calls[0][0].plazoDias).toBe(365)
  })
})

describe('doble envío', () => {
  it('un segundo envío mientras guarda no llega al servidor (guarda interna, sin botón)', async () => {
    crearProveedor.mockReturnValue(new Promise(() => {}))
    await llenar('30')
    fireEvent.submit(screen.getByRole('button', { name: /guardando/i }).closest('form'))
    expect(crearProveedor).toHaveBeenCalledTimes(1)
  })
})

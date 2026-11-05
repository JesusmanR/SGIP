import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ErrorApi } from '../../servicios/api.js'
import { crearProveedor, verProveedor } from '../../servicios/api/proveedores.js'
import { PROVEEDOR, montar } from './ayuda.jsx'

vi.mock('../../servicios/api/proveedores.js', () => ({
  listarProveedores: vi.fn(async () => []), verProveedor: vi.fn(), crearProveedor: vi.fn(),
}))

beforeEach(() => vi.resetAllMocks())

const campo = (nombre) => screen.getByLabelText(nombre)
const guardar = () => userEvent.click(screen.getByRole('button', { name: /guardar proveedor/i }))

describe('crear proveedor', () => {
  it('cada campo tiene su etiqueta visible', () => {
    montar('/proveedores/nuevo')
    for (const n of [/razón social/i, /nit/i, /persona de contacto/i, /correo/i, /teléfono/i, /plazo de pago/i]) {
      expect(screen.getByLabelText(n)).toBeInTheDocument()
    }
  })

  it('vacío no envía nada, dice qué falta junto al campo y enfoca el primero', async () => {
    montar('/proveedores/nuevo')
    await guardar()
    expect(crearProveedor).not.toHaveBeenCalled()
    const razon = campo(/razón social/i)
    expect(razon).toHaveAttribute('aria-invalid', 'true')
    expect(razon).toHaveFocus()
    expect(razon).toHaveAccessibleDescription(/obligatoria/i)
    expect(campo(/nit/i)).toHaveAccessibleDescription(/obligatorio/i)
  })

  it('correo mal escrito y plazo negativo no se envían', async () => {
    montar('/proveedores/nuevo')
    await userEvent.type(campo(/razón social/i), 'Acme')
    await userEvent.type(campo(/nit/i), '900')
    await userEvent.type(campo(/correo/i), 'no-es-correo')
    await userEvent.clear(campo(/plazo de pago/i))
    await userEvent.type(campo(/plazo de pago/i), '-3')
    await guardar()
    expect(crearProveedor).not.toHaveBeenCalled()
    expect(campo(/correo/i)).toHaveAccessibleDescription(/correo/i)
    expect(campo(/plazo de pago/i)).toHaveAccessibleDescription(/cero o más/i)
  })

  it('con datos válidos envía lo escrito (vacíos como nulo, plazo como número) y va al detalle', async () => {
    crearProveedor.mockResolvedValue({ ...PROVEEDOR, id: 9, razonSocial: 'Acme' })
    verProveedor.mockResolvedValue({ ...PROVEEDOR, id: 9, razonSocial: 'Acme' })
    montar('/proveedores/nuevo')
    await userEvent.type(campo(/razón social/i), '  Acme  ')
    await userEvent.type(campo(/nit/i), '900123')
    await userEvent.clear(campo(/plazo de pago/i))
    await userEvent.type(campo(/plazo de pago/i), '45')
    await guardar()
    await waitFor(() => expect(crearProveedor).toHaveBeenCalledTimes(1))
    expect(crearProveedor).toHaveBeenCalledWith({
      razonSocial: 'Acme', nit: '900123', contacto: null, correo: null, telefono: null, plazoDias: 45,
    })
    expect(await screen.findByRole('heading', { level: 1, name: 'Acme' })).toBeInTheDocument()
    expect(verProveedor).toHaveBeenCalledWith('9')
    expect(screen.getByRole('status')).toHaveTextContent(/proveedor creado/i)
  })

  it('el NIT repetido (409) se dice junto al campo NIT y no se navega', async () => {
    crearProveedor.mockRejectedValue(new ErrorApi(409, 'Ya existe un proveedor con el NIT 900 en esta empresa.'))
    montar('/proveedores/nuevo')
    await userEvent.type(campo(/razón social/i), 'Acme')
    await userEvent.type(campo(/nit/i), '900')
    await guardar()
    await waitFor(() => expect(campo(/nit/i)).toHaveAccessibleDescription(/ya existe un proveedor/i))
    expect(campo(/nit/i)).toHaveFocus()
    expect(screen.getByRole('heading', { level: 1, name: /nuevo proveedor/i })).toBeInTheDocument()
  })

  it('otro error del servidor (403) se muestra completo en un aviso y deja corregir', async () => {
    crearProveedor.mockRejectedValue(new ErrorApi(403, 'No tienes permiso.'))
    montar('/proveedores/nuevo')
    await userEvent.type(campo(/razón social/i), 'Acme')
    await userEvent.type(campo(/nit/i), '900')
    await guardar()
    expect(await screen.findByRole('alert')).toHaveTextContent('No tienes permiso.')
    expect(screen.getByRole('button', { name: /guardar proveedor/i })).toBeEnabled()
    expect(campo(/razón social/i)).toHaveValue('Acme')
  })

  it('mientras envía el botón queda deshabilitado y no se duplica el envío', async () => {
    let resolver
    crearProveedor.mockReturnValue(new Promise((r) => { resolver = r }))
    verProveedor.mockResolvedValue(PROVEEDOR)
    montar('/proveedores/nuevo')
    await userEvent.type(campo(/razón social/i), 'Acme')
    await userEvent.type(campo(/nit/i), '900')
    await guardar()
    const enviando = screen.getByRole('button', { name: /guardando/i })
    expect(enviando).toBeDisabled()
    await userEvent.click(enviando)
    expect(crearProveedor).toHaveBeenCalledTimes(1)
    resolver({ ...PROVEEDOR, id: 9 })
  })

  it('cancelar vuelve a la lista sin enviar', async () => {
    montar('/proveedores/nuevo')
    await userEvent.click(screen.getByRole('link', { name: /cancelar/i }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Proveedores' })).toBeInTheDocument()
    expect(crearProveedor).not.toHaveBeenCalled()
  })
})

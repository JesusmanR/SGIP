/**
 * «Mi empresa» la ven los tres roles de empresa. Lo que se prueba sale de
 * las reglas de producto: solo el administrador cambia el logo, nadie que
 * no sea administrador ve costos ni márgenes, y los impuestos son
 * opcionales —no cobrar IVA no es un error ni una alerta.
 */
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import Empresa from './Empresa.jsx'
import { api } from '../servicios/api.js'
import { guardarSesion } from '../servicios/sesion.js'

vi.mock('../servicios/api.js', () => ({
  api: { empresa: vi.fn(), guardarEmpresa: vi.fn(), subirLogo: vi.fn(), estadoPin: vi.fn(), fijarPin: vi.fn() },
  imagenAutenticada: vi.fn().mockResolvedValue('blob:logo'),
  ErrorApi: class extends Error {},
}))
vi.mock('../servicios/imagenes.js', () => ({
  FORMATOS_ACEPTADOS: 'image/png',
  prepararImagen: vi.fn(async (a) => a),
}))

const EMPRESA = {
  id: 1, nit: '901482310-2', razonSocial: 'Comercial Andina S.A.S.',
  logoUrl: '/api/v1/empresa/logo', tipoDocumento: 'NIT', nombreComercial: 'Andina Repuestos',
  direccion: 'Calle 10 # 5-20', ciudad: 'Cali', telefono: '3001112233', correoContacto: 'info@andina.co',
}

function montar() {
  return render(<MemoryRouter><Empresa /></MemoryRouter>)
}

const logo = () => new File(['x'], 'logo.png', { type: 'image/png' })

beforeEach(() => {
  sessionStorage.clear()
  guardarSesion({ token: 't', nombre: 'Ana', rol: 'ADMINISTRADOR' })
  api.empresa.mockResolvedValue(EMPRESA)
  api.estadoPin.mockResolvedValue({ configurado: false })
  api.fijarPin.mockResolvedValue(null)
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


describe('Mi PIN de autorización', () => {
  const seccion = () => screen.getByRole('region', { name: /mi pin de autorización/i })

  it('solo el administrador lo ve, y los demás ni lo piden', async () => {
    guardarSesion({ token: 't', nombre: 'Luis', rol: 'VENDEDOR' })
    montar()
    await screen.findByText('Comercial Andina S.A.S.')
    expect(screen.queryByRole('region', { name: /mi pin de autorización/i })).not.toBeInTheDocument()
    expect(api.estadoPin).not.toHaveBeenCalled()
  })

  it('elPinPideElActualSoloSiYaEstaConfigurado', async () => {
    api.estadoPin.mockResolvedValue({ configurado: false })
    const primera = montar()
    await screen.findByLabelText(/^pin nuevo$/i)
    expect(screen.queryByLabelText(/pin actual/i)).not.toBeInTheDocument()
    expect(within(seccion()).getByText(/todavía no tienes un pin/i)).toBeInTheDocument()
    primera.unmount()

    api.estadoPin.mockResolvedValue({ configurado: true })
    montar()
    expect(await screen.findByLabelText(/pin actual/i)).toBeInTheDocument()
    expect(within(seccion()).getByText(/ya tienes un pin/i)).toBeInTheDocument()
  })

  it('la primera vez guarda el PIN nuevo sin PIN actual', async () => {
    montar()
    await userEvent.type(await screen.findByLabelText(/^pin nuevo$/i), '4821')
    await userEvent.type(screen.getByLabelText(/repite el pin nuevo/i), '4821')
    await userEvent.click(screen.getByRole('button', { name: /guardar pin/i }))
    await waitFor(() => expect(api.fijarPin).toHaveBeenCalledWith(null, '4821'))
    expect(await screen.findByText(/pin guardado/i)).toBeInTheDocument()
  })

  it('para cambiarlo manda el actual y el nuevo', async () => {
    api.estadoPin.mockResolvedValue({ configurado: true })
    montar()
    await userEvent.type(await screen.findByLabelText(/pin actual/i), '1111')
    await userEvent.type(screen.getByLabelText(/^pin nuevo$/i), '4821')
    await userEvent.type(screen.getByLabelText(/repite el pin nuevo/i), '4821')
    await userEvent.click(screen.getByRole('button', { name: /guardar pin/i }))
    await waitFor(() => expect(api.fijarPin).toHaveBeenCalledWith('1111', '4821'))
  })

  it('pinNuncaSeMuestra: los campos son de contraseña, se vacían al guardar y el PIN no queda en pantalla', async () => {
    api.estadoPin.mockResolvedValue({ configurado: true })
    montar()
    const campos = [await screen.findByLabelText(/pin actual/i), screen.getByLabelText(/^pin nuevo$/i), screen.getByLabelText(/repite el pin nuevo/i)]
    for (const c of campos) expect(c).toHaveAttribute('type', 'password')
    await userEvent.type(campos[0], '1111')
    await userEvent.type(campos[1], '4821')
    await userEvent.type(campos[2], '4821')
    await userEvent.click(screen.getByRole('button', { name: /guardar pin/i }))
    await screen.findByText(/pin guardado/i)
    for (const c of campos) expect(c).toHaveValue('')
    expect(document.body.textContent).not.toContain('4821')
    expect(document.body.textContent).not.toContain('1111')
    expect(sessionStorage.getItem('sgip.sesion')).not.toContain('4821')
  })

  it('el PIN nuevo son de 4 a 6 números y la confirmación debe coincidir', async () => {
    montar()
    await userEvent.type(await screen.findByLabelText(/^pin nuevo$/i), '12')
    await userEvent.type(screen.getByLabelText(/repite el pin nuevo/i), '12')
    await userEvent.click(screen.getByRole('button', { name: /guardar pin/i }))
    expect(screen.getByText(/de 4 a 6 números/i, { selector: '.campo-mensaje' })).toBeInTheDocument()
    await userEvent.clear(screen.getByLabelText(/^pin nuevo$/i))
    await userEvent.type(screen.getByLabelText(/^pin nuevo$/i), '4821')
    await userEvent.clear(screen.getByLabelText(/repite el pin nuevo/i))
    await userEvent.type(screen.getByLabelText(/repite el pin nuevo/i), '4822')
    await userEvent.click(screen.getByRole('button', { name: /guardar pin/i }))
    expect(screen.getByText(/los dos pin no coinciden/i)).toBeInTheDocument()
    expect(api.fijarPin).not.toHaveBeenCalled()
  })

  it('un PIN actual equivocado se anuncia y no confirma nada', async () => {
    api.estadoPin.mockResolvedValue({ configurado: true })
    api.fijarPin.mockRejectedValue(new Error('El PIN actual no es correcto.'))
    montar()
    await userEvent.type(await screen.findByLabelText(/pin actual/i), '0000')
    await userEvent.type(screen.getByLabelText(/^pin nuevo$/i), '4821')
    await userEvent.type(screen.getByLabelText(/repite el pin nuevo/i), '4821')
    await userEvent.click(screen.getByRole('button', { name: /guardar pin/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent('El PIN actual no es correcto.')
    expect(screen.queryByText(/pin guardado/i)).not.toBeInTheDocument()
  })
})


describe('datos de contacto editables', () => {
  it('adminEditaYGuarda: manda exactamente los cinco campos y confirma', async () => {
    const teclado = userEvent.setup()
    api.guardarEmpresa.mockResolvedValue({ ...EMPRESA, direccion: 'Carrera 7 # 1-2' })
    montar()
    await screen.findByText('Comercial Andina S.A.S.')
    const direccion = screen.getByLabelText('Dirección')
    expect(direccion).toHaveValue('Calle 10 # 5-20')
    await teclado.clear(direccion)
    await teclado.type(direccion, 'Carrera 7 # 1-2')
    await teclado.click(screen.getByRole('button', { name: 'Guardar cambios' }))
    await waitFor(() => expect(api.guardarEmpresa).toHaveBeenCalledTimes(1))
    expect(api.guardarEmpresa).toHaveBeenCalledWith({
      nombreComercial: 'Andina Repuestos', direccion: 'Carrera 7 # 1-2', ciudad: 'Cali',
      telefono: '3001112233', correoContacto: 'info@andina.co',
    })
    expect(await screen.findByText('Datos guardados.')).toBeInTheDocument()
  })

  it('un rechazo del servidor se muestra y no dice que se guardó', async () => {
    const teclado = userEvent.setup()
    api.guardarEmpresa.mockRejectedValue(new Error('El correo no tiene forma de correo.'))
    montar()
    await screen.findByText('Comercial Andina S.A.S.')
    await teclado.click(screen.getByRole('button', { name: 'Guardar cambios' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('El correo no tiene forma de correo.')
    expect(screen.queryByText('Datos guardados.')).not.toBeInTheDocument()
  })

  it('nitYRazonSocialSonSoloLecturaConNotaDeSoporte', async () => {
    montar()
    await screen.findByText('Comercial Andina S.A.S.')
    expect(screen.getByText(/NIT 901482310-2/)).toBeInTheDocument()
    expect(screen.getByText('Para cambiarlos, contacta a soporte')).toBeInTheDocument()
    expect(screen.queryByLabelText(/razón social/i)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/^NIT/i)).not.toBeInTheDocument()
  })

  it('una cédula se rotula CC', async () => {
    api.empresa.mockResolvedValue({ ...EMPRESA, tipoDocumento: 'CC', nit: '1020304050' })
    montar()
    expect(await screen.findByText(/CC 1020304050/)).toBeInTheDocument()
  })

  it('vendedorVeSoloLectura: ve los datos sin campos ni botón de guardar', async () => {
    guardarSesion({ token: 't', nombre: 'Ana', rol: 'VENDEDOR' })
    montar()
    await screen.findByText('Comercial Andina S.A.S.')
    expect(screen.getByText('Calle 10 # 5-20')).toBeInTheDocument()
    expect(screen.getByText('3001112233')).toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Guardar cambios' })).not.toBeInTheDocument()
  })
})

/**
 * Sección Empresas de la plataforma (pantallas 70, 71, 74 y 75). Se prueba
 * montada en sus rutas, con el módulo de API simulado y el ErrorApi real.
 */
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ErrorApi } from '../../servicios/api.js'
import { apiPlataformaEmpresas } from '../../servicios/api/plataforma-empresas.js'
import Empresas from './index.jsx'

vi.mock('../../servicios/api/plataforma-empresas.js', () => ({
  apiPlataformaEmpresas: {
    listar: vi.fn(), ver: vi.fn(), darDeAlta: vi.fn(),
    activar: vi.fn(), desactivar: vi.fn(), desbloquearUsuario: vi.fn(),
  },
}))

const BASE = {
  empresaId: 4, nit: '900123', razonSocial: 'Ferretería Norte', correoContacto: 'f@norte.co',
  telefono: '3001234567', ciudad: 'Cali', estado: 'ACTIVA', creadaEn: '2026-09-18T09:14:00',
  plan: 'BASICO', inicioPrueba: '2026-09-18', finPrueba: '2026-12-18', estadoSuscripcion: 'AL_DIA', usuarios: 3,
}
const PRUEBA = { ...BASE, empresaId: 5, razonSocial: 'Repuestos Sur', estado: 'PRUEBA', usuarios: 1 }
const APAGADA = { ...BASE, empresaId: 6, razonSocial: 'Tornillos Este', estado: 'DESACTIVADA', usuarios: 2 }

function montar(ruta = '/plataforma/empresas') {
  return render(
    <MemoryRouter initialEntries={[ruta]}>
      <Routes>
        <Route path="/plataforma/empresas/*" element={<Empresas />} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  Object.values(apiPlataformaEmpresas).forEach((f) => f.mockReset())
})

describe('lista de empresas', () => {
  it('muestra carga, una fila por empresa con el estado en texto y un resumen con lo que da la lista', async () => {
    apiPlataformaEmpresas.listar.mockResolvedValue([BASE, PRUEBA, APAGADA])
    montar()
    expect(screen.getByRole('status')).toHaveTextContent(/cargando/i)
    const tabla = await screen.findByRole('table', { name: /empresas/i })
    const filas = within(tabla).getAllByRole('row')
    expect(filas).toHaveLength(4)
    expect(within(filas[1]).getByText('Activa')).toBeInTheDocument()
    expect(within(filas[2]).getByText('En prueba')).toBeInTheDocument()
    expect(within(filas[3]).getByText('Desactivada')).toBeInTheDocument()
    expect(within(filas[1]).getByRole('link', { name: 'Ferretería Norte' })).toHaveAttribute('href', '/plataforma/empresas/4')
    const resumen = screen.getByRole('list', { name: /resumen/i })
    expect(within(resumen).getByText('Activas: 1')).toBeInTheDocument()
    expect(within(resumen).getByText('En prueba: 1')).toBeInTheDocument()
    expect(within(resumen).getByText('Desactivadas: 1')).toBeInTheDocument()
    expect(apiPlataformaEmpresas.listar).toHaveBeenCalledTimes(1)
  })

  it('no habla de planes ni de cobros', async () => {
    apiPlataformaEmpresas.listar.mockResolvedValue([BASE])
    montar()
    await screen.findByRole('table')
    expect(screen.queryByText(/plan|suscripci|cobro|factura/i)).not.toBeInTheDocument()
  })

  it('vacío: invita a dar de alta la primera', async () => {
    apiPlataformaEmpresas.listar.mockResolvedValue([])
    montar()
    expect(await screen.findByText(/todavía no hay empresas/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /nueva empresa/i })).toHaveAttribute('href', '/plataforma/empresas/nueva')
  })

  it('error: dice qué pasó y reintenta', async () => {
    apiPlataformaEmpresas.listar.mockRejectedValueOnce(new ErrorApi(500, 'Falló la base'))
    apiPlataformaEmpresas.listar.mockResolvedValueOnce([BASE])
    montar()
    expect(await screen.findByRole('alert')).toHaveTextContent('Falló la base')
    await userEvent.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByRole('table')).toBeInTheDocument()
  })

  it('403: sin permiso y sin botón de alta', async () => {
    apiPlataformaEmpresas.listar.mockRejectedValue(new ErrorApi(403, 'No'))
    montar()
    expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /nueva empresa/i })).not.toBeInTheDocument()
  })
})

describe('ficha de una empresa', () => {
  it('muestra sus datos', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    montar('/plataforma/empresas/4')
    expect(await screen.findByRole('heading', { level: 1, name: 'Ferretería Norte' })).toBeInTheDocument()
    expect(screen.getByText('900123')).toBeInTheDocument()
    expect(screen.getByText('Cali')).toBeInTheDocument()
    expect(screen.getByText('Activa')).toBeInTheDocument()
    expect(apiPlataformaEmpresas.ver).toHaveBeenCalledWith('4')
  })

  it('404: dice que no existe', async () => {
    apiPlataformaEmpresas.ver.mockRejectedValue(new ErrorApi(404, 'La empresa no existe.'))
    montar('/plataforma/empresas/99')
    expect(await screen.findByText(/no encontramos esa empresa/i)).toBeInTheDocument()
  })

  it('403: sin permiso', async () => {
    apiPlataformaEmpresas.ver.mockRejectedValue(new ErrorApi(403, 'No'))
    montar('/plataforma/empresas/4')
    expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument()
  })

  it('error con reintento', async () => {
    apiPlataformaEmpresas.ver.mockRejectedValueOnce(new ErrorApi(500, 'Se cayó'))
    apiPlataformaEmpresas.ver.mockResolvedValueOnce(BASE)
    montar('/plataforma/empresas/4')
    expect(await screen.findByRole('alert')).toHaveTextContent('Se cayó')
    await userEvent.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Ferretería Norte' })).toBeInTheDocument()
  })

  it('desactivar pide confirmación, explica qué pasa con sus usuarios y manda el motivo', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    apiPlataformaEmpresas.desactivar.mockResolvedValue({ ...BASE, estado: 'DESACTIVADA' })
    montar('/plataforma/empresas/4')
    await userEvent.click(await screen.findByRole('button', { name: 'Desactivar' }))
    const caja = screen.getByRole('group', { name: /desactivar a ferretería norte/i })
    expect(caja).toHaveTextContent(/3 usuarios/i)
    expect(caja).toHaveTextContent(/solo consultar/i)
    expect(caja).toHaveTextContent(/no se borra nada/i)
    expect(apiPlataformaEmpresas.desactivar).not.toHaveBeenCalled()
    await userEvent.type(screen.getByLabelText(/motivo/i), 'Falta de pago')
    apiPlataformaEmpresas.ver.mockResolvedValue({ ...BASE, estado: 'DESACTIVADA' })
    await userEvent.click(screen.getByRole('button', { name: 'Sí, desactivar' }))
    await waitFor(() => expect(apiPlataformaEmpresas.desactivar).toHaveBeenCalledWith(4, 'Falta de pago'))
    expect(await screen.findByText(/empresa desactivada/i)).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Activar' })).toBeInTheDocument()
  })

  it('cancelar la confirmación no envía nada', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    montar('/plataforma/empresas/4')
    await userEvent.click(await screen.findByRole('button', { name: 'Desactivar' }))
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(apiPlataformaEmpresas.desactivar).not.toHaveBeenCalled()
  })

  it('un error del servidor al desactivar se muestra con su detalle', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    apiPlataformaEmpresas.desactivar.mockRejectedValue(new ErrorApi(409, 'No se puede ahora.'))
    montar('/plataforma/empresas/4')
    await userEvent.click(await screen.findByRole('button', { name: 'Desactivar' }))
    await userEvent.click(screen.getByRole('button', { name: 'Sí, desactivar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('No se puede ahora.')
  })

  it('activar una empresa desactivada pide confirmación y la activa', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue({ ...BASE, estado: 'DESACTIVADA' })
    apiPlataformaEmpresas.activar.mockResolvedValue(BASE)
    montar('/plataforma/empresas/4')
    await userEvent.click(await screen.findByRole('button', { name: 'Activar' }))
    expect(apiPlataformaEmpresas.activar).not.toHaveBeenCalled()
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    await userEvent.click(screen.getByRole('button', { name: 'Sí, activar' }))
    await waitFor(() => expect(apiPlataformaEmpresas.activar).toHaveBeenCalledWith(4, ''))
    expect(await screen.findByText(/empresa activada/i)).toBeInTheDocument()
  })
})

describe('desbloquear a un usuario de la empresa', () => {
  it('explica que no cambia la contraseña y que el bloqueo caduca a los 15 minutos, y no restablece contraseñas', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    montar('/plataforma/empresas/4')
    const seccion = await screen.findByRole('region', { name: /desbloquear a un usuario/i })
    expect(seccion).toHaveTextContent(/no cambia la contraseña/i)
    expect(seccion).toHaveTextContent(/15 minutos/i)
    expect(screen.queryByText(/restablecer/i)).not.toBeInTheDocument()
  })

  it('pide confirmación y llama a la ruta con la empresa y el usuario', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    apiPlataformaEmpresas.desbloquearUsuario.mockResolvedValue(null)
    montar('/plataforma/empresas/4')
    await userEvent.type(await screen.findByLabelText(/número del usuario/i), '9')
    await userEvent.click(screen.getByRole('button', { name: 'Desbloquear' }))
    expect(apiPlataformaEmpresas.desbloquearUsuario).not.toHaveBeenCalled()
    expect(screen.getByRole('group', { name: /desbloquear al usuario 9/i })).toHaveTextContent(/no cambia la contraseña/i)
    await userEvent.click(screen.getByRole('button', { name: 'Sí, desbloquear' }))
    await waitFor(() => expect(apiPlataformaEmpresas.desbloquearUsuario).toHaveBeenCalledWith(4, 9))
    expect(await screen.findByText(/usuario 9 desbloqueado/i)).toBeInTheDocument()
  })

  it('sin un número válido no envía y marca el campo', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    montar('/plataforma/empresas/4')
    await userEvent.click(await screen.findByRole('button', { name: 'Desbloquear' }))
    const campo = screen.getByLabelText(/número del usuario/i)
    expect(campo).toHaveAttribute('aria-invalid', 'true')
    expect(campo).toHaveFocus()
    expect(screen.getByText(/escribe el número del usuario/i)).toBeInTheDocument()
    expect(apiPlataformaEmpresas.desbloquearUsuario).not.toHaveBeenCalled()
  })

  it('un 404 del servidor se muestra con su detalle', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    apiPlataformaEmpresas.desbloquearUsuario.mockRejectedValue(new ErrorApi(404, 'El usuario no existe.'))
    montar('/plataforma/empresas/4')
    await userEvent.type(await screen.findByLabelText(/número del usuario/i), '9')
    await userEvent.click(screen.getByRole('button', { name: 'Desbloquear' }))
    await userEvent.click(screen.getByRole('button', { name: 'Sí, desbloquear' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('El usuario no existe.')
  })
})

describe('alta de una empresa', () => {
  const llenar = async ({ clave = 'abcdefgh', repite = clave } = {}) => {
    await userEvent.type(screen.getByLabelText(/identificación tributaria/i), '900555')
    await userEvent.type(screen.getByLabelText('Razón social'), 'Tienda Nueva')
    await userEvent.type(screen.getByLabelText('Nombre del administrador'), 'Ana Gómez')
    await userEvent.type(screen.getByLabelText('Correo del administrador'), 'ana@nueva.co')
    await userEvent.type(screen.getByLabelText('Contraseña inicial'), clave)
    await userEvent.type(screen.getByLabelText('Repite la contraseña'), repite)
  }

  it('envía los datos con su primer administrador y va a la ficha', async () => {
    apiPlataformaEmpresas.darDeAlta.mockResolvedValue({ ...BASE, empresaId: 12, razonSocial: 'Tienda Nueva' })
    apiPlataformaEmpresas.ver.mockResolvedValue({ ...BASE, empresaId: 12, razonSocial: 'Tienda Nueva' })
    montar('/plataforma/empresas/nueva')
    await llenar()
    await userEvent.click(screen.getByRole('button', { name: 'Dar de alta' }))
    await waitFor(() => expect(apiPlataformaEmpresas.darDeAlta).toHaveBeenCalledTimes(1))
    expect(apiPlataformaEmpresas.darDeAlta.mock.calls[0][0]).toMatchObject({
      nit: '900555', razonSocial: 'Tienda Nueva', administradorNombre: 'Ana Gómez',
      administradorCorreo: 'ana@nueva.co', administradorClave: 'abcdefgh',
    })
    expect(await screen.findByRole('heading', { level: 1, name: 'Tienda Nueva' })).toBeInTheDocument()
    expect(screen.getByText(/empresa creada/i)).toBeInTheDocument()
    expect(screen.queryByText('abcdefgh')).not.toBeInTheDocument()
  })

  it('formulario vacío: no envía, marca cada campo y enfoca el primer error', async () => {
    montar('/plataforma/empresas/nueva')
    await userEvent.click(screen.getByRole('button', { name: 'Dar de alta' }))
    expect(apiPlataformaEmpresas.darDeAlta).not.toHaveBeenCalled()
    const nit = screen.getByLabelText(/identificación tributaria/i)
    expect(nit).toHaveAttribute('aria-invalid', 'true')
    expect(nit).toHaveFocus()
    expect(screen.getByText('Escribe la razón social.')).toBeInTheDocument()
    expect(screen.getByText('Escribe la contraseña inicial.')).toBeInTheDocument()
  })

  it('contraseña corta o que no coincide no se envía', async () => {
    montar('/plataforma/empresas/nueva')
    await llenar({ clave: 'corta', repite: 'corta' })
    await userEvent.click(screen.getByRole('button', { name: 'Dar de alta' }))
    expect(screen.getByText(/al menos 8 caracteres/i)).toBeInTheDocument()
    await userEvent.clear(screen.getByLabelText('Contraseña inicial'))
    await userEvent.type(screen.getByLabelText('Contraseña inicial'), 'abcdefgh')
    await userEvent.click(screen.getByRole('button', { name: 'Dar de alta' }))
    expect(screen.getByText('No coincide con la contraseña.')).toBeInTheDocument()
    expect(apiPlataformaEmpresas.darDeAlta).not.toHaveBeenCalled()
  })

  it('un 409 por NIT repetido queda junto al campo', async () => {
    apiPlataformaEmpresas.darDeAlta.mockRejectedValue(new ErrorApi(409, 'Ya hay una empresa con ese NIT.'))
    montar('/plataforma/empresas/nueva')
    await llenar()
    await userEvent.click(screen.getByRole('button', { name: 'Dar de alta' }))
    const nit = screen.getByLabelText(/identificación tributaria/i)
    await waitFor(() => expect(nit).toHaveAttribute('aria-invalid', 'true'))
    expect(screen.getByText('Ya hay una empresa con ese NIT.')).toBeInTheDocument()
  })

  it('un 400 se muestra arriba y el botón vuelve a servir', async () => {
    apiPlataformaEmpresas.darDeAlta.mockRejectedValue(new ErrorApi(400, 'El correo no tiene forma de correo.'))
    montar('/plataforma/empresas/nueva')
    await llenar()
    await userEvent.click(screen.getByRole('button', { name: 'Dar de alta' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('El correo no tiene forma de correo.')
    expect(screen.getByRole('button', { name: 'Dar de alta' })).toBeEnabled()
  })

  it('el botón se deshabilita mientras envía y solo se envía una vez', async () => {
    let resolver
    apiPlataformaEmpresas.darDeAlta.mockReturnValue(new Promise((r) => { resolver = r }))
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    montar('/plataforma/empresas/nueva')
    await llenar()
    await userEvent.click(screen.getByRole('button', { name: 'Dar de alta' }))
    const boton = await screen.findByRole('button', { name: /dando de alta/i })
    expect(boton).toBeDisabled()
    resolver(BASE)
    expect(apiPlataformaEmpresas.darDeAlta).toHaveBeenCalledTimes(1)
  })
})

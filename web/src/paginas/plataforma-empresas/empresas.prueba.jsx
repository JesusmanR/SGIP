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
    activar: vi.fn(), desactivar: vi.fn(), listarUsuarios: vi.fn(), desbloquearUsuario: vi.fn(),
  },
}))

const BASE = {
  empresaId: 4, nit: '900123', razonSocial: 'Ferretería Norte', correoContacto: 'f@norte.co',
  telefono: '3001234567', ciudad: 'Cali', estado: 'ACTIVA', creadaEn: '2026-09-18T09:14:00',
  plan: 'BASICO', inicioPrueba: '2026-09-18', finPrueba: '2026-12-18', estadoSuscripcion: 'AL_DIA', usuarios: 3,
}
const PRUEBA = { ...BASE, empresaId: 5, razonSocial: 'Repuestos Sur', estado: 'PRUEBA', usuarios: 1 }
const APAGADA = { ...BASE, empresaId: 6, razonSocial: 'Tornillos Este', estado: 'DESACTIVADA', usuarios: 2 }

// La empresa 4 tiene 3 usuarios en total, pero solo 2 con la cuenta activa.
const ANA = { id: 20, nombre: 'Ana Pérez', correo: 'ana@norte.co', rol: 'VENDEDOR', activo: true, bloqueado: true }
const LUIS = { id: 21, nombre: 'Luis Gómez', correo: 'luis@norte.co', rol: 'ADMINISTRADOR', activo: true, bloqueado: false }
const MARTA = { id: 22, nombre: 'Marta Ríos', correo: 'marta@norte.co', rol: 'VENDEDOR', activo: false, bloqueado: false }
const USUARIOS = [ANA, LUIS, MARTA]

// Los textos con que el servidor distingue los dos 409 del alta (ServicioPlataforma.darDeAlta).
const DETALLE_NIT = 'Ya hay una empresa registrada con la identificación tributaria 900555.'
const DETALLE_CORREO = 'El correo ana@nueva.co ya pertenece a un usuario de la plataforma.'

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
  apiPlataformaEmpresas.listarUsuarios.mockResolvedValue([])
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
    apiPlataformaEmpresas.listarUsuarios.mockResolvedValue(USUARIOS)
    apiPlataformaEmpresas.desactivar.mockResolvedValue({ ...BASE, estado: 'DESACTIVADA' })
    montar('/plataforma/empresas/4')
    await screen.findByText('Marta Ríos')
    await userEvent.click(await screen.findByRole('button', { name: 'Desactivar' }))
    const caja = screen.getByRole('group', { name: /desactivar a ferretería norte/i })
    expect(caja).toHaveTextContent(/sus 2 usuarios activos podrán entrar/i)
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

  it('con un solo usuario activo concuerda en singular', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue({ ...BASE, usuarios: 2 })
    apiPlataformaEmpresas.listarUsuarios.mockResolvedValue([LUIS, MARTA])
    montar('/plataforma/empresas/4')
    await screen.findByText('Luis Gómez')
    await userEvent.click(await screen.findByRole('button', { name: 'Desactivar' }))
    const caja = screen.getByRole('group', { name: /desactivar a ferretería norte/i })
    expect(caja).toHaveTextContent(/su único usuario activo podrá entrar/i)
    expect(caja).not.toHaveTextContent(/podrán entrar/i)
  })

  it('si no se pudo saber quiénes son, no pone cifra de usuarios', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    apiPlataformaEmpresas.listarUsuarios.mockRejectedValue(new ErrorApi(500, 'Se cayó la lista'))
    montar('/plataforma/empresas/4')
    await screen.findByText('Se cayó la lista')
    await userEvent.click(await screen.findByRole('button', { name: 'Desactivar' }))
    const caja = screen.getByRole('group', { name: /desactivar a ferretería norte/i })
    expect(caja).toHaveTextContent(/sus usuarios podrán entrar/i)
    expect(caja).not.toHaveTextContent(/\d/)
  })

  it('al cancelar la confirmación el foco vuelve al botón que la abrió', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    montar('/plataforma/empresas/4')
    await userEvent.click(await screen.findByRole('button', { name: 'Desactivar' }))
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(screen.getByRole('button', { name: 'Desactivar' })).toHaveFocus()
  })

  it('al confirmar con éxito el botón ya no existe y el foco va a los datos de la empresa', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    apiPlataformaEmpresas.desactivar.mockResolvedValue({ ...BASE, estado: 'DESACTIVADA' })
    montar('/plataforma/empresas/4')
    await userEvent.click(await screen.findByRole('button', { name: 'Desactivar' }))
    apiPlataformaEmpresas.ver.mockResolvedValue({ ...BASE, estado: 'DESACTIVADA' })
    await userEvent.click(screen.getByRole('button', { name: 'Sí, desactivar' }))
    await screen.findByRole('button', { name: 'Activar' })
    expect(screen.getByRole('region', { name: /datos de la empresa/i })).toHaveFocus()
  })

  it('un error al activar se muestra con su detalle y el foco vuelve al botón Activar', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue({ ...BASE, estado: 'PRUEBA' })
    apiPlataformaEmpresas.activar.mockRejectedValue(new ErrorApi(500, 'No se pudo activar ahora.'))
    montar('/plataforma/empresas/4')
    await userEvent.click(await screen.findByRole('button', { name: 'Activar' }))
    await userEvent.click(screen.getByRole('button', { name: 'Sí, activar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('No se pudo activar ahora.')
    expect(screen.getByRole('button', { name: 'Activar' })).toHaveFocus()
  })

  it('un 403 en una acción lo dice y deja la ficha en pie', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    apiPlataformaEmpresas.desactivar.mockRejectedValue(new ErrorApi(403, 'Sin permiso para esta operación.'))
    montar('/plataforma/empresas/4')
    await userEvent.click(await screen.findByRole('button', { name: 'Desactivar' }))
    await userEvent.click(screen.getByRole('button', { name: 'Sí, desactivar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Sin permiso para esta operación.')
    expect(screen.getByRole('heading', { level: 1, name: 'Ferretería Norte' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Desactivar' })).toBeEnabled()
  })

  it('doble clic en confirmar envía una sola vez', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    let resolver
    apiPlataformaEmpresas.desactivar.mockReturnValue(new Promise((r) => { resolver = r }))
    montar('/plataforma/empresas/4')
    await userEvent.click(await screen.findByRole('button', { name: 'Desactivar' }))
    await userEvent.dblClick(screen.getByRole('button', { name: 'Sí, desactivar' }))
    expect(apiPlataformaEmpresas.desactivar).toHaveBeenCalledTimes(1)
    resolver({ ...BASE, estado: 'DESACTIVADA' })
  })
})

describe('usuarios de la empresa y desbloqueo por persona', () => {
  it('lista a las personas con su rol y su estado en texto, y ofrece desbloquear solo al bloqueado', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    apiPlataformaEmpresas.listarUsuarios.mockResolvedValue(USUARIOS)
    montar('/plataforma/empresas/4')
    const tabla = await screen.findByRole('table', { name: /usuarios de la empresa/i })
    const filas = within(tabla).getAllByRole('row')
    expect(filas).toHaveLength(4)
    expect(filas[1]).toHaveTextContent('Ana Pérez')
    expect(filas[1]).toHaveTextContent('ana@norte.co')
    expect(filas[1]).toHaveTextContent('Vendedor')
    expect(filas[1]).toHaveTextContent('Bloqueado')
    expect(filas[2]).toHaveTextContent('Administrador')
    expect(filas[2]).toHaveTextContent('Activo')
    expect(filas[3]).toHaveTextContent('Desactivado')
    expect(within(tabla).getAllByRole('button')).toHaveLength(1)
    expect(within(filas[1]).getByRole('button', { name: 'Desbloquear a Ana Pérez' })).toBeInTheDocument()
    expect(apiPlataformaEmpresas.listarUsuarios).toHaveBeenCalledWith('4')
    expect(apiPlataformaEmpresas.listarUsuarios).toHaveBeenCalledTimes(1)
  })

  it('el rol de cliente también sale en palabras, no como código', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    apiPlataformaEmpresas.listarUsuarios.mockResolvedValue([{ ...LUIS, id: 23, nombre: 'Rosa Díaz', rol: 'CLIENTE' }])
    montar('/plataforma/empresas/4')
    const tabla = await screen.findByRole('table', { name: /usuarios de la empresa/i })
    expect(within(tabla).getAllByRole('row')[1]).toHaveTextContent('Cliente')
    expect(tabla).not.toHaveTextContent('CLIENTE')
  })

  it('ya no pide un número de usuario ni lo explica', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    apiPlataformaEmpresas.listarUsuarios.mockResolvedValue(USUARIOS)
    montar('/plataforma/empresas/4')
    await screen.findByRole('table', { name: /usuarios de la empresa/i })
    expect(screen.queryByLabelText(/número del usuario/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/lo ve el administrador/i)).not.toBeInTheDocument()
  })

  it('explica que no cambia la contraseña, sin fijar cuántos minutos dura el bloqueo, y no restablece contraseñas', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    apiPlataformaEmpresas.listarUsuarios.mockResolvedValue(USUARIOS)
    montar('/plataforma/empresas/4')
    const seccion = await screen.findByRole('region', { name: /usuarios de la empresa/i })
    expect(seccion).toHaveTextContent(/no cambia la contraseña/i)
    expect(seccion).toHaveTextContent(/se quita solo/i)
    expect(seccion).not.toHaveTextContent(/\d+\s*minutos/i)
    expect(screen.queryByText(/restablecer/i)).not.toBeInTheDocument()
  })

  it('sin usuarios: lo dice', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    montar('/plataforma/empresas/4')
    expect(await screen.findByText(/esta empresa no tiene usuarios/i)).toBeInTheDocument()
  })

  it('error al cargar los usuarios: dice qué pasó y reintenta', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    apiPlataformaEmpresas.listarUsuarios.mockRejectedValueOnce(new ErrorApi(500, 'Se cayó la lista'))
    apiPlataformaEmpresas.listarUsuarios.mockResolvedValueOnce(USUARIOS)
    montar('/plataforma/empresas/4')
    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('Se cayó la lista')
    await userEvent.click(within(alerta).getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByRole('table', { name: /usuarios de la empresa/i })).toBeInTheDocument()
  })

  it('403 al listar los usuarios: sin permiso', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    apiPlataformaEmpresas.listarUsuarios.mockRejectedValue(new ErrorApi(403, 'No'))
    montar('/plataforma/empresas/4')
    expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument()
  })

  it('pide confirmación que nombra a la persona y su correo, desbloquea y recarga la lista', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    apiPlataformaEmpresas.listarUsuarios.mockResolvedValueOnce(USUARIOS)
    apiPlataformaEmpresas.desbloquearUsuario.mockResolvedValue(null)
    montar('/plataforma/empresas/4')
    await userEvent.click(await screen.findByRole('button', { name: 'Desbloquear a Ana Pérez' }))
    expect(apiPlataformaEmpresas.desbloquearUsuario).not.toHaveBeenCalled()
    const caja = screen.getByRole('group', { name: /desbloquear a ana pérez/i })
    expect(caja).toHaveTextContent('ana@norte.co')
    expect(caja).toHaveTextContent(/no cambia su contraseña/i)
    apiPlataformaEmpresas.listarUsuarios.mockResolvedValue([{ ...ANA, bloqueado: false }, LUIS, MARTA])
    await userEvent.click(screen.getByRole('button', { name: 'Sí, desbloquear' }))
    await waitFor(() => expect(apiPlataformaEmpresas.desbloquearUsuario).toHaveBeenCalledWith(4, 20))
    expect(await screen.findByText(/se desbloqueó a ana pérez/i)).toBeInTheDocument()
    await waitFor(() => expect(apiPlataformaEmpresas.listarUsuarios).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.queryByRole('button', { name: /desbloquear a/i })).not.toBeInTheDocument())
    // El botón que abrió la confirmación ya no existe: el foco va a un lugar estable.
    expect(screen.getByRole('region', { name: /usuarios de la empresa/i })).toHaveFocus()
  })

  it('al cancelar no envía nada y el foco vuelve al botón de esa persona', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    apiPlataformaEmpresas.listarUsuarios.mockResolvedValue(USUARIOS)
    montar('/plataforma/empresas/4')
    await userEvent.click(await screen.findByRole('button', { name: 'Desbloquear a Ana Pérez' }))
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(apiPlataformaEmpresas.desbloquearUsuario).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Desbloquear a Ana Pérez' })).toHaveFocus()
  })

  it('un 404 del servidor se muestra con su detalle y el foco vuelve al botón', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    apiPlataformaEmpresas.listarUsuarios.mockResolvedValue(USUARIOS)
    apiPlataformaEmpresas.desbloquearUsuario.mockRejectedValue(new ErrorApi(404, 'El usuario no existe.'))
    montar('/plataforma/empresas/4')
    await userEvent.click(await screen.findByRole('button', { name: 'Desbloquear a Ana Pérez' }))
    await userEvent.click(screen.getByRole('button', { name: 'Sí, desbloquear' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('El usuario no existe.')
    expect(screen.getByRole('button', { name: 'Desbloquear a Ana Pérez' })).toHaveFocus()
  })

  it('un 403 al desbloquear lo dice', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    apiPlataformaEmpresas.listarUsuarios.mockResolvedValue(USUARIOS)
    apiPlataformaEmpresas.desbloquearUsuario.mockRejectedValue(new ErrorApi(403, 'Sin permiso para esta operación.'))
    montar('/plataforma/empresas/4')
    await userEvent.click(await screen.findByRole('button', { name: 'Desbloquear a Ana Pérez' }))
    await userEvent.click(screen.getByRole('button', { name: 'Sí, desbloquear' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Sin permiso para esta operación.')
  })

  it('doble clic en confirmar desbloquea una sola vez', async () => {
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    apiPlataformaEmpresas.listarUsuarios.mockResolvedValue(USUARIOS)
    let resolver
    apiPlataformaEmpresas.desbloquearUsuario.mockReturnValue(new Promise((r) => { resolver = r }))
    montar('/plataforma/empresas/4')
    await userEvent.click(await screen.findByRole('button', { name: 'Desbloquear a Ana Pérez' }))
    await userEvent.dblClick(screen.getByRole('button', { name: 'Sí, desbloquear' }))
    expect(apiPlataformaEmpresas.desbloquearUsuario).toHaveBeenCalledTimes(1)
    resolver(null)
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

  it('las dos contraseñas son campos de contraseña: no se leen en pantalla', async () => {
    montar('/plataforma/empresas/nueva')
    expect(screen.getByLabelText('Contraseña inicial')).toHaveAttribute('type', 'password')
    expect(screen.getByLabelText('Repite la contraseña')).toHaveAttribute('type', 'password')
  })

  it('una contraseña de solo espacios se rechaza como si estuviera vacía', async () => {
    montar('/plataforma/empresas/nueva')
    await llenar({ clave: '        ' })
    await userEvent.click(screen.getByRole('button', { name: 'Dar de alta' }))
    expect(screen.getByText('Escribe la contraseña inicial.')).toBeInTheDocument()
    expect(screen.getByLabelText('Contraseña inicial')).toHaveAttribute('aria-invalid', 'true')
    expect(apiPlataformaEmpresas.darDeAlta).not.toHaveBeenCalled()
  })

  it('un 409 por NIT repetido queda junto al NIT y no junto al correo', async () => {
    apiPlataformaEmpresas.darDeAlta.mockRejectedValue(new ErrorApi(409, DETALLE_NIT))
    montar('/plataforma/empresas/nueva')
    await llenar()
    await userEvent.click(screen.getByRole('button', { name: 'Dar de alta' }))
    const nit = screen.getByLabelText(/identificación tributaria/i)
    await waitFor(() => expect(nit).toHaveAttribute('aria-invalid', 'true'))
    expect(screen.getByText(DETALLE_NIT)).toBeInTheDocument()
    expect(nit).toHaveFocus()
    expect(screen.getByLabelText('Correo del administrador')).not.toHaveAttribute('aria-invalid', 'true')
  })

  it('un 409 por el correo del administrador queda junto al correo y no junto al NIT', async () => {
    apiPlataformaEmpresas.darDeAlta.mockRejectedValue(new ErrorApi(409, DETALLE_CORREO))
    montar('/plataforma/empresas/nueva')
    await llenar()
    await userEvent.click(screen.getByRole('button', { name: 'Dar de alta' }))
    const correo = screen.getByLabelText('Correo del administrador')
    await waitFor(() => expect(correo).toHaveAttribute('aria-invalid', 'true'))
    expect(screen.getByText(DETALLE_CORREO)).toBeInTheDocument()
    expect(correo).toHaveFocus()
    expect(screen.getByLabelText(/identificación tributaria/i)).not.toHaveAttribute('aria-invalid', 'true')
  })

  it('tras un 409 por correo, corregir el correo limpia el error y deja volver a enviar', async () => {
    apiPlataformaEmpresas.darDeAlta.mockRejectedValueOnce(new ErrorApi(409, DETALLE_CORREO))
    apiPlataformaEmpresas.darDeAlta.mockResolvedValueOnce({ ...BASE, empresaId: 12, razonSocial: 'Tienda Nueva' })
    apiPlataformaEmpresas.ver.mockResolvedValue({ ...BASE, empresaId: 12, razonSocial: 'Tienda Nueva' })
    montar('/plataforma/empresas/nueva')
    await llenar()
    await userEvent.click(screen.getByRole('button', { name: 'Dar de alta' }))
    const correo = await screen.findByLabelText('Correo del administrador')
    await waitFor(() => expect(correo).toHaveAttribute('aria-invalid', 'true'))

    // Sin cambiar nada no vuelve a enviar lo mismo.
    await userEvent.click(screen.getByRole('button', { name: 'Dar de alta' }))
    expect(apiPlataformaEmpresas.darDeAlta).toHaveBeenCalledTimes(1)

    await userEvent.clear(correo)
    await userEvent.type(correo, 'otra@nueva.co')
    expect(correo).not.toHaveAttribute('aria-invalid', 'true')
    expect(screen.queryByText(DETALLE_CORREO)).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Dar de alta' }))
    await waitFor(() => expect(apiPlataformaEmpresas.darDeAlta).toHaveBeenCalledTimes(2))
    expect(apiPlataformaEmpresas.darDeAlta.mock.calls[1][0]).toMatchObject({ administradorCorreo: 'otra@nueva.co' })
    expect(await screen.findByRole('heading', { level: 1, name: 'Tienda Nueva' })).toBeInTheDocument()
  })

  it('tras un 409 por NIT, corregir el NIT deja volver a enviar sin tocar el correo', async () => {
    apiPlataformaEmpresas.darDeAlta.mockRejectedValueOnce(new ErrorApi(409, DETALLE_NIT))
    apiPlataformaEmpresas.darDeAlta.mockResolvedValueOnce({ ...BASE, empresaId: 12, razonSocial: 'Tienda Nueva' })
    apiPlataformaEmpresas.ver.mockResolvedValue({ ...BASE, empresaId: 12, razonSocial: 'Tienda Nueva' })
    montar('/plataforma/empresas/nueva')
    await llenar()
    await userEvent.click(screen.getByRole('button', { name: 'Dar de alta' }))
    const nit = await screen.findByLabelText(/identificación tributaria/i)
    await waitFor(() => expect(nit).toHaveAttribute('aria-invalid', 'true'))
    await userEvent.type(nit, '7')
    expect(nit).not.toHaveAttribute('aria-invalid', 'true')
    await userEvent.click(screen.getByRole('button', { name: 'Dar de alta' }))
    await waitFor(() => expect(apiPlataformaEmpresas.darDeAlta).toHaveBeenCalledTimes(2))
  })

  it('un 409 que no es del NIT ni del correo va arriba, no marca ningún campo y deja reintentar', async () => {
    apiPlataformaEmpresas.darDeAlta.mockRejectedValueOnce(
      new ErrorApi(409, 'Dos altas a la vez chocaron. Intenta de nuevo.'),
    )
    apiPlataformaEmpresas.darDeAlta.mockResolvedValueOnce({ ...BASE, empresaId: 12, razonSocial: 'Tienda Nueva' })
    apiPlataformaEmpresas.ver.mockResolvedValue({ ...BASE, empresaId: 12, razonSocial: 'Tienda Nueva' })
    montar('/plataforma/empresas/nueva')
    await llenar()
    await userEvent.click(screen.getByRole('button', { name: 'Dar de alta' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Dos altas a la vez chocaron.')
    expect(screen.getByLabelText(/identificación tributaria/i)).not.toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Correo del administrador')).not.toHaveAttribute('aria-invalid', 'true')
    await userEvent.click(screen.getByRole('button', { name: 'Dar de alta' }))
    await waitFor(() => expect(apiPlataformaEmpresas.darDeAlta).toHaveBeenCalledTimes(2))
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

  it('un doble clic real en «Dar de alta» envía una sola vez', async () => {
    let resolver
    apiPlataformaEmpresas.darDeAlta.mockReturnValue(new Promise((r) => { resolver = r }))
    apiPlataformaEmpresas.ver.mockResolvedValue(BASE)
    montar('/plataforma/empresas/nueva')
    await llenar()
    await userEvent.dblClick(screen.getByRole('button', { name: 'Dar de alta' }))
    expect(apiPlataformaEmpresas.darDeAlta).toHaveBeenCalledTimes(1)
    resolver(BASE)
  })
})

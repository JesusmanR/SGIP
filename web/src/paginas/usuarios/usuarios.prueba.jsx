/**
 * Sección Usuarios (P-20). Se prueba montada en sus rutas reales, con el
 * módulo de API simulado y la clase ErrorApi real: la pantalla decide con
 * `estado` y con el `detail` que trae.
 */
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ErrorApi } from '../../servicios/api.js'
import { apiUsuarios } from '../../servicios/api/usuarios.js'
import Usuarios from './index.jsx'

vi.mock('../../servicios/api/usuarios.js', () => ({
  apiUsuarios: {
    listar: vi.fn(), ver: vi.fn(), crear: vi.fn(), editar: vi.fn(),
    activar: vi.fn(), desactivar: vi.fn(), desbloquear: vi.fn(),
  },
}))

const ANA = {
  usuarioId: 7, nombre: 'Ana Pérez', correo: 'ana@tienda.co', rol: 'VENDEDOR',
  activo: true, bloqueado: false, cambioClaveRequerido: false, creadoEn: '2026-09-18T09:14:00',
}
const LUIS = { ...ANA, usuarioId: 8, nombre: 'Luis Gómez', correo: 'luis@tienda.co', rol: 'ADMINISTRADOR' }
const INACTIVA = { ...ANA, usuarioId: 9, nombre: 'Marta Ríos', correo: 'marta@tienda.co', activo: false }
const BLOQUEADO = { ...ANA, usuarioId: 10, nombre: 'Pedro Soto', correo: 'pedro@tienda.co', bloqueado: true }
const NUEVO = { ...ANA, usuarioId: 11, nombre: 'Nora Díaz', correo: 'nora@tienda.co', cambioClaveRequerido: true }

function montar(ruta = '/usuarios') {
  return render(
    <MemoryRouter initialEntries={[ruta]}>
      <Routes>
        <Route path="/usuarios/*" element={<Usuarios />} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  Object.values(apiUsuarios).forEach((f) => f.mockReset())
})

describe('lista de usuarios', () => {
  it('muestra la carga y luego una fila por usuario, con el estado en texto', async () => {
    apiUsuarios.listar.mockResolvedValue([ANA, LUIS, INACTIVA, BLOQUEADO, NUEVO])
    montar()
    expect(screen.getByRole('status')).toHaveTextContent(/cargando/i)
    const tabla = await screen.findByRole('table', { name: /usuarios/i })
    const filas = within(tabla).getAllByRole('row')
    expect(filas).toHaveLength(6)
    expect(within(filas[1]).getByText('Activo')).toBeInTheDocument()
    expect(within(filas[1]).getByText('Vendedor')).toBeInTheDocument()
    expect(within(filas[2]).getByText('Administrador')).toBeInTheDocument()
    expect(within(filas[3]).getByText('Inactivo')).toBeInTheDocument()
    expect(within(filas[4]).getByText('Bloqueado')).toBeInTheDocument()
    expect(within(filas[5]).getByText(/debe cambiar la contraseña/i)).toBeInTheDocument()
    expect(apiUsuarios.listar).toHaveBeenCalledTimes(1)
  })

  it('una cuenta bloqueada y activa dice las dos cosas', async () => {
    apiUsuarios.listar.mockResolvedValue([BLOQUEADO])
    montar()
    const fila = (await screen.findAllByRole('row'))[1]
    expect(within(fila).getByText('Activo')).toBeInTheDocument()
    expect(within(fila).getByText('Bloqueado')).toBeInTheDocument()
  })

  it('cada nombre lleva a su ficha', async () => {
    apiUsuarios.listar.mockResolvedValue([ANA])
    montar()
    expect(await screen.findByRole('link', { name: 'Ana Pérez' })).toHaveAttribute('href', '/usuarios/7')
  })

  it('ofrece crear un usuario', async () => {
    apiUsuarios.listar.mockResolvedValue([ANA])
    montar()
    expect(await screen.findByRole('link', { name: /nuevo usuario/i })).toHaveAttribute('href', '/usuarios/nuevo')
  })

  it('sin usuarios dice que no hay', async () => {
    apiUsuarios.listar.mockResolvedValue([])
    montar()
    expect(await screen.findByText(/todavía no hay usuarios/i)).toBeInTheDocument()
  })

  it('un error dice qué pasó y reintenta', async () => {
    apiUsuarios.listar
      .mockRejectedValueOnce(new ErrorApi(500, 'El servidor no respondió.'))
      .mockResolvedValueOnce([ANA])
    montar()
    expect(await screen.findByRole('alert')).toHaveTextContent('El servidor no respondió.')
    await userEvent.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByRole('link', { name: 'Ana Pérez' })).toBeInTheDocument()
    expect(apiUsuarios.listar).toHaveBeenCalledTimes(2)
  })

  it('un 403 muestra la pantalla de sin permiso', async () => {
    apiUsuarios.listar.mockRejectedValue(new ErrorApi(403, 'Tu rol no tiene permiso para hacer esto.'))
    montar()
    expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument()
  })
})

describe('ficha de un usuario', () => {
  it('muestra sus datos y el estado en texto, sin ninguna contraseña', async () => {
    apiUsuarios.ver.mockResolvedValue(ANA)
    montar('/usuarios/7')
    expect(await screen.findByRole('heading', { level: 1, name: 'Ana Pérez' })).toBeInTheDocument()
    expect(apiUsuarios.ver).toHaveBeenCalledWith('7')
    expect(screen.getByText('ana@tienda.co')).toBeInTheDocument()
    expect(screen.getByText('Vendedor')).toBeInTheDocument()
    expect(screen.getByText('Activo')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /editar/i })).toHaveAttribute('href', '/usuarios/7/editar')
    expect(screen.queryByText(/restablecer/i)).not.toBeInTheDocument()
  })

  it('un usuario inexistente dice que no se encontró', async () => {
    apiUsuarios.ver.mockRejectedValue(new ErrorApi(404, 'No se pudo completar la operación.'))
    montar('/usuarios/99')
    expect(await screen.findByText(/no encontramos a ese usuario/i)).toBeInTheDocument()
  })

  it('un error de carga reintenta', async () => {
    apiUsuarios.ver
      .mockRejectedValueOnce(new ErrorApi(500, 'Falló.'))
      .mockResolvedValueOnce(ANA)
    montar('/usuarios/7')
    await userEvent.click(await screen.findByRole('button', { name: /reintentar/i }))
    expect(await screen.findByRole('heading', { name: 'Ana Pérez' })).toBeInTheDocument()
  })

  it('un 403 al cargar muestra sin permiso', async () => {
    apiUsuarios.ver.mockRejectedValue(new ErrorApi(403, 'no'))
    montar('/usuarios/7')
    expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument()
  })

  describe('desactivar', () => {
    it('pide confirmación y dice qué pasa; cancelar no envía nada', async () => {
      apiUsuarios.ver.mockResolvedValue(ANA)
      montar('/usuarios/7')
      await userEvent.click(await screen.findByRole('button', { name: 'Desactivar' }))
      expect(screen.getByText(/no podrá entrar/i)).toBeInTheDocument()
      expect(screen.getByText(/no se borra nada/i)).toBeInTheDocument()
      await userEvent.click(screen.getByRole('button', { name: /cancelar/i }))
      expect(apiUsuarios.desactivar).not.toHaveBeenCalled()
      expect(screen.queryByText(/no podrá entrar/i)).not.toBeInTheDocument()
    })

    it('al confirmar desactiva, recarga y avisa', async () => {
      apiUsuarios.ver.mockResolvedValueOnce(ANA).mockResolvedValueOnce({ ...ANA, activo: false })
      apiUsuarios.desactivar.mockResolvedValue({ ...ANA, activo: false })
      montar('/usuarios/7')
      await userEvent.click(await screen.findByRole('button', { name: 'Desactivar' }))
      await userEvent.click(screen.getByRole('button', { name: /sí, desactivar/i }))
      expect(apiUsuarios.desactivar).toHaveBeenCalledWith(7)
      expect(await screen.findByText('Inactivo')).toBeInTheDocument()
      expect(screen.getByRole('status')).toHaveTextContent(/cuenta desactivada/i)
      expect(screen.getByRole('button', { name: 'Activar' })).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Desactivar' })).not.toBeInTheDocument()
    })

    it('muestra el detail del servidor si lo impide, por ejemplo el último administrador', async () => {
      apiUsuarios.ver.mockResolvedValue(LUIS)
      apiUsuarios.desactivar.mockRejectedValue(
        new ErrorApi(409, 'Es el único administrador activo de la empresa. Nombra otro antes de desactivarlo.'),
      )
      montar('/usuarios/8')
      await userEvent.click(await screen.findByRole('button', { name: 'Desactivar' }))
      await userEvent.click(screen.getByRole('button', { name: /sí, desactivar/i }))
      expect(await screen.findByRole('alert')).toHaveTextContent(/único administrador activo/i)
      expect(screen.getByRole('button', { name: 'Desactivar' })).toBeInTheDocument()
    })

    it('no se envía dos veces mientras espera', async () => {
      apiUsuarios.ver.mockResolvedValue(ANA)
      apiUsuarios.desactivar.mockReturnValue(new Promise(() => {}))
      montar('/usuarios/7')
      await userEvent.click(await screen.findByRole('button', { name: 'Desactivar' }))
      await userEvent.click(screen.getByRole('button', { name: /sí, desactivar/i }))
      expect(screen.getByRole('button', { name: /sí, desactivar/i })).toBeDisabled()
      await userEvent.click(screen.getByRole('button', { name: /sí, desactivar/i }))
      expect(apiUsuarios.desactivar).toHaveBeenCalledTimes(1)
    })
  })

  describe('activar', () => {
    it('activa una cuenta inactiva y la ficha lo refleja', async () => {
      apiUsuarios.ver.mockResolvedValueOnce(INACTIVA).mockResolvedValueOnce({ ...INACTIVA, activo: true })
      apiUsuarios.activar.mockResolvedValue({ ...INACTIVA, activo: true })
      montar('/usuarios/9')
      await userEvent.click(await screen.findByRole('button', { name: 'Activar' }))
      expect(apiUsuarios.activar).toHaveBeenCalledWith(9)
      expect(await screen.findByText('Activo')).toBeInTheDocument()
    })

    it('muestra el error del servidor', async () => {
      apiUsuarios.ver.mockResolvedValue(INACTIVA)
      apiUsuarios.activar.mockRejectedValue(new ErrorApi(500, 'Algo falló al activar.'))
      montar('/usuarios/9')
      await userEvent.click(await screen.findByRole('button', { name: 'Activar' }))
      expect(await screen.findByRole('alert')).toHaveTextContent('Algo falló al activar.')
    })
  })

  describe('desbloquear', () => {
    it('solo aparece en una cuenta bloqueada', async () => {
      apiUsuarios.ver.mockResolvedValue(ANA)
      montar('/usuarios/7')
      await screen.findByRole('heading', { name: 'Ana Pérez' })
      expect(screen.queryByRole('button', { name: /desbloquear/i })).not.toBeInTheDocument()
    })

    it('explica que no cambia la contraseña y pide confirmar', async () => {
      apiUsuarios.ver.mockResolvedValue(BLOQUEADO)
      montar('/usuarios/10')
      expect(await screen.findByText('Bloqueado')).toBeInTheDocument()
      await userEvent.click(screen.getByRole('button', { name: 'Desbloquear' }))
      expect(screen.getByText(/no cambia su contraseña/i)).toBeInTheDocument()
      await userEvent.click(screen.getByRole('button', { name: /cancelar/i }))
      expect(apiUsuarios.desbloquear).not.toHaveBeenCalled()
    })

    it('al confirmar desbloquea y recarga', async () => {
      apiUsuarios.ver.mockResolvedValueOnce(BLOQUEADO).mockResolvedValueOnce({ ...BLOQUEADO, bloqueado: false })
      apiUsuarios.desbloquear.mockResolvedValue(null)
      montar('/usuarios/10')
      await userEvent.click(await screen.findByRole('button', { name: 'Desbloquear' }))
      await userEvent.click(screen.getByRole('button', { name: /sí, desbloquear/i }))
      expect(apiUsuarios.desbloquear).toHaveBeenCalledWith(10)
      await waitFor(() => expect(screen.queryByText('Bloqueado')).not.toBeInTheDocument())
      expect(screen.queryByRole('button', { name: /desbloquear/i })).not.toBeInTheDocument()
    })

    it('muestra el error del servidor', async () => {
      apiUsuarios.ver.mockResolvedValue(BLOQUEADO)
      apiUsuarios.desbloquear.mockRejectedValue(new ErrorApi(404, 'No se pudo completar la operación.'))
      montar('/usuarios/10')
      await userEvent.click(await screen.findByRole('button', { name: 'Desbloquear' }))
      await userEvent.click(screen.getByRole('button', { name: /sí, desbloquear/i }))
      expect(await screen.findByRole('alert')).toHaveTextContent('No se pudo completar la operación.')
    })
  })

  it('no hay botón para restablecer contraseñas', async () => {
    apiUsuarios.ver.mockResolvedValue(BLOQUEADO)
    montar('/usuarios/10')
    await screen.findByText('Bloqueado')
    expect(screen.queryByRole('button', { name: /restablecer|contraseña/i })).not.toBeInTheDocument()
  })
})

describe('crear usuario', () => {
  async function llenar({
    nombre = 'Nora Díaz', correo = 'nora@tienda.co', rol = 'VENDEDOR', clave = 'inicial123', repite = clave,
  } = {}) {
    const u = userEvent.setup()
    if (nombre) await u.type(screen.getByLabelText('Nombre'), nombre)
    if (correo) await u.type(screen.getByLabelText('Correo'), correo)
    if (rol) await u.selectOptions(screen.getByLabelText('Rol'), rol)
    if (clave) await u.type(screen.getByLabelText('Contraseña inicial'), clave)
    if (repite) await u.type(screen.getByLabelText('Repite la contraseña'), repite)
    return u
  }

  it('dice que la contraseña es inicial y que deberá cambiarla en la primera entrada', () => {
    montar('/usuarios/nuevo')
    expect(screen.getByRole('heading', { level: 1, name: /nuevo usuario/i })).toBeInTheDocument()
    expect(screen.getByText(/deberá cambiarla/i)).toBeInTheDocument()
    expect(screen.getByText(/no la vuelve a mostrar/i)).toBeInTheDocument()
    expect(screen.getByLabelText('Contraseña inicial')).toHaveAttribute('type', 'password')
  })

  it('solo ofrece los roles que el administrador puede repartir', () => {
    montar('/usuarios/nuevo')
    const opciones = within(screen.getByLabelText('Rol')).getAllByRole('option').map((o) => o.value)
    expect(opciones).toEqual(['', 'ADMINISTRADOR', 'VENDEDOR', 'CLIENTE'])
  })

  it('con el formulario vacío no envía nada, dice qué falta junto a cada campo y enfoca el primero', async () => {
    montar('/usuarios/nuevo')
    await userEvent.click(screen.getByRole('button', { name: /crear usuario/i }))
    expect(apiUsuarios.crear).not.toHaveBeenCalled()
    expect(screen.getByText('Escribe el nombre.')).toBeInTheDocument()
    expect(screen.getByText('Escribe el correo.')).toBeInTheDocument()
    expect(screen.getByText('Elige un rol.')).toBeInTheDocument()
    expect(screen.getByText('Escribe la contraseña inicial.')).toBeInTheDocument()
    expect(screen.getByLabelText('Nombre')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Nombre')).toHaveFocus()
  })

  it.each([
    ['correo sin forma', { correo: 'ana' }, /correo válido/i],
    ['contraseña corta', { clave: 'corta', repite: 'corta' }, /al menos 8 caracteres/i],
    ['contraseñas distintas', { clave: 'inicial123', repite: 'inicial124' }, /no coincide/i],
  ])('%s: no envía y lo dice', async (_n, cambios, mensaje) => {
    montar('/usuarios/nuevo')
    const u = await llenar(cambios)
    await u.click(screen.getByRole('button', { name: /crear usuario/i }))
    expect(apiUsuarios.crear).not.toHaveBeenCalled()
    expect(screen.getByText(mensaje)).toBeInTheDocument()
  })

  it('crea el usuario, va a su ficha y la contraseña ya no está en pantalla', async () => {
    apiUsuarios.crear.mockResolvedValue(NUEVO)
    apiUsuarios.ver.mockResolvedValue(NUEVO)
    montar('/usuarios/nuevo')
    const u = await llenar({ clave: 'inicial123' })
    await u.click(screen.getByRole('button', { name: /crear usuario/i }))
    expect(apiUsuarios.crear).toHaveBeenCalledWith({
      nombre: 'Nora Díaz', correo: 'nora@tienda.co', clave: 'inicial123', rol: 'VENDEDOR',
    })
    expect(await screen.findByRole('heading', { level: 1, name: 'Nora Díaz' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(/usuario creado/i)
    expect(screen.getByText('Debe cambiar la contraseña')).toBeInTheDocument()
    expect(document.body.innerHTML).not.toContain('inicial123')
    expect(document.querySelectorAll('input[type="password"]')).toHaveLength(0)
  })

  it('un correo repetido (409) se muestra junto al campo del correo, con el detail', async () => {
    apiUsuarios.crear.mockRejectedValue(
      new ErrorApi(409, 'El correo nora@tienda.co ya pertenece a un usuario de la plataforma.'),
    )
    montar('/usuarios/nuevo')
    const u = await llenar()
    await u.click(screen.getByRole('button', { name: /crear usuario/i }))
    expect(await screen.findByText(/ya pertenece a un usuario de la plataforma/i)).toBeInTheDocument()
    expect(screen.getByLabelText('Correo')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Correo')).toHaveFocus()
    expect(screen.getByRole('button', { name: /crear usuario/i })).toBeEnabled()
  })

  it.each([
    [400, 'nombre: El nombre es obligatorio'],
    [403, 'El administrador de una empresa puede asignar ADMINISTRADOR, CLIENTE, VENDEDOR.'],
    [422, 'No se puede crear ahora.'],
  ])('un %s del servidor muestra su detail en un aviso', async (estado, detalle) => {
    apiUsuarios.crear.mockRejectedValue(new ErrorApi(estado, detalle))
    montar('/usuarios/nuevo')
    const u = await llenar()
    await u.click(screen.getByRole('button', { name: /crear usuario/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(detalle)
    expect(screen.queryByText(/no tienes permiso/i)).not.toBeInTheDocument()
  })

  it('el botón queda deshabilitado mientras envía y no duplica la petición', async () => {
    apiUsuarios.crear.mockReturnValue(new Promise(() => {}))
    montar('/usuarios/nuevo')
    const u = await llenar()
    await u.click(screen.getByRole('button', { name: /crear usuario/i }))
    expect(screen.getByRole('button', { name: /creando/i })).toBeDisabled()
    await u.type(screen.getByLabelText('Nombre'), '{Enter}')
    expect(apiUsuarios.crear).toHaveBeenCalledTimes(1)
  })
})

describe('editar usuario', () => {
  it('carga los datos, el correo no se edita y no hay contraseña', async () => {
    apiUsuarios.ver.mockResolvedValue(ANA)
    montar('/usuarios/7/editar')
    expect(await screen.findByLabelText('Nombre')).toHaveValue('Ana Pérez')
    expect(screen.getByLabelText('Rol')).toHaveValue('VENDEDOR')
    expect(screen.getByText('ana@tienda.co')).toBeInTheDocument()
    expect(screen.queryByLabelText('Correo')).not.toBeInTheDocument()
    expect(document.querySelectorAll('input[type="password"]')).toHaveLength(0)
    expect(screen.getByText(/no cambia contraseñas/i)).toBeInTheDocument()
  })

  it('guarda nombre y rol y vuelve a la ficha con aviso', async () => {
    apiUsuarios.ver.mockResolvedValue(ANA)
    apiUsuarios.editar.mockResolvedValue({ ...ANA, nombre: 'Ana María Pérez', rol: 'ADMINISTRADOR' })
    montar('/usuarios/7/editar')
    const nombre = await screen.findByLabelText('Nombre')
    await userEvent.clear(nombre)
    await userEvent.type(nombre, 'Ana María Pérez')
    await userEvent.selectOptions(screen.getByLabelText('Rol'), 'ADMINISTRADOR')
    await userEvent.click(screen.getByRole('button', { name: /guardar cambios/i }))
    expect(apiUsuarios.editar).toHaveBeenCalledWith(7, { nombre: 'Ana María Pérez', rol: 'ADMINISTRADOR' })
    expect(await screen.findByRole('status')).toHaveTextContent(/cambios guardados/i)
  })

  it('con el nombre vacío no envía nada', async () => {
    apiUsuarios.ver.mockResolvedValue(ANA)
    montar('/usuarios/7/editar')
    await userEvent.clear(await screen.findByLabelText('Nombre'))
    await userEvent.click(screen.getByRole('button', { name: /guardar cambios/i }))
    expect(apiUsuarios.editar).not.toHaveBeenCalled()
    expect(screen.getByText('Escribe el nombre.')).toBeInTheDocument()
    expect(screen.getByLabelText('Nombre')).toHaveFocus()
  })

  it('muestra el detail si el servidor lo impide (último administrador)', async () => {
    apiUsuarios.ver.mockResolvedValue(LUIS)
    apiUsuarios.editar.mockRejectedValue(
      new ErrorApi(409, 'Es el único administrador activo de la empresa. Nombra otro antes de cambiarle el rol.'),
    )
    montar('/usuarios/8/editar')
    await userEvent.selectOptions(await screen.findByLabelText('Rol'), 'VENDEDOR')
    await userEvent.click(screen.getByRole('button', { name: /guardar cambios/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/único administrador activo/i)
  })

  it('no se envía dos veces mientras guarda', async () => {
    apiUsuarios.ver.mockResolvedValue(ANA)
    apiUsuarios.editar.mockReturnValue(new Promise(() => {}))
    montar('/usuarios/7/editar')
    await userEvent.click(await screen.findByRole('button', { name: /guardar cambios/i }))
    expect(screen.getByRole('button', { name: /guardando/i })).toBeDisabled()
    expect(apiUsuarios.editar).toHaveBeenCalledTimes(1)
  })

  it('un 403 al cargar muestra sin permiso', async () => {
    apiUsuarios.ver.mockRejectedValue(new ErrorApi(403, 'no'))
    montar('/usuarios/7/editar')
    expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument()
  })

  it('un 404 al cargar dice que no existe', async () => {
    apiUsuarios.ver.mockRejectedValue(new ErrorApi(404, 'x'))
    montar('/usuarios/99/editar')
    expect(await screen.findByText(/no encontramos a ese usuario/i)).toBeInTheDocument()
  })
})

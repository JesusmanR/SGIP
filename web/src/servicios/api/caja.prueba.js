import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ErrorApi, pedir } from '../api.js'
import {
  abrirTurno, actualizarCliente, administradores, clientePorIdentificacion, crearCliente,
  datosDeLaEmpresa, mediosDePago, productoPorCodigo, productosPorTexto, registrarVenta,
  saldoDeCliente, turnoActual, CLAVE_EN_USO,
} from './caja.js'

vi.mock('../api.js', async (original) => ({ ...(await original()), pedir: vi.fn(async () => ({ ok: true })) }))

beforeEach(() => vi.clearAllMocks())

describe('servicio de la caja', () => {
  it('lee el turno actual y lo abre con la base', async () => {
    await turnoActual()
    expect(pedir).toHaveBeenCalledWith('/caja/turnos/actual')
    await abrirTurno(50000)
    expect(pedir).toHaveBeenCalledWith('/caja/turnos', { method: 'POST', body: { baseEfectivo: 50000 } })
  })

  it('busca por código con el código codificado, y un 404 es «no existe», no un error', async () => {
    await productoPorCodigo('770 1/2')
    expect(pedir).toHaveBeenCalledWith('/caja/productos?codigo=770%201%2F2')
    pedir.mockRejectedValueOnce(new ErrorApi(404, 'No hay ningún producto con ese código.'))
    expect(await productoPorCodigo('x')).toBeNull()
    pedir.mockRejectedValueOnce(new ErrorApi(500, 'Falló'))
    await expect(productoPorCodigo('x')).rejects.toThrow('Falló')
  })

  it('busca por texto', async () => {
    await productosPorTexto('tornillo m6')
    expect(pedir).toHaveBeenCalledWith('/caja/productos?q=tornillo%20m6')
  })

  it('busca al cliente por tipo e identificación, y un 404 es «no existe»', async () => {
    await clientePorIdentificacion('CC', '1020304050')
    expect(pedir).toHaveBeenCalledWith('/caja/clientes?tipo=CC&identificacion=1020304050')
    pedir.mockRejectedValueOnce(new ErrorApi(404, 'El cliente no existe.'))
    expect(await clientePorIdentificacion('CC', '9')).toBeNull()
  })

  it('crea y actualiza clientes', async () => {
    const datos = { nombre: 'Ana', tipoDocumento: 'CC', identificacion: '1', correo: 'a@b.co', autorizacionDatos: true }
    await crearCliente(datos)
    expect(pedir).toHaveBeenCalledWith('/caja/clientes', { method: 'POST', body: datos })
    await actualizarCliente(7, datos)
    expect(pedir).toHaveBeenCalledWith('/caja/clientes/7', { method: 'PUT', body: datos })
  })

  it('lee el saldo a favor del cliente (supuesto: GET /caja/clientes/{id}/saldo)', async () => {
    await saldoDeCliente(7)
    expect(pedir).toHaveBeenCalledWith('/caja/clientes/7/saldo')
  })

  it('lista los medios de pago, los administradores y la empresa', async () => {
    await mediosDePago()
    expect(pedir).toHaveBeenCalledWith('/caja/medios-pago')
    await administradores()
    expect(pedir).toHaveBeenCalledWith('/caja/administradores')
    await datosDeLaEmpresa()
    expect(pedir).toHaveBeenCalledWith('/empresa')
  })

  it('el tipo del 409 de clave en uso es una constante (supuesto: el backend la añadirá al integrar)', () => {
    expect(CLAVE_EN_USO).toBe('urn:sgip:caja:clave-en-uso')
  })

  it('registra la venta con POST y el cuerpo tal cual', async () => {
    const solicitud = { claveIdempotencia: 'u', lineas: [], pagos: [] }
    await registrarVenta(solicitud)
    expect(pedir).toHaveBeenCalledWith('/caja/ventas', { method: 'POST', body: solicitud })
  })
})

import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ErrorApi } from '../../servicios/api.js'
import { CLAVE_EN_USO, registrarVenta } from '../../servicios/api/caja.js'
import { CLAVE_VENTA_PENDIENTE } from '../../servicios/ventaPendiente.js'
import useVentaEnCurso from './useVentaEnCurso.js'

vi.mock('../../servicios/api/caja.js', async (original) => ({ ...(await original()), registrarVenta: vi.fn() }))

const construir = (clave) => ({ claveIdempotencia: clave, lineas: [{ productoId: 1, cantidad: 1 }], pagos: [] })
const VENTA = { id: 1, consecutivo: 'V-1', total: 10, cambio: 0, lineas: [] }

beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
})

async function enviar(hook, autorizacion) {
  let resultado
  await act(async () => { resultado = await hook.result.current.enviar(construir, autorizacion) })
  return resultado
}

describe('la venta en curso', () => {
  it('arranca libre, con una clave', () => {
    const { result } = renderHook(() => useVentaEnCurso())
    expect(result.current.estado).toBe('libre')
    expect(result.current.clave).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('al confirmarse devuelve la venta y renueva la clave', async () => {
    registrarVenta.mockResolvedValue(VENTA)
    const hook = renderHook(() => useVentaEnCurso())
    const antes = hook.result.current.clave
    const r = await enviar(hook)
    expect(r.venta).toBe(VENTA)
    expect(registrarVenta.mock.calls[0][0].claveIdempotencia).toBe(antes)
    expect(hook.result.current.clave).not.toBe(antes)
    expect(hook.result.current.estado).toBe('libre')
  })

  it.each([
    ['un error de red', () => new TypeError('Failed to fetch')],
    ['un 500', () => new ErrorApi(500, 'Falló')],
    ['un 502', () => new ErrorApi(502, 'Puerta de enlace')],
    ['un 504', () => new ErrorApi(504, 'Tiempo agotado')],
  ])('%s deja la venta ambigua y el reintento manda el cuerpo exacto con la misma clave', async (_n, error) => {
    registrarVenta.mockRejectedValueOnce(error()).mockResolvedValueOnce(VENTA)
    const hook = renderHook(() => useVentaEnCurso())
    const clave = hook.result.current.clave
    const r = await enviar(hook)
    expect(r.error).toMatch(/no sabemos si se registró/i)
    expect(hook.result.current.estado).toBe('ambiguo')
    expect(hook.result.current.clave).toBe(clave)

    // Aunque la pantalla armara otro cuerpo, se reenvía el primero.
    let segundo
    await act(async () => { segundo = await hook.result.current.enviar(() => ({ otro: true })) })
    expect(registrarVenta.mock.calls[1][0]).toEqual(registrarVenta.mock.calls[0][0])
    expect(segundo.venta).toBe(VENTA)
    expect(hook.result.current.estado).toBe('libre')
    expect(hook.result.current.clave).not.toBe(clave)
  })

  it('la autorización viaja en el envío pero no se guarda en el cuerpo que se reenvía', async () => {
    registrarVenta.mockRejectedValueOnce(new TypeError('x')).mockResolvedValueOnce(VENTA)
    const hook = renderHook(() => useVentaEnCurso())
    await enviar(hook, { administradorId: 3, pin: '1234' })
    expect(registrarVenta.mock.calls[0][0].autorizacion).toEqual({ administradorId: 3, pin: '1234' })
    await enviar(hook)
    expect(registrarVenta.mock.calls[1][0].autorizacion).toBeUndefined()
    expect(JSON.stringify(hook.result.current)).not.toContain('1234')
  })

  it('un error definitivo (400, 403, 409 de existencia) deja libre la venta y conserva la clave', async () => {
    registrarVenta.mockRejectedValueOnce(new ErrorApi(409, 'No hay existencia suficiente de Tornillo M6.', 'urn:otro'))
    const hook = renderHook(() => useVentaEnCurso())
    const clave = hook.result.current.clave
    const r = await enviar(hook)
    expect(r.error).toBe('No hay existencia suficiente de Tornillo M6.')
    expect(r.error).not.toMatch(/otra vez/i)
    expect(hook.result.current.estado).toBe('libre')
    expect(hook.result.current.clave).toBe(clave)
  })

  it('el 409 de clave en uso se distingue por su tipo y no deja reintentar', async () => {
    registrarVenta.mockRejectedValueOnce(new ErrorApi(409, 'x', CLAVE_EN_USO))
    const hook = renderHook(() => useVentaEnCurso())
    const r = await enviar(hook)
    expect(r.error).toBe('Esta venta ya se registró con otros datos. Revisa las ventas del turno.')
    expect(hook.result.current.estado).toBe('claveEnUso')
    expect(r.error).not.toMatch(/clave|idempotencia/i)
  })

  it('un segundo envío mientras el primero sigue en vuelo se ignora con un valor propio', async () => {
    let responder
    registrarVenta.mockImplementationOnce(() => new Promise((resolver) => { responder = resolver }))
    const hook = renderHook(() => useVentaEnCurso())
    let primero
    act(() => { primero = hook.result.current.enviar(construir) })
    expect(hook.result.current.enviando).toBe(true)
    let segundo
    await act(async () => { segundo = await hook.result.current.enviar(construir) })
    expect(segundo).toEqual({ ignorado: true })
    expect(registrarVenta).toHaveBeenCalledTimes(1)
    await act(async () => { responder(VENTA); await primero })
  })

  it('si armar el cuerpo falla, el envío no queda trabado y el siguiente funciona', async () => {
    registrarVenta.mockResolvedValue(VENTA)
    const hook = renderHook(() => useVentaEnCurso())
    let primero
    await act(async () => { primero = await hook.result.current.enviar(() => { throw new Error('datos rotos') }) })
    expect(primero.error).toMatch(/no se pudo preparar/i)
    expect(registrarVenta).not.toHaveBeenCalled()
    expect(hook.result.current.enviando).toBe(false)
    expect(hook.result.current.estado).toBe('libre')
    const segundo = await enviar(hook)
    expect(segundo.venta).toBe(VENTA)
  })

  it('abandonar renueva la clave y deja libre', async () => {
    registrarVenta.mockRejectedValueOnce(new TypeError('x'))
    const hook = renderHook(() => useVentaEnCurso())
    const clave = hook.result.current.clave
    await enviar(hook)
    act(() => hook.result.current.abandonar())
    expect(hook.result.current.estado).toBe('libre')
    expect(hook.result.current.clave).not.toBe(clave)
  })
})

describe('una venta ambigua sobrevive a recargar la pestaña (M12-R1)', () => {
  const guardado = () => JSON.parse(sessionStorage.getItem(CLAVE_VENTA_PENDIENTE))

  async function dejarAmbigua(autorizacion, motivos = []) {
    registrarVenta.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    const hook = renderHook(() => useVentaEnCurso())
    await act(async () => { await hook.result.current.enviar(construir, autorizacion, motivos) })
    return hook
  }

  it('guarda la clave y el cuerpo exacto al quedar ambigua, y nunca el PIN', async () => {
    const hook = await dejarAmbigua({ administradorId: 3, pin: '4821' }, ['Descuento en la venta'])
    const guardada = guardado()
    expect(guardada.clave).toBe(hook.result.current.clave)
    expect(guardada.cuerpo).toEqual(construir(hook.result.current.clave))
    expect(guardada.motivos).toEqual(['Descuento en la venta'])
    expect(sessionStorage.getItem(CLAVE_VENTA_PENDIENTE)).not.toContain('4821')
    expect(Object.keys(sessionStorage).filter((k) => sessionStorage.getItem(k).includes('4821'))).toEqual([])
  })

  it('al cargar de nuevo arranca ambigua, con la misma clave, y reenvía el cuerpo guardado sin armar otro', async () => {
    const primero = await dejarAmbigua(undefined, ['Venta como encargo (sin existencia)'])
    const clave = primero.result.current.clave
    primero.unmount()

    registrarVenta.mockResolvedValueOnce(VENTA)
    const recargada = renderHook(() => useVentaEnCurso())
    expect(recargada.result.current.estado).toBe('ambiguo')
    expect(recargada.result.current.clave).toBe(clave)
    expect(recargada.result.current.restaurada).toBe(true)
    expect(recargada.result.current.motivos).toEqual(['Venta como encargo (sin existencia)'])

    let r
    await act(async () => { r = await recargada.result.current.enviar(() => { throw new Error('no se arma de nuevo') }) })
    expect(r.venta).toBe(VENTA)
    expect(registrarVenta.mock.calls[1][0]).toEqual(construir(clave))
    expect(recargada.result.current.estado).toBe('libre')
    expect(recargada.result.current.restaurada).toBe(false)
    expect(sessionStorage.getItem(CLAVE_VENTA_PENDIENTE)).toBeNull()
  })

  it('una venta normal, sin ambigüedad, no deja nada guardado', async () => {
    registrarVenta.mockResolvedValue(VENTA)
    const hook = renderHook(() => useVentaEnCurso())
    await enviar(hook)
    expect(sessionStorage.getItem(CLAVE_VENTA_PENDIENTE)).toBeNull()
  })

  it.each([
    ['un error definitivo', () => new ErrorApi(400, 'Datos inválidos')],
    ['una clave en uso', () => new ErrorApi(409, 'x', CLAVE_EN_USO)],
  ])('%s borra lo guardado', async (_n, error) => {
    const hook = await dejarAmbigua()
    expect(guardado()).not.toBeNull()
    registrarVenta.mockRejectedValueOnce(error())
    await enviar(hook)
    expect(sessionStorage.getItem(CLAVE_VENTA_PENDIENTE)).toBeNull()
  })

  it('abandonar borra lo guardado', async () => {
    const hook = await dejarAmbigua()
    act(() => hook.result.current.abandonar())
    expect(sessionStorage.getItem(CLAVE_VENTA_PENDIENTE)).toBeNull()
  })

  it('el PIN rechazado de una venta restaurada no pierde el cuerpo: se puede volver a intentar', async () => {
    const primero = await dejarAmbigua(undefined, ['Descuento en la venta'])
    primero.unmount()
    const recargada = renderHook(() => useVentaEnCurso())
    registrarVenta.mockRejectedValueOnce(new ErrorApi(403, 'El PIN no es correcto.'))
    let r
    await act(async () => { r = await recargada.result.current.enviar(construir, { administradorId: 3, pin: '0000' }) })
    expect(r.error).toBe('El PIN no es correcto.')
    expect(recargada.result.current.estado).toBe('ambiguo')
    expect(guardado()).not.toBeNull()
    registrarVenta.mockResolvedValueOnce(VENTA)
    await act(async () => { r = await recargada.result.current.enviar(construir, { administradorId: 3, pin: '1111' }) })
    expect(r.venta).toBe(VENTA)
  })

  it.each([
    ['basura', 'no es json'],
    ['sin cuerpo', JSON.stringify({ clave: 'a' })],
    ['una clave que no es la del cuerpo', JSON.stringify({ clave: 'a', cuerpo: { claveIdempotencia: 'b' }, motivos: [] })],
  ])('un valor guardado que no vale (%s) se ignora y arranca libre', (_n, valor) => {
    sessionStorage.setItem(CLAVE_VENTA_PENDIENTE, valor)
    const { result } = renderHook(() => useVentaEnCurso())
    expect(result.current.estado).toBe('libre')
    expect(result.current.restaurada).toBe(false)
  })

  it('si el almacenamiento no se puede usar, todo sigue funcionando en memoria', async () => {
    const guardar = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('lleno') })
    const leer = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('bloqueado') })
    try {
      registrarVenta.mockRejectedValueOnce(new TypeError('x')).mockResolvedValueOnce(VENTA)
      const hook = renderHook(() => useVentaEnCurso())
      expect(hook.result.current.estado).toBe('libre')
      await enviar(hook)
      expect(hook.result.current.estado).toBe('ambiguo')
      const r = await enviar(hook)
      expect(r.venta).toBe(VENTA)
    } finally {
      guardar.mockRestore()
      leer.mockRestore()
    }
  })
})

describe('M12-R1, ronda 1: guardar antes de enviar y no entrar en bucle', () => {
  const guardado = () => JSON.parse(sessionStorage.getItem(CLAVE_VENTA_PENDIENTE))

  it('guarda la clave y el cuerpo ANTES de enviar: una recarga con el envío en vuelo se puede reintentar', async () => {
    let responder
    registrarVenta.mockImplementationOnce(() => new Promise((resolver) => { responder = resolver }))
    const hook = renderHook(() => useVentaEnCurso())
    let envio
    act(() => { envio = hook.result.current.enviar(construir, undefined, ['Descuento en la venta']) })
    expect(guardado()).toEqual({
      clave: hook.result.current.clave, cuerpo: construir(hook.result.current.clave), motivos: ['Descuento en la venta'],
    })
    await act(async () => { responder(VENTA); await envio })
    // Una respuesta definitiva (aquí, la venta) lo borra.
    expect(sessionStorage.getItem(CLAVE_VENTA_PENDIENTE)).toBeNull()
  })

  it('un error definitivo borra lo guardado antes de enviar', async () => {
    registrarVenta.mockRejectedValueOnce(new ErrorApi(400, 'Datos inválidos'))
    const hook = renderHook(() => useVentaEnCurso())
    await enviar(hook)
    expect(sessionStorage.getItem(CLAVE_VENTA_PENDIENTE)).toBeNull()
  })

  async function restaurada() {
    sessionStorage.setItem(CLAVE_VENTA_PENDIENTE, JSON.stringify({
      clave: 'k-1', cuerpo: { ...construir('k-1') }, motivos: ['Descuento en la venta'],
    }))
    return renderHook(() => useVentaEnCurso())
  }

  it('un 403 que no es de PIN en una venta restaurada la descarta y avisa, sin dejarla en bucle', async () => {
    const hook = await restaurada()
    registrarVenta.mockRejectedValueOnce(new ErrorApi(403, 'Tu empresa está en modo de consulta.', 'urn:sgip:empresa:solo-consulta'))
    let r
    await act(async () => { r = await hook.result.current.enviar(construir, { administradorId: 3, pin: '4821' }) })
    expect(r.error).toBe('Tu empresa está en modo de consulta.')
    expect(r.descartada).toBe(true)
    expect(hook.result.current.estado).toBe('libre')
    expect(hook.result.current.restaurada).toBe(false)
    expect(sessionStorage.getItem(CLAVE_VENTA_PENDIENTE)).toBeNull()
  })

  it('un 403 de PIN, por su tipo, conserva el cuerpo aunque el texto no diga PIN', async () => {
    const hook = await restaurada()
    registrarVenta.mockRejectedValueOnce(new ErrorApi(403, 'La credencial no es válida.', 'urn:sgip:caja:pin-invalido'))
    let r
    await act(async () => { r = await hook.result.current.enviar(construir, { administradorId: 3, pin: '0000' }) })
    expect(r.descartada).toBeUndefined()
    expect(hook.result.current.estado).toBe('ambiguo')
    expect(guardado()).not.toBeNull()
  })
})

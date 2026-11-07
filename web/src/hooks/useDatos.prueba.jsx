import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ErrorApi } from '../servicios/api.js'
import { useDatos } from './useDatos.js'

function diferida() {
  let resolver, rechazar
  const promesa = new Promise((res, rej) => { resolver = res; rechazar = rej })
  return { promesa, resolver, rechazar }
}

describe('useDatos', () => {
  it('empieza cargando y entrega los datos', async () => {
    const cargar = vi.fn().mockResolvedValue([1, 2])
    const { result } = renderHook(() => useDatos(cargar, []))
    expect(result.current.cargando).toBe(true)
    await waitFor(() => expect(result.current.cargando).toBe(false))
    expect(result.current.datos).toEqual([1, 2])
    expect(result.current.error).toBeNull()
  })

  it('expone el error', async () => {
    const fallo = new ErrorApi(500, 'se rompió')
    const { result } = renderHook(() => useDatos(() => Promise.reject(fallo), []))
    await waitFor(() => expect(result.current.cargando).toBe(false))
    expect(result.current.error).toBe(fallo)
    expect(result.current.datos).toBeNull()
    expect(result.current.sinPermiso).toBe(false)
  })

  it('expone el 403 como sinPermiso', async () => {
    const { result } = renderHook(() => useDatos(() => Promise.reject(new ErrorApi(403, 'no')), []))
    await waitFor(() => expect(result.current.cargando).toBe(false))
    expect(result.current.sinPermiso).toBe(true)
    expect(result.current.error.estado).toBe(403)
  })

  it('recargar vuelve a pedir', async () => {
    const cargar = vi.fn().mockResolvedValueOnce('a').mockResolvedValueOnce('b')
    const { result } = renderHook(() => useDatos(cargar, []))
    await waitFor(() => expect(result.current.datos).toBe('a'))
    act(() => result.current.recargar())
    await waitFor(() => expect(result.current.datos).toBe('b'))
    expect(cargar).toHaveBeenCalledTimes(2)
  })

  it('no se deja pisar por una respuesta vieja', async () => {
    const primera = diferida()
    const segunda = diferida()
    const { result, rerender } = renderHook(
      ({ id }) => useDatos(() => (id === 1 ? primera.promesa : segunda.promesa), [id]),
      { initialProps: { id: 1 } },
    )
    rerender({ id: 2 })
    await act(async () => { segunda.resolver('nueva') })
    await waitFor(() => expect(result.current.datos).toBe('nueva'))
    await act(async () => { primera.resolver('vieja') })
    expect(result.current.datos).toBe('nueva')
    expect(result.current.cargando).toBe(false)
  })

  it('al cambiar las dependencias los datos viejos desaparecen mientras llega lo nuevo', async () => {
    const segunda = diferida()
    const { result, rerender } = renderHook(
      ({ id }) => useDatos(() => (id === 5 ? Promise.resolve('detalle 5') : segunda.promesa), [id]),
      { initialProps: { id: 5 } },
    )
    await waitFor(() => expect(result.current.datos).toBe('detalle 5'))
    rerender({ id: 6 })
    expect(result.current.cargando).toBe(true)
    expect(result.current.datos).toBeNull()
    await act(async () => { segunda.resolver('detalle 6') })
    expect(result.current.datos).toBe('detalle 6')
  })

  it('ni un solo render enseña los datos de la dependencia anterior', async () => {
    const vistos = []
    const { result, rerender } = renderHook(({ id }) => {
      const r = useDatos(() => Promise.resolve(`dato ${id}`), [id])
      vistos.push({ id, datos: r.datos, cargando: r.cargando })
      return r
    }, { initialProps: { id: 1 } })
    await waitFor(() => expect(result.current.datos).toBe('dato 1'))
    vistos.length = 0
    rerender({ id: 2 })
    await waitFor(() => expect(result.current.datos).toBe('dato 2'))
    expect(vistos.filter((v) => v.id === 2 && v.datos === 'dato 1')).toEqual([])
    expect(vistos[0]).toMatchObject({ id: 2, datos: null, cargando: true })
  })

  it('recargar sí conserva los datos mientras llegan los nuevos', async () => {
    const segunda = diferida()
    const cargar = vi.fn().mockResolvedValueOnce('a').mockReturnValueOnce(segunda.promesa)
    const { result } = renderHook(() => useDatos(cargar, []))
    await waitFor(() => expect(result.current.datos).toBe('a'))
    act(() => result.current.recargar())
    expect(result.current.cargando).toBe(true)
    expect(result.current.datos).toBe('a')
    await act(async () => { segunda.resolver('b') })
  })

  it('un error viejo tampoco pisa los datos nuevos', async () => {
    const primera = diferida()
    const segunda = diferida()
    const { result, rerender } = renderHook(
      ({ id }) => useDatos(() => (id === 1 ? primera.promesa : segunda.promesa), [id]),
      { initialProps: { id: 1 } },
    )
    rerender({ id: 2 })
    await act(async () => { segunda.resolver('nueva') })
    await act(async () => { primera.rechazar(new ErrorApi(500, 'tarde')) })
    expect(result.current.datos).toBe('nueva')
    expect(result.current.error).toBeNull()
  })
})

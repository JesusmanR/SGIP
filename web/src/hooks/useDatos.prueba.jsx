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

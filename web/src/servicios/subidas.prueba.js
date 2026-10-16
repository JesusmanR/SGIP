import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./api.js', () => ({ api: { subirImagen: vi.fn() } }))
vi.mock('./imagenes.js', () => ({ prepararImagen: vi.fn(async (a) => a) }))

const { api } = await import('./api.js')
const { MAXIMO_POR_PRODUCTO, subirImagenes } = await import('./subidas.js')

function archivo(nombre) {
  return new File([new Uint8Array(8)], nombre, { type: 'image/jpeg' })
}

beforeEach(() => { vi.clearAllMocks() })

describe('subirImagenes', () => {
  it('sube de una en una y nunca dos a la vez', async () => {
    // En paralelo se pisarían: dos subidas simultáneas sobre un producto
    // sin fotos se declaran las dos principal y piden el mismo orden, y
    // los índices únicos rechazan a la segunda.
    let simultaneas = 0
    let maximoSimultaneas = 0
    api.subirImagen.mockImplementation(async () => {
      simultaneas += 1
      maximoSimultaneas = Math.max(maximoSimultaneas, simultaneas)
      await new Promise((r) => setTimeout(r, 1))
      simultaneas -= 1
      return { id: 1, principal: false }
    })

    await subirImagenes(7, [archivo('a.jpg'), archivo('b.jpg'), archivo('c.jpg')])

    expect(maximoSimultaneas).toBe(1)
    expect(api.subirImagen).toHaveBeenCalledTimes(3)
  })

  it('devuelve las creadas en el orden en que se eligieron', async () => {
    let n = 0
    api.subirImagen.mockImplementation(async () => ({ id: ++n, principal: n === 1 }))

    const { creadas } = await subirImagenes(7, [archivo('a.jpg'), archivo('b.jpg')])

    expect(creadas.map((c) => c.id)).toEqual([1, 2])
  })

  it('un archivo que falla no detiene a los demás, y se dice cuál fue', async () => {
    // Subir cinco y perderlas todas porque la tercera era un HEIC sería
    // peor que decir cuál falló.
    api.subirImagen
      .mockResolvedValueOnce({ id: 1, principal: true })
      .mockRejectedValueOnce(new Error('La imagen está dañada o incompleta.'))
      .mockResolvedValueOnce({ id: 3, principal: false })

    const { creadas, problemas } = await subirImagenes(
      7, [archivo('a.jpg'), archivo('mala.jpg'), archivo('c.jpg')])

    expect(creadas).toHaveLength(2)
    expect(problemas).toHaveLength(1)
    expect(problemas[0]).toContain('mala.jpg')
    expect(problemas[0]).toContain('dañada')
  })

  it('cuando se sabe cuántas hay, avisa de las que no caben sin enviarlas', async () => {
    api.subirImagen.mockResolvedValue({ id: 9, principal: false })

    const { creadas, problemas } = await subirImagenes(
      7, [archivo('a.jpg'), archivo('b.jpg'), archivo('c.jpg')], MAXIMO_POR_PRODUCTO - 1)

    expect(creadas).toHaveLength(1)
    expect(api.subirImagen).toHaveBeenCalledTimes(1)
    expect(problemas).toHaveLength(2)
    expect(problemas[0]).toContain(String(MAXIMO_POR_PRODUCTO))
  })

  it('sin saber cuántas hay, deja que corte el servidor', async () => {
    // El recuadro de la tabla no conoce el conteo. El tope lo hace
    // cumplir el esquema de todos modos; el cliente solo ahorra viajes
    // cuando puede, y nunca se inventa un límite que no le consta.
    api.subirImagen
      .mockResolvedValueOnce({ id: 1, principal: true })
      .mockRejectedValueOnce(new Error('Un producto admite 5 imágenes y ya tiene 5.'))

    const { creadas, problemas } = await subirImagenes(7, [archivo('a.jpg'), archivo('b.jpg')])

    expect(creadas).toHaveLength(1)
    expect(problemas[0]).toContain('ya tiene 5')
  })

  it('una selección vacía no llama al servidor', async () => {
    const { creadas, problemas } = await subirImagenes(7, [])

    expect(creadas).toEqual([])
    expect(problemas).toEqual([])
    expect(api.subirImagen).not.toHaveBeenCalled()
  })
})

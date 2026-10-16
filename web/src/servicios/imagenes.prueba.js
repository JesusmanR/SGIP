import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ErrorImagen, TOPE_BYTES, prepararImagen } from './imagenes.js'

/**
 * Lo que se prueba aquí es la decisión, no el codificador: jsdom no trae
 * ni createImageBitmap ni un lienzo de verdad, así que los dos están
 * simulados. Que el navegador sepa comprimir un JPEG se da por supuesto;
 * lo que hay que comprobar es qué formato se elige, cuánto se reduce y
 * qué se le dice al usuario cuando no hay nada que hacer.
 */

/** Tamaño que devolverá el lienzo según el formato pedido. */
let pesos
/** Medidas de la imagen simulada y lo último que se le pidió al lienzo. */
let medidas
let dibujado

function archivo(nombre, tipo, bytes) {
  return new File([new Uint8Array(bytes)], nombre, { type: tipo })
}

beforeEach(() => {
  pesos = { 'image/png': 300 * 1024, 'image/jpeg': 200 * 1024 }
  medidas = { width: 4000, height: 3000 }
  dibujado = null

  globalThis.createImageBitmap = vi.fn(async (entrada) => {
    if (entrada.name.includes('roto')) throw new Error('formato desconocido')
    return { ...medidas, close: () => {} }
  })

  HTMLCanvasElement.prototype.getContext = vi.fn(() => ({
    drawImage: (_mapa, _x, _y, ancho, alto) => { dibujado = { ancho, alto } },
  }))
  HTMLCanvasElement.prototype.toBlob = function toBlob(devolver, tipo) {
    devolver(new Blob([new Uint8Array(pesos[tipo])], { type: tipo }))
  }
})

afterEach(() => { vi.restoreAllMocks() })

describe('prepararImagen', () => {
  it('deja intacto lo que el servidor ya puede leer y no pesa de más', async () => {
    const original = archivo('foto.jpg', 'image/jpeg', 50 * 1024)

    expect(await prepararImagen(original)).toBe(original)
    expect(globalThis.createImageBitmap).not.toHaveBeenCalled()
  })

  it('convierte un WebP porque el servidor no sabe decodificarlo', async () => {
    // Era el defecto: el servidor reconocía el WebP por su firma y luego
    // ImageIO devolvía null, de modo que el usuario leía «la imagen está
    // dañada o incompleta» por un archivo perfecto.
    const salida = await prepararImagen(archivo('pieza.webp', 'image/webp', 90 * 1024))

    expect(salida.type).toBe('image/png')
    expect(salida.name).toBe('pieza.png')
  })

  it('convierte el WebP a PNG y no a JPEG, por la transparencia', async () => {
    // Un logo convertido a JPEG sale con un rectángulo blanco alrededor.
    const salida = await prepararImagen(archivo('logo.webp', 'image/webp', 10 * 1024))

    expect(salida.type).toBe('image/png')
  })

  it('si el PNG se pasa del tope, cede la transparencia antes que fallar', async () => {
    pesos['image/png'] = TOPE_BYTES + 1024
    const salida = await prepararImagen(archivo('paisaje.webp', 'image/webp', 2 * 1024 * 1024))

    expect(salida.type).toBe('image/jpeg')
    expect(salida.size).toBeLessThanOrEqual(TOPE_BYTES)
  })

  it('reduce una foto de teléfono en vez de rechazarla por peso', async () => {
    // El segundo defecto: con el tope anterior de 2 MB, cualquier foto
    // recién tomada se rechazaba antes de que nada pudiera encogerla.
    const salida = await prepararImagen(archivo('camara.jpg', 'image/jpeg', 12 * 1024 * 1024))

    expect(salida.size).toBeLessThanOrEqual(TOPE_BYTES)
    expect(salida.type).toBe('image/jpeg')
  })

  it('reduce al lado máximo conservando la proporción', async () => {
    medidas = { width: 4000, height: 3000 }
    await prepararImagen(archivo('grande.webp', 'image/webp', 1024))

    expect(dibujado).toEqual({ ancho: 1600, alto: 1200 })
  })

  it('no amplía una imagen pequeña', async () => {
    // Ampliarla solo la volvería borrosa, y el servidor tampoco amplía.
    medidas = { width: 120, height: 60 }
    await prepararImagen(archivo('chica.webp', 'image/webp', 1024))

    expect(dibujado).toEqual({ ancho: 120, alto: 60 })
  })

  it('cuando ni el navegador puede leerlo, lo dice nombrando el archivo', async () => {
    // Aquí sí se puede culpar al archivo: un HEIC de iPhone o un TIFF.
    await expect(prepararImagen(archivo('roto.heic', 'image/heic', 2048)))
      .rejects.toThrow(ErrorImagen)
    await expect(prepararImagen(archivo('roto.heic', 'image/heic', 2048)))
      .rejects.toThrow(/roto\.heic/)
  })

  it('si sigue pesando de más tras reducirla, dice cuánto pesa y cuál es el tope', async () => {
    pesos = { 'image/png': TOPE_BYTES + 1, 'image/jpeg': TOPE_BYTES + 1 }

    await expect(prepararImagen(archivo('enorme.webp', 'image/webp', 1024)))
      .rejects.toThrow(/10 MB/)
  })
})

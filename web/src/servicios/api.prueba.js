import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ErrorApi, imagenAutenticada } from './api.js'
import { guardarSesion, haySesion } from './sesion.js'

describe('cliente del API', () => {
  beforeEach(() => {
    sessionStorage.clear()
    global.fetch = vi.fn()
  })
  afterEach(() => { vi.restoreAllMocks() })

  function responder(estado, cuerpo) {
    global.fetch.mockResolvedValue({
      ok: estado >= 200 && estado < 300,
      status: estado,
      json: async () => cuerpo,
    })
  }

  it('no manda cabecera de autorización si no hay sesión', async () => {
    responder(200, [])
    await api.catalogo()
    const [, opciones] = global.fetch.mock.calls[0]
    expect(opciones.headers.Authorization).toBeUndefined()
  })

  it('manda el token cuando hay sesión', async () => {
    guardarSesion({ token: 'abc123', nombre: 'Ana' })
    responder(200, [])
    await api.catalogo()
    const [, opciones] = global.fetch.mock.calls[0]
    expect(opciones.headers.Authorization).toBe('Bearer abc123')
  })

  it('un 401 cierra la sesión local', async () => {
    guardarSesion({ token: 'caducado', nombre: 'Ana' })
    responder(401, {})
    await expect(api.catalogo()).rejects.toBeInstanceOf(ErrorApi)
    expect(haySesion()).toBe(false)
  })

  it('un 401 del login muestra el detalle del backend y no habla de sesión', async () => {
    responder(401, { detail: 'El correo o la contraseña no son correctos.' })
    await expect(api.entrar('a@b.co', 'x')).rejects.toThrow('El correo o la contraseña no son correctos.')
  })

  it('el ingreso no manda el token de una sesión que la pestaña aún guarde', async () => {
    // Con el cambio de contraseña pendiente, el servidor respondería 403 a
    // un ingreso que llegue con ese token: la ruta es pública y debe serlo
    // también para quien conserva una sesión restringida.
    guardarSesion({ token: 'pendiente', nombre: 'Ana', debeCambiarClave: true })
    responder(200, { token: 'nuevo' })

    await api.entrar('a@b.co', 'x')

    const [, opciones] = global.fetch.mock.calls[0]
    expect(opciones.headers.Authorization).toBeUndefined()
  })

  it('un 401 del login no borra una sesión que ya exista', async () => {
    // Un intento fallido de entrar no debe echar a quien ya tenía una
    // sesión abierta en la pestaña.
    guardarSesion({ token: 'vigente', nombre: 'Ana' })
    responder(401, { detail: 'x' })
    await expect(api.entrar('a@b.co', 'x')).rejects.toBeInstanceOf(ErrorApi)
    expect(haySesion()).toBe(true)
  })

  it('cambiar la clave manda las dos contraseñas y acepta el 204', async () => {
    guardarSesion({ token: 'abc', nombre: 'Ana' })
    global.fetch.mockResolvedValue({ ok: true, status: 204, json: async () => { throw new Error('sin cuerpo') } })

    await expect(api.cambiarClave('actual-1', 'nueva-1234')).resolves.toBeNull()

    const [ruta, opciones] = global.fetch.mock.calls[0]
    expect(ruta).toBe('/api/v1/auth/clave')
    expect(opciones.method).toBe('POST')
    expect(JSON.parse(opciones.body)).toEqual({ claveActual: 'actual-1', claveNueva: 'nueva-1234' })
  })

  it('un 400 al cambiar la clave no se toma por sesión expirada', async () => {
    // Por eso el backend responde 400 y no 401 cuando la actual está mal:
    // un 401 cerraría la sesión del usuario por un error de tecleo.
    guardarSesion({ token: 'vigente', nombre: 'Ana' })
    responder(400, { detail: 'La contraseña actual no es correcta.' })

    await expect(api.cambiarClave('mala', 'nueva-1234'))
      .rejects.toThrow('La contraseña actual no es correcta.')
    expect(haySesion()).toBe(true)
  })

  it('el error lleva el tipo del problema para distinguir un 409 de otro', async () => {
    responder(409, { type: 'urn:sgip:producto-cambio', detail: 'Cambió.' })
    const e = await api.editarProducto(1, {}).catch((x) => x)
    expect(e).toBeInstanceOf(ErrorApi)
    expect(e.estado).toBe(409)
    expect(e.tipo).toBe('urn:sgip:producto-cambio')
  })

  it('usa el detalle del ProblemDetail como mensaje', async () => {
    responder(409, { detail: 'Ya existe una categoría llamada «Bebidas».' })
    await expect(api.categorias()).rejects.toThrow('Ya existe una categoría llamada «Bebidas».')
  })

  it('un error sin cuerpo JSON no rompe la aplicación', async () => {
    global.fetch.mockResolvedValue({
      ok: false, status: 500, json: async () => { throw new Error('sin cuerpo') },
    })
    await expect(api.categorias()).rejects.toThrow('No se pudo completar la operación.')
  })

  it('el texto de búsqueda se codifica antes de ponerlo en la ruta', async () => {
    responder(200, [])
    await api.catalogo({ busqueda: 'arroz & panela' })
    const [ruta] = global.fetch.mock.calls[0]
    expect(ruta).toBe('/api/v1/productos?busqueda=arroz%20%26%20panela')
  })

  it('los filtros vacíos no viajan en la consulta', async () => {
    responder(200, [])
    // Mandar «categoria=» no es lo mismo que no mandarla: el backend
    // recibiría una cadena en vez de nulo y el filtro dejaría de
    // anularse a sí mismo.
    await api.catalogo({ busqueda: '', categoria: '', proveedor: null, estado: undefined })
    const [ruta] = global.fetch.mock.calls[0]
    expect(ruta).toBe('/api/v1/productos')
  })

  it('los filtros que sí tienen valor se combinan', async () => {
    responder(200, [])
    await api.catalogo({ busqueda: 'arroz', categoria: 4, proveedor: 9, estado: 'AGOTADO' })
    const [ruta] = global.fetch.mock.calls[0]
    expect(ruta).toBe('/api/v1/productos?busqueda=arroz&categoria=4&proveedor=9&estado=AGOTADO')
  })

  it('sin argumentos pide el catálogo entero', async () => {
    responder(200, [])
    await api.catalogo()
    const [ruta] = global.fetch.mock.calls[0]
    expect(ruta).toBe('/api/v1/productos')
  })

  it('editar un producto va por PUT con el cuerpo en JSON', async () => {
    responder(200, {})
    await api.editarProducto(11, { nombre: 'Arroz', costo: 3400 })
    const [ruta, opciones] = global.fetch.mock.calls[0]
    expect(ruta).toBe('/api/v1/productos/11')
    expect(opciones.method).toBe('PUT')
    expect(opciones.headers['Content-Type']).toBe('application/json')
    expect(JSON.parse(opciones.body).nombre).toBe('Arroz')
  })

  it('desactivar una categoría es un POST, no un DELETE', async () => {
    responder(200, {})
    // Nada se borra: los productos que la usaron conservan su historial.
    await api.desactivarCategoria(8)
    const [ruta, opciones] = global.fetch.mock.calls[0]
    expect(ruta).toBe('/api/v1/categorias/8/desactivar')
    expect(opciones.method).toBe('POST')
  })

  it('renombrar una categoría va por PUT', async () => {
    responder(200, {})
    await api.editarCategoria(8, { nombre: 'Granos', descripcion: null })
    const [ruta, opciones] = global.fetch.mock.calls[0]
    expect(ruta).toBe('/api/v1/categorias/8')
    expect(opciones.method).toBe('PUT')
  })

  it('la subida no fija Content-Type', async () => {
    // Con FormData lo pone el navegador, y tiene que incluir el boundary
    // que separa las partes. Escribirlo a mano rompe la peticion y el
    // servidor responde que no llego ningun archivo.
    guardarSesion({ token: 'abc', nombre: 'Ana' })
    responder(200, { id: 1 })
    await api.subirLogo(new File(['x'], 'logo.png', { type: 'image/png' }))

    const [, opciones] = global.fetch.mock.calls[0]
    expect(opciones.headers['Content-Type']).toBeUndefined()
    expect(opciones.headers.Authorization).toBe('Bearer abc')
    expect(opciones.body).toBeInstanceOf(FormData)
  })

  it('la subida manda el archivo bajo el nombre que espera el backend', async () => {
    responder(200, {})
    const archivo = new File(['x'], 'foto.jpg', { type: 'image/jpeg' })
    await api.subirImagen(11, archivo)

    const [ruta, opciones] = global.fetch.mock.calls[0]
    expect(ruta).toBe('/api/v1/productos/11/imagenes')
    expect(opciones.body.get('archivo')).toBe(archivo)
  })

  it('un error de subida muestra el detalle del backend', async () => {
    responder(400, { detail: 'Solo se admiten imagenes JPEG, PNG o WebP.' })
    await expect(api.subirLogo(new File(['x'], 'a.txt'))).rejects
      .toThrow('Solo se admiten imagenes JPEG, PNG o WebP.')
  })

  it('un 401 en la subida tambien cierra la sesion', async () => {
    guardarSesion({ token: 'caducado', nombre: 'Ana' })
    responder(401, {})
    await expect(api.subirLogo(new File(['x'], 'a.png'))).rejects.toBeInstanceOf(ErrorApi)
    expect(haySesion()).toBe(false)
  })

  it('un 204 no intenta leer cuerpo', async () => {
    guardarSesion({ token: 'abc', nombre: 'Ana' })
    global.fetch.mockResolvedValue({ ok: true, status: 204 })
    await expect(api.salir()).resolves.toBeNull()
  })
})

describe('imágenes protegidas', () => {
  beforeEach(() => {
    sessionStorage.clear()
    global.fetch = vi.fn()
    global.URL.createObjectURL = vi.fn(() => 'blob:falsa')
  })
  afterEach(() => { vi.restoreAllMocks() })

  it('pide la imagen con la cabecera de autorización', async () => {
    // Una etiqueta <img> no la manda, y por eso el backend la rechazaba.
    guardarSesion({ token: 'abc123', nombre: 'Ana' })
    global.fetch.mockResolvedValue({ ok: true, status: 200, blob: async () => new Blob(['x']) })

    const url = await imagenAutenticada('/api/v1/productos/6/imagen')

    const [ruta, opciones] = global.fetch.mock.calls[0]
    expect(ruta).toBe('/api/v1/productos/6/imagen')
    expect(opciones.headers.Authorization).toBe('Bearer abc123')
    expect(url).toBe('blob:falsa')
  })

  it('un 401 al cargar una imagen NO cierra la sesion', async () => {
    // Echar al usuario por una miniatura rota es peor que la miniatura
    // rota. La sesion la gobiernan las llamadas de datos, no las imagenes.
    guardarSesion({ token: 'abc123', nombre: 'Ana' })
    global.fetch.mockResolvedValue({ ok: false, status: 401 })

    await expect(imagenAutenticada('/api/v1/empresa/logo')).rejects.toBeInstanceOf(ErrorApi)
    expect(haySesion()).toBe(true)
  })

  it('una imagen que el servidor niega lanza error en vez de devolver una URL rota', async () => {
    global.fetch.mockResolvedValue({ ok: false, status: 401 })

    await expect(imagenAutenticada('/api/v1/productos/6/imagen')).rejects.toBeInstanceOf(ErrorApi)
    expect(global.URL.createObjectURL).not.toHaveBeenCalled()
  })
})

describe('pedir exportado', () => {
  it('se exporta para que cada sección arme su módulo sin tocar api.js', async () => {
    const { pedir } = await import('./api.js')
    expect(typeof pedir).toBe('function')
  })
})

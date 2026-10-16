import { leerSesion, borrarSesion } from './sesion.js'

/**
 * Único punto por el que el front habla con el backend.
 *
 * Centralizarlo tiene dos razones concretas: la cabecera de autorización
 * se arma en un solo sitio, y un 401 se traduce en un cierre de sesión
 * en lugar de en un error raro en mitad de una pantalla.
 */
export class ErrorApi extends Error {
  constructor(estado, detalle) {
    super(detalle)
    this.estado = estado
  }
}

/**
 * Subida de archivos. No pone Content-Type a propósito: con FormData lo
 * fija el navegador, y tiene que incluir el «boundary» que separa las
 * partes. Escribirlo a mano rompe la petición de una forma que el
 * servidor reporta como «no llegó ningún archivo».
 */
async function subir(ruta, archivo) {
  const sesion = leerSesion()
  const cuerpo = new FormData()
  cuerpo.append('archivo', archivo)

  const cabeceras = {}
  if (sesion?.token) cabeceras.Authorization = `Bearer ${sesion.token}`

  const respuesta = await fetch(`/api/v1${ruta}`, { method: 'POST', headers: cabeceras, body: cuerpo })
  if (respuesta.status === 401) {
    borrarSesion()
    throw new ErrorApi(401, 'La sesión expiró. Vuelve a entrar.')
  }
  if (!respuesta.ok) {
    let detalle = 'No se pudo subir el archivo.'
    try {
      const json = await respuesta.json()
      if (json?.detail) detalle = json.detail
    } catch {
      // Sin cuerpo JSON queda el mensaje genérico.
    }
    throw new ErrorApi(respuesta.status, detalle)
  }
  return respuesta.json()
}

/**
 * Trae una imagen protegida y devuelve una URL que <img> puede usar.
 *
 * Hace falta porque una etiqueta <img src> no manda cabeceras: el
 * navegador la pide por su cuenta, sin Authorization, y el backend la
 * rechaza por anónima. Con fetch sí se puede autenticar, y el resultado
 * se envuelve en una URL de objeto.
 *
 * Quien la pide es responsable de liberarla con URL.revokeObjectURL
 * cuando deje de usarla: mientras no se haga, el navegador conserva esos
 * bytes en memoria.
 */
export async function imagenAutenticada(ruta) {
  const sesion = leerSesion()
  const cabeceras = {}
  if (sesion?.token) cabeceras.Authorization = `Bearer ${sesion.token}`

  const respuesta = await fetch(ruta, { headers: cabeceras })
  if (!respuesta.ok) {
    // A diferencia del resto, un 401 aquí NO cierra la sesión. Una imagen
    // que no carga no demuestra que la sesión caducó, y echar al usuario
    // por una miniatura rota es peor que mostrar la miniatura rota. Si la
    // sesión de verdad expiró, la próxima llamada de datos lo detecta.
    throw new ErrorApi(respuesta.status, 'No se pudo cargar la imagen.')
  }
  return URL.createObjectURL(await respuesta.blob())
}

async function pedir(ruta, opciones = {}) {
  const sesion = leerSesion()
  const cabeceras = { ...opciones.headers }
  if (opciones.body !== undefined) cabeceras['Content-Type'] = 'application/json'
  if (sesion?.token) cabeceras.Authorization = `Bearer ${sesion.token}`

  const respuesta = await fetch(`/api/v1${ruta}`, {
    ...opciones,
    headers: cabeceras,
    body: opciones.body === undefined ? undefined : JSON.stringify(opciones.body),
  })

  if (respuesta.status === 401) {
    // El token caducó o alguien cerró la sesión desde otro dispositivo.
    borrarSesion()
    throw new ErrorApi(401, 'La sesión expiró. Vuelve a entrar.')
  }

  if (!respuesta.ok) {
    // El backend responde con ProblemDetail, que trae «detail».
    let detalle = 'No se pudo completar la operación.'
    try {
      const cuerpo = await respuesta.json()
      if (cuerpo?.detail) detalle = cuerpo.detail
    } catch {
      // Una respuesta sin cuerpo JSON deja el mensaje genérico.
    }
    throw new ErrorApi(respuesta.status, detalle)
  }

  if (respuesta.status === 204) return null
  return respuesta.json()
}

export const api = {
  entrar: (correo, clave) => pedir('/auth/login', { method: 'POST', body: { correo, clave } }),
  salir: () => pedir('/auth/logout', { method: 'POST' }),
  catalogo: (busqueda) =>
    pedir(`/productos${busqueda ? `?busqueda=${encodeURIComponent(busqueda)}` : ''}`),
  categorias: () => pedir('/categorias'),
  proveedores: () => pedir('/proveedores'),
  empresa: () => pedir('/empresa'),
  subirLogo: (archivo) => subir('/empresa/logo', archivo),
  subirImagen: (productoId, archivo) => subir(`/productos/${productoId}/imagenes`, archivo),
  imagenesDe: (productoId) => pedir(`/productos/${productoId}/imagenes`),
  borrarImagen: (imagenId) => pedir(`/imagenes/${imagenId}`, { method: 'DELETE' }),
  hacerPrincipal: (imagenId) => pedir(`/imagenes/${imagenId}/principal`, { method: 'POST' }),
}

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
}

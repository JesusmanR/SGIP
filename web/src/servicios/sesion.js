/**
 * Dónde vive el token de la sesión.
 *
 * Va en sessionStorage y no en localStorage: así muere al cerrar la
 * pestaña, que es lo que un cajero espera cuando termina su turno en un
 * equipo compartido.
 *
 * Conviene nombrar el límite en lugar de taparlo: cualquier guion que
 * logre ejecutarse en esta página puede leer el token. La defensa de
 * verdad sería una cookie httpOnly, que el navegador envía sola y
 * JavaScript no puede leer. Eso exige que el backend la emita y que el
 * cierre de sesión la borre, de modo que es un cambio de las dos partes
 * y queda anotado como pendiente, no resuelto a medias aquí.
 */
const CLAVE = 'sgip.sesion'

export function guardarSesion(sesion) {
  sessionStorage.setItem(CLAVE, JSON.stringify(sesion))
}

export function leerSesion() {
  const crudo = sessionStorage.getItem(CLAVE)
  if (!crudo) return null
  try {
    return JSON.parse(crudo)
  } catch {
    // Un valor corrupto no debe dejar la aplicación inservible.
    sessionStorage.removeItem(CLAVE)
    return null
  }
}

export function borrarSesion() {
  sessionStorage.removeItem(CLAVE)
}

export function haySesion() {
  return leerSesion() !== null
}

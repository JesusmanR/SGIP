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

/**
 * A dónde lleva entrar, según el rol.
 *
 * La especificación de P-02 dice que el panel «es la primera pantalla
 * tras el acceso del administrador», de modo que esto no es una
 * preferencia de navegación: es parte del requisito. Para los demás
 * roles sigue siendo el catálogo, que es donde un vendedor trabaja.
 *
 * Está aquí y no en cada pantalla porque hay tres sitios que deciden lo
 * mismo —el acceso, la ruta comodín y la barra— y con la regla
 * repetida tres veces basta con olvidar uno para que entrar y recargar
 * lleven a pantallas distintas.
 */
export function inicioSegunRol(sesion = leerSesion()) {
  return sesion?.rol === 'ADMINISTRADOR' ? '/panel' : '/catalogo'
}

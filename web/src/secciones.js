/**
 * Tabla única de secciones: de aquí salen el menú y las guardas de ruta.
 * No hay otra lista de permisos en el front.
 *
 * Es comodidad, no seguridad: el backend responde 403 a quien no
 * corresponde. Los roles salen de los controladores del backend:
 * - /catalogo y /empresa (lectura) no restringen rol, pero leen de la
 *   empresa de la sesión, y SUPER_ADMIN no pertenece a ninguna; por eso
 *   solo los ven los roles de empresa.
 * - /plataforma/* es de SUPER_ADMIN.
 * - /caja la usan quienes venden: administrador y vendedor, nunca CLIENTE. Sus subpantallas
 *   mandan por separado: domicilios y devoluciones las usan quienes venden; turnos, encargos
 *   y medios de pago son solo del administrador. El cierre del turno y los cierres propios no
 *   tienen entrada: cuelgan de /caja.
 * - Todo lo demás es de ADMINISTRADOR.
 */
const ADMIN = ['ADMINISTRADOR']
const VENDEN = ['ADMINISTRADOR', 'VENDEDOR']
const DE_EMPRESA = ['ADMINISTRADOR', 'VENDEDOR', 'CLIENTE']
const PLATAFORMA = ['SUPER_ADMIN']

export const secciones = [
  { ruta: '/caja', etiqueta: 'Caja', roles: VENDEN, grupo: 'Operación' },
  { ruta: '/caja/domicilios', etiqueta: 'Domicilios', roles: VENDEN, grupo: 'Operación' },
  { ruta: '/caja/devoluciones', etiqueta: 'Devoluciones', roles: VENDEN, grupo: 'Operación' },
  { ruta: '/caja/encargos', etiqueta: 'Encargos', roles: ADMIN, grupo: 'Operación' },
  { ruta: '/caja/turnos', etiqueta: 'Turnos de caja', roles: ADMIN, grupo: 'Operación' },
  { ruta: '/panel', etiqueta: 'Panel', roles: ADMIN, grupo: 'Operación' },
  { ruta: '/catalogo', etiqueta: 'Catálogo', roles: DE_EMPRESA, grupo: 'Operación' },
  { ruta: '/importar', etiqueta: 'Importar precios', roles: ADMIN, grupo: 'Operación' },
  { ruta: '/proveedores', etiqueta: 'Proveedores', roles: ADMIN, grupo: 'Compras' },
  { ruta: '/compras', etiqueta: 'Órdenes de compra', roles: ADMIN, grupo: 'Compras' },
  { ruta: '/inventario', etiqueta: 'Inventario', roles: ADMIN, grupo: 'Inventario' },
  { ruta: '/precios', etiqueta: 'Reglas de ganancia', roles: ADMIN, grupo: 'Configuración' },
  { ruta: '/impuestos', etiqueta: 'Impuestos', roles: ADMIN, grupo: 'Configuración' },
  { ruta: '/caja/medios-pago', etiqueta: 'Medios de pago', roles: ADMIN, grupo: 'Configuración' },
  { ruta: '/usuarios', etiqueta: 'Usuarios', roles: ADMIN, grupo: 'Configuración' },
  { ruta: '/empresa', etiqueta: 'Mi empresa', roles: DE_EMPRESA, grupo: 'Configuración' },
  { ruta: '/plataforma/empresas', etiqueta: 'Empresas', roles: PLATAFORMA, grupo: 'Plataforma' },
  { ruta: '/plataforma/tributario', etiqueta: 'Datos tributarios', roles: PLATAFORMA, grupo: 'Plataforma' },
]

/**
 * La sección a la que pertenece una ruta o subruta, si alguna. Gana la más específica:
 * `/caja/turnos` es del administrador aunque `/caja` sea de quien vende.
 */
export function seccionDe(ruta) {
  return secciones
    .filter((s) => ruta === s.ruta || ruta.startsWith(`${s.ruta}/`))
    .reduce((mejor, s) => (!mejor || s.ruta.length > mejor.ruta.length ? s : mejor), undefined)
}

/** Si el rol puede ver la ruta (o cualquier subruta suya). Sin rol, nada. */
export function puedeVer(ruta, rol) {
  if (!rol) return false
  return seccionDe(ruta)?.roles.includes(rol) ?? false
}

/** Las secciones del rol, en el orden de la tabla. */
export function seccionesDe(rol) {
  return rol ? secciones.filter((s) => s.roles.includes(rol)) : []
}

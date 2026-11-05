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
 * - Todo lo demás es de ADMINISTRADOR.
 */
const ADMIN = ['ADMINISTRADOR']
const DE_EMPRESA = ['ADMINISTRADOR', 'VENDEDOR', 'CLIENTE']
const PLATAFORMA = ['SUPER_ADMIN']

export const secciones = [
  { ruta: '/panel', etiqueta: 'Panel', roles: ADMIN, grupo: 'Operación' },
  { ruta: '/catalogo', etiqueta: 'Catálogo', roles: DE_EMPRESA, grupo: 'Operación' },
  { ruta: '/importar', etiqueta: 'Importar precios', roles: ADMIN, grupo: 'Operación' },
  { ruta: '/proveedores', etiqueta: 'Proveedores', roles: ADMIN, grupo: 'Compras' },
  { ruta: '/compras', etiqueta: 'Órdenes de compra', roles: ADMIN, grupo: 'Compras' },
  { ruta: '/inventario', etiqueta: 'Inventario', roles: ADMIN, grupo: 'Inventario' },
  { ruta: '/precios', etiqueta: 'Reglas de ganancia', roles: ADMIN, grupo: 'Configuración' },
  { ruta: '/impuestos', etiqueta: 'Impuestos', roles: ADMIN, grupo: 'Configuración' },
  { ruta: '/usuarios', etiqueta: 'Usuarios', roles: ADMIN, grupo: 'Configuración' },
  { ruta: '/empresa', etiqueta: 'Mi empresa', roles: DE_EMPRESA, grupo: 'Configuración' },
  { ruta: '/plataforma/empresas', etiqueta: 'Empresas', roles: PLATAFORMA, grupo: 'Plataforma' },
  { ruta: '/plataforma/tributario', etiqueta: 'Datos tributarios', roles: PLATAFORMA, grupo: 'Plataforma' },
]

/** La sección a la que pertenece una ruta o subruta, si alguna. */
function seccionDe(ruta) {
  return secciones.find((s) => ruta === s.ruta || ruta.startsWith(`${s.ruta}/`))
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

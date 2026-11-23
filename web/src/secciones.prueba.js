/**
 * La tabla de secciones es la única lista de permisos del front. Esta
 * prueba la contrasta con la tabla del encargo, escrita aparte a mano:
 * si alguien cambia una fila sin querer, aquí se nota.
 */
import { describe, expect, it } from 'vitest'
import { puedeVer, seccionDe, secciones, seccionesDe } from './secciones.js'

const ADMIN = ['ADMINISTRADOR']
const VENDEN = ['ADMINISTRADOR', 'VENDEDOR']
const TODOS = ['ADMINISTRADOR', 'VENDEDOR', 'CLIENTE']
const ESPERADO = [
  ['/caja', 'Caja', VENDEN, 'Operación'],
  ['/caja/domicilios', 'Domicilios', VENDEN, 'Operación'],
  ['/caja/devoluciones', 'Devoluciones', VENDEN, 'Operación'],
  ['/caja/encargos', 'Encargos', ADMIN, 'Operación'],
  ['/caja/turnos', 'Turnos de caja', ADMIN, 'Operación'],
  ['/panel', 'Panel', ADMIN, 'Operación'],
  ['/catalogo', 'Catálogo', TODOS, 'Operación'],
  ['/importar', 'Importar precios', ADMIN, 'Operación'],
  ['/proveedores', 'Proveedores', ADMIN, 'Compras'],
  ['/compras', 'Órdenes de compra', ADMIN, 'Compras'],
  ['/inventario', 'Inventario', ADMIN, 'Inventario'],
  ['/precios', 'Reglas de ganancia', ADMIN, 'Configuración'],
  ['/impuestos', 'Impuestos', ADMIN, 'Configuración'],
  ['/caja/medios-pago', 'Medios de pago', ADMIN, 'Configuración'],
  ['/usuarios', 'Usuarios', ADMIN, 'Configuración'],
  ['/empresa', 'Mi empresa', TODOS, 'Configuración'],
  ['/plataforma/empresas', 'Empresas', ['SUPER_ADMIN'], 'Plataforma'],
  ['/plataforma/tributario', 'Datos tributarios', ['SUPER_ADMIN'], 'Plataforma'],
]
const ROLES = ['ADMINISTRADOR', 'VENDEDOR', 'CLIENTE', 'SUPER_ADMIN']

describe('tabla de secciones', () => {
  it('cajaSoloParaAdministradorYVendedor: el cliente y el super administrador no la ven', () => {
    expect(puedeVer('/caja', 'ADMINISTRADOR')).toBe(true)
    expect(puedeVer('/caja', 'VENDEDOR')).toBe(true)
    expect(puedeVer('/caja', 'CLIENTE')).toBe(false)
    expect(puedeVer('/caja', 'SUPER_ADMIN')).toBe(false)
    expect(secciones[0].ruta).toBe('/caja')
  })

  it('rutasDeLaCajaPorRol: turnos, encargos y medios de pago solo del administrador; domicilios y devoluciones de quien vende', () => {
    for (const ruta of ['/caja/turnos', '/caja/encargos', '/caja/medios-pago']) {
      expect(puedeVer(ruta, 'ADMINISTRADOR'), ruta).toBe(true)
      for (const rol of ['VENDEDOR', 'CLIENTE', 'SUPER_ADMIN']) expect(puedeVer(ruta, rol), `${ruta} ${rol}`).toBe(false)
    }
    for (const ruta of ['/caja/domicilios', '/caja/devoluciones']) {
      expect(puedeVer(ruta, 'ADMINISTRADOR'), ruta).toBe(true)
      expect(puedeVer(ruta, 'VENDEDOR'), ruta).toBe(true)
      for (const rol of ['CLIENTE', 'SUPER_ADMIN']) expect(puedeVer(ruta, rol), `${ruta} ${rol}`).toBe(false)
    }
    // El cierre y los cierres propios cuelgan de /caja: no tienen entrada de menú propia.
    expect(puedeVer('/caja/cierre', 'VENDEDOR')).toBe(true)
    expect(puedeVer('/caja/mis-cierres', 'VENDEDOR')).toBe(true)
    expect(secciones.some((s) => s.ruta === '/caja/cierre' || s.ruta === '/caja/mis-cierres')).toBe(false)
  })

  it('manda la sección más específica: /caja/turnos no hereda los roles de /caja', () => {
    expect(seccionDe('/caja/turnos/5')?.ruta).toBe('/caja/turnos')
    expect(seccionDe('/caja/cierre')?.ruta).toBe('/caja')
  })

  it('tiene exactamente las filas del encargo', () => {
    expect(secciones.map((s) => [s.ruta, s.etiqueta, s.roles, s.grupo])).toEqual(ESPERADO)
  })

  describe.each(ESPERADO)('%s', (ruta, _etiqueta, roles) => {
    it.each(ROLES)('para %s', (rol) => {
      expect(puedeVer(ruta, rol)).toBe(roles.includes(rol))
    })
  })

  it('quien no tiene rol no ve nada', () => {
    for (const [ruta] of ESPERADO) {
      expect(puedeVer(ruta, undefined)).toBe(false)
      expect(puedeVer(ruta, null)).toBe(false)
    }
    expect(seccionesDe(undefined)).toEqual([])
  })

  it('una ruta que no existe no la ve nadie', () => {
    expect(puedeVer('/no-existe', 'ADMINISTRADOR')).toBe(false)
  })

  it.each(ROLES)('seccionesDe(%s) no filtra rutas que no le tocan', (rol) => {
    const esperadas = ESPERADO.filter(([, , roles]) => roles.includes(rol)).map(([r]) => r)
    expect(seccionesDe(rol).map((s) => s.ruta)).toEqual(esperadas)
  })

  it('puedeVer acepta una subruta de la sección', () => {
    expect(puedeVer('/compras/ordenes/5', 'ADMINISTRADOR')).toBe(true)
    expect(puedeVer('/compras/ordenes/5', 'VENDEDOR')).toBe(false)
    // «/precios» no debe confundirse con una ruta que solo empieza igual.
    expect(puedeVer('/preciosX', 'ADMINISTRADOR')).toBe(false)
  })
})

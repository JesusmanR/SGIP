/**
 * La tabla de secciones es la única lista de permisos del front. Esta
 * prueba la contrasta con la tabla del encargo, escrita aparte a mano:
 * si alguien cambia una fila sin querer, aquí se nota.
 */
import { describe, expect, it } from 'vitest'
import { puedeVer, secciones, seccionesDe } from './secciones.js'

const ADMIN = ['ADMINISTRADOR']
const TODOS = ['ADMINISTRADOR', 'VENDEDOR', 'CLIENTE']
const ESPERADO = [
  ['/panel', 'Panel', ADMIN, 'Operación'],
  ['/catalogo', 'Catálogo', TODOS, 'Operación'],
  ['/importar', 'Importar precios', ADMIN, 'Operación'],
  ['/proveedores', 'Proveedores', ADMIN, 'Compras'],
  ['/compras', 'Órdenes de compra', ADMIN, 'Compras'],
  ['/inventario', 'Inventario', ADMIN, 'Inventario'],
  ['/precios', 'Reglas de ganancia', ADMIN, 'Configuración'],
  ['/impuestos', 'Impuestos', ADMIN, 'Configuración'],
  ['/usuarios', 'Usuarios', ADMIN, 'Configuración'],
  ['/empresa', 'Mi empresa', TODOS, 'Configuración'],
  ['/plataforma/empresas', 'Empresas', ['SUPER_ADMIN'], 'Plataforma'],
  ['/plataforma/tributario', 'Datos tributarios', ['SUPER_ADMIN'], 'Plataforma'],
]
const ROLES = ['ADMINISTRADOR', 'VENDEDOR', 'CLIENTE', 'SUPER_ADMIN']

describe('tabla de secciones', () => {
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

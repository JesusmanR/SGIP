/**
 * Rutas y guardas, recorridas por ruta y por rol contra la tabla de
 * secciones. Las páginas se sustituyen por marcas: lo que se comprueba
 * es qué se monta y qué no, no lo que cada página hace por dentro.
 */
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import App from './App.jsx'
import { secciones } from './secciones.js'
import { guardarSesion } from './servicios/sesion.js'

vi.mock('./servicios/api.js', () => ({ api: { salir: vi.fn() }, ErrorApi: class extends Error {} }))

vi.mock('./paginas/Acceso.jsx', () => ({ default: () => <p>pagina:/acceso</p> }))
vi.mock('./paginas/CambiarClave.jsx', () => ({ default: () => <p>pagina:/cambiar-clave</p> }))
vi.mock('./paginas/Panel.jsx', () => ({ default: () => <p>pagina:/panel</p> }))
vi.mock('./paginas/Catalogo.jsx', () => ({ default: () => <p>pagina:/catalogo</p> }))
vi.mock('./paginas/Importacion.jsx', () => ({ default: () => <p>pagina:/importar</p> }))
vi.mock('./paginas/Empresa.jsx', () => ({ default: () => <p>pagina:/empresa</p> }))
vi.mock('./paginas/usuarios/index.jsx', () => ({ default: () => <p>pagina:/usuarios</p> }))
vi.mock('./paginas/proveedores/index.jsx', () => ({ default: () => <p>pagina:/proveedores</p> }))
vi.mock('./paginas/compras/index.jsx', () => ({ default: () => <p>pagina:/compras</p> }))
vi.mock('./paginas/inventario/index.jsx', () => ({ default: () => <p>pagina:/inventario</p> }))
vi.mock('./paginas/precios/index.jsx', () => ({ default: () => <p>pagina:/precios</p> }))
vi.mock('./paginas/impuestos/index.jsx', () => ({ default: () => <p>pagina:/impuestos</p> }))
vi.mock('./paginas/plataforma-empresas/index.jsx', () => ({ default: () => <p>pagina:/plataforma/empresas</p> }))
vi.mock('./paginas/plataforma-tributario/index.jsx', () => ({ default: () => <p>pagina:/plataforma/tributario</p> }))

const INICIO = {
  ADMINISTRADOR: '/panel',
  VENDEDOR: '/catalogo',
  CLIENTE: '/catalogo',
  SUPER_ADMIN: '/plataforma/empresas',
}
const ROLES = Object.keys(INICIO)

function ir(ruta) {
  return render(<MemoryRouter initialEntries={[ruta]}><App /></MemoryRouter>)
}
const pagina = (ruta) => `pagina:${ruta}`

beforeEach(() => sessionStorage.clear())

describe('un rol que la tabla no conoce', () => {
  it.each(['/panel', '/ruta-que-no-existe'])('en %s va al acceso y su sesión se cierra', async (ruta) => {
    guardarSesion({ token: 't', nombre: 'N', rol: 'FANTASMA' })
    ir(ruta)
    expect(await screen.findByText('pagina:/acceso')).toBeInTheDocument()
    expect(sessionStorage.getItem('sgip.sesion')).toBeNull()
  })
})

describe('cada sección, por rol', () => {
  const casos = secciones.flatMap((s) => ROLES.map((rol) => [s.ruta, rol, s.roles.includes(rol)]))

  it.each(casos)('%s como %s', async (ruta, rol, permitido) => {
    guardarSesion({ token: 't', nombre: 'N', rol })
    ir(ruta)
    if (permitido) {
      expect(await screen.findByText(pagina(ruta))).toBeInTheDocument()
      return
    }
    // Termina en su inicio y la página prohibida nunca llega a montarse.
    expect(await screen.findByText(pagina(INICIO[rol]))).toBeInTheDocument()
    expect(screen.queryByText(pagina(ruta))).not.toBeInTheDocument()
  })
})

describe('guardas', () => {
  it('/importar por URL como vendedor va al catálogo', async () => {
    guardarSesion({ token: 't', nombre: 'Luis', rol: 'VENDEDOR' })
    ir('/importar')
    expect(await screen.findByText('pagina:/catalogo')).toBeInTheDocument()
    expect(screen.queryByText('pagina:/importar')).not.toBeInTheDocument()
  })

  it.each(secciones.map((s) => [s.ruta]))('sin sesión, %s va a /acceso', async (ruta) => {
    ir(ruta)
    expect(await screen.findByText('pagina:/acceso')).toBeInTheDocument()
    expect(screen.queryByText(pagina(ruta))).not.toBeInTheDocument()
  })

  it.each(secciones.map((s) => [s.ruta]))('con la clave pendiente, %s va a /cambiar-clave', async (ruta) => {
    guardarSesion({ token: 't', nombre: 'N', rol: 'ADMINISTRADOR', debeCambiarClave: true })
    ir(ruta)
    expect(await screen.findByText('pagina:/cambiar-clave')).toBeInTheDocument()
    expect(screen.queryByText(pagina(ruta))).not.toBeInTheDocument()
  })

  it('una subruta profunda también queda bajo la guarda', async () => {
    guardarSesion({ token: 't', nombre: 'Luis', rol: 'VENDEDOR' })
    ir('/compras/ordenes/5')
    expect(await screen.findByText('pagina:/catalogo')).toBeInTheDocument()
    expect(screen.queryByText('pagina:/compras')).not.toBeInTheDocument()
  })

  it('una subruta profunda llega a la sección si el rol puede', async () => {
    guardarSesion({ token: 't', nombre: 'Ana', rol: 'ADMINISTRADOR' })
    ir('/compras/ordenes/5')
    expect(await screen.findByText('pagina:/compras')).toBeInTheDocument()
  })

  it('un rol fuera de la tabla termina en /acceso con la sesión borrada, sin bucle', async () => {
    guardarSesion({ token: 't', nombre: 'N', rol: 'FANTASMA' })
    ir('/panel')
    expect(await screen.findByText('pagina:/acceso')).toBeInTheDocument()
    expect(sessionStorage.getItem('sgip.sesion')).toBeNull()
  })

  it('un rol fuera de la tabla en una ruta desconocida también termina en /acceso', async () => {
    guardarSesion({ token: 't', nombre: 'N', rol: 'FANTASMA' })
    ir('/nada')
    expect(await screen.findByText('pagina:/acceso')).toBeInTheDocument()
    expect(sessionStorage.getItem('sgip.sesion')).toBeNull()
  })

  it('/COMPRAS no esquiva la guarda: el vendedor termina en su inicio', async () => {
    guardarSesion({ token: 't', nombre: 'Luis', rol: 'VENDEDOR' })
    ir('/COMPRAS')
    expect(await screen.findByText('pagina:/catalogo')).toBeInTheDocument()
    expect(screen.queryByText('pagina:/compras')).not.toBeInTheDocument()
  })

  it('/compras-x no es una sección: el administrador va a su inicio', async () => {
    guardarSesion({ token: 't', nombre: 'Ana', rol: 'ADMINISTRADOR' })
    ir('/compras-x')
    expect(await screen.findByText('pagina:/panel')).toBeInTheDocument()
    expect(screen.queryByText('pagina:/compras')).not.toBeInTheDocument()
  })

  it('una ruta desconocida lleva al inicio del rol, o al acceso sin sesión', async () => {
    ir('/nada')
    expect(await screen.findByText('pagina:/acceso')).toBeInTheDocument()
  })

  it('acceso y cambio de clave quedan fuera del armazón', async () => {
    ir('/acceso')
    await screen.findByText('pagina:/acceso')
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
    guardarSesion({ token: 't', nombre: 'N', rol: 'ADMINISTRADOR' })
    ir('/cambiar-clave')
    expect(await screen.findAllByText('pagina:/cambiar-clave')).not.toHaveLength(0)
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
  })
})

describe('menú y rutas', () => {
  it.each(ROLES)('ninguna entrada del menú de %s lleva a una ruta sin registrar', async (rol) => {
    guardarSesion({ token: 't', nombre: 'N', rol })
    const propias = secciones.filter((s) => s.roles.includes(rol))
    for (const s of propias) {
      const { unmount } = ir(s.ruta)
      expect(await screen.findByText(pagina(s.ruta))).toBeInTheDocument()
      unmount()
    }
  })

  it('las secciones se pintan dentro del armazón', async () => {
    guardarSesion({ token: 't', nombre: 'Ana', rol: 'ADMINISTRADOR' })
    ir('/usuarios')
    await screen.findByText('pagina:/usuarios')
    expect(screen.getByRole('navigation', { name: /principal/i })).toBeInTheDocument()
    expect(screen.getAllByRole('main')).toHaveLength(1)
  })
})

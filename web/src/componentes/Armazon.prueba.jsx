/**
 * Armazón de las pantallas con sesión: menú por rol y salida.
 *
 * Hereda de la antigua barra la decisión con criterio: al salir, si la
 * red falla, la sesión local se cierra igual. Dejar al usuario dentro
 * porque la petición no llegó es lo peor de los dos mundos: parece que
 * salió y no salió.
 */
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import Armazon from './Armazon.jsx'
import { api } from '../servicios/api.js'
import { guardarSesion, haySesion } from '../servicios/sesion.js'
import { secciones } from '../secciones.js'

const navegar = vi.fn()
vi.mock('react-router-dom', async (original) => ({
  ...(await original()),
  useNavigate: () => navegar,
}))

vi.mock('../servicios/api.js', () => ({
  api: { salir: vi.fn() },
  ErrorApi: class extends Error {},
}))

const ROLES = ['ADMINISTRADOR', 'VENDEDOR', 'CLIENTE', 'SUPER_ADMIN']

function montar(ruta = '/catalogo') {
  return render(
    <MemoryRouter initialEntries={[ruta]}>
      <Routes>
        <Route element={<Armazon />}>
          <Route path="*" element={<h1>Contenido de la página</h1>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

describe('armazón', () => {
  beforeEach(() => {
    sessionStorage.clear()
    guardarSesion({ token: 'abc', nombre: 'Jesús Manuel', rol: 'ADMINISTRADOR' })
  })
  afterEach(() => vi.clearAllMocks())

  it('un error en una sección no se queda al navegar a otra, y dentro de la sección no se pierde el estado', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    function Roto() {
      throw new Error('falló el módulo')
    }
    render(
      <MemoryRouter initialEntries={['/compras']}>
        <Routes>
          <Route element={<Armazon />}>
            <Route path="/compras" element={<Roto />} />
            <Route path="/inventario" element={<h1>Inventario listo</h1>} />
            <Route path="*" element={<h1>Otra</h1>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    )
    expect(screen.getByRole('alert')).toHaveTextContent('falló el módulo')
    await userEvent.click(screen.getByRole('link', { name: 'Inventario' }))
    expect(await screen.findByRole('heading', { name: 'Inventario listo' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    vi.restoreAllMocks()
  })

  it('pone el contenido de la ruta dentro de un único main', () => {
    montar()
    const main = screen.getByRole('main')
    expect(within(main).getByRole('heading', { name: 'Contenido de la página' })).toBeInTheDocument()
    expect(screen.getAllByRole('main')).toHaveLength(1)
  })

  it('si el contenido falla, el menú sigue y se ve el error', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    function Roto() { throw new Error('no cargó la sección') }
    render(
      <MemoryRouter>
        <Routes>
          <Route element={<Armazon />}>
            <Route path="*" element={<Roto />} />
          </Route>
        </Routes>
      </MemoryRouter>,
    )
    expect(screen.getByRole('alert')).toHaveTextContent('no cargó la sección')
    expect(screen.getByRole('navigation', { name: /principal/i })).toBeInTheDocument()
  })

  it('muestra el nombre real de quien entró', () => {
    montar()
    expect(screen.getByText('Jesús Manuel')).toBeInTheDocument()
    expect(screen.queryByText(/alex morgan/i)).not.toBeInTheDocument()
  })

  describe.each(ROLES)('menú de %s', (rol) => {
    it('enlaza exactamente a las secciones que le tocan, agrupadas', () => {
      guardarSesion({ token: 'abc', nombre: 'X', rol })
      montar()
      const menu = screen.getByRole('navigation', { name: /principal/i })
      const propias = secciones.filter((s) => s.roles.includes(rol))
      const enlaces = within(menu).getAllByRole('link')
      expect(enlaces.map((a) => a.getAttribute('href'))).toEqual(propias.map((s) => s.ruta))
      expect(enlaces.map((a) => a.textContent)).toEqual(propias.map((s) => s.etiqueta))
      for (const grupo of new Set(propias.map((s) => s.grupo))) {
        expect(within(menu).getAllByText(grupo).length).toBeGreaterThan(0)
      }
    })
  })

  it('el vendedor no ve enlaces de administrador', () => {
    guardarSesion({ token: 'abc', nombre: 'Luis', rol: 'VENDEDOR' })
    montar()
    for (const nombre of [/^panel$/i, /importar precios/i, /usuarios/i, /proveedores/i, /impuestos/i]) {
      expect(screen.queryByRole('link', { name: nombre })).not.toBeInTheDocument()
    }
    expect(screen.getByRole('link', { name: /catálogo/i })).toBeInTheDocument()
  })

  it('marca la sección activa, también desde una subruta', () => {
    montar('/compras/ordenes/5')
    expect(screen.getByRole('link', { name: 'Órdenes de compra' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Panel' })).not.toHaveAttribute('aria-current')
  })

  describe('menú del celular', () => {
    it('se abre y se cierra con el botón', async () => {
      const teclado = userEvent.setup()
      montar()
      const boton = screen.getByRole('button', { name: /menú/i })
      expect(boton).toHaveAttribute('aria-expanded', 'false')
      await teclado.click(boton)
      expect(boton).toHaveAttribute('aria-expanded', 'true')
      await teclado.click(boton)
      expect(boton).toHaveAttribute('aria-expanded', 'false')
    })

    it('Escape lo cierra y devuelve el foco al botón', async () => {
      const teclado = userEvent.setup()
      montar()
      const boton = screen.getByRole('button', { name: /menú/i })
      await teclado.click(boton)
      screen.getByRole('link', { name: 'Catálogo' }).focus()
      await teclado.keyboard('{Escape}')
      expect(boton).toHaveAttribute('aria-expanded', 'false')
      expect(boton).toHaveFocus()
    })

    it('Escape con el menú cerrado no roba el foco', async () => {
      const teclado = userEvent.setup()
      montar()
      screen.getByRole('link', { name: 'Catálogo' }).focus()
      await teclado.keyboard('{Escape}')
      expect(screen.getByRole('link', { name: 'Catálogo' })).toHaveFocus()
    })

    it('se cierra al navegar', async () => {
      const teclado = userEvent.setup()
      montar()
      const boton = screen.getByRole('button', { name: /menú/i })
      await teclado.click(boton)
      await teclado.click(screen.getByRole('link', { name: 'Panel' }))
      expect(boton).toHaveAttribute('aria-expanded', 'false')
    })
  })

  describe('barra superior y perfil', () => {
    it.each([
      ['/panel', 'Panel'],
      ['/catalogo', 'Catálogo'],
      ['/compras/ordenes/5', 'Órdenes de compra'],
      ['/precios', 'Reglas de ganancia'],
    ])('en %s la ruta de migas termina en «%s»', (ruta, seccion) => {
      montar(ruta)
      const migas = screen.getByRole('navigation', { name: /ruta/i })
      expect(within(migas).getByText('Espacio de trabajo')).toBeInTheDocument()
      expect(within(migas).getByText(seccion)).toHaveAttribute('aria-current', 'page')
    })

    it('en una ruta sin sección solo muestra el espacio de trabajo', () => {
      montar('/sin-seccion')
      const migas = screen.getByRole('navigation', { name: /ruta/i })
      expect(within(migas).getByText('Espacio de trabajo')).toBeInTheDocument()
      expect(migas.querySelector('[aria-current]')).toBeNull()
    })

    it('el perfil muestra las iniciales, el nombre y el rol en palabras', () => {
      guardarSesion({ token: 'abc', nombre: 'Jesús Manuel', rol: 'SUPER_ADMIN' })
      montar()
      const perfil = screen.getByRole('region', { name: /tu perfil/i })
      expect(within(perfil).getByText('JM')).toBeInTheDocument()
      expect(within(perfil).getByText('Jesús Manuel')).toBeInTheDocument()
      expect(within(perfil).getByText('Super administrador')).toBeInTheDocument()
    })

    it('con un solo nombre, una inicial', () => {
      guardarSesion({ token: 'abc', nombre: 'Ana', rol: 'VENDEDOR' })
      montar()
      expect(within(screen.getByRole('region', { name: /tu perfil/i })).getByText('A')).toBeInTheDocument()
    })

    it('el menú del celular trae su botón de cerrar, que devuelve el foco al botón del menú', async () => {
      const teclado = userEvent.setup()
      montar()
      const boton = screen.getByRole('button', { name: /^menú$/i })
      await teclado.click(boton)
      await teclado.click(screen.getByRole('button', { name: /cerrar navegación/i }))
      expect(boton).toHaveAttribute('aria-expanded', 'false')
      expect(boton).toHaveFocus()
    })

    it('tocar el velo detrás del menú lo cierra', async () => {
      const teclado = userEvent.setup()
      const { container } = montar()
      await teclado.click(screen.getByRole('button', { name: /^menú$/i }))
      await teclado.click(container.querySelector('.armazon-velo'))
      expect(screen.getByRole('button', { name: /^menú$/i })).toHaveAttribute('aria-expanded', 'false')
    })
  })

  it('al salir cierra la sesión y vuelve al acceso', async () => {
    const teclado = userEvent.setup()
    api.salir.mockResolvedValue({})
    montar()
    await teclado.click(screen.getByRole('button', { name: /salir/i }))
    await waitFor(() => expect(haySesion()).toBe(false))
    expect(navegar).toHaveBeenCalledWith('/acceso', { replace: true })
  })

  it('y la cierra también si la petición falla', async () => {
    const teclado = userEvent.setup()
    api.salir.mockRejectedValue(new Error('sin red'))
    montar()
    await teclado.click(screen.getByRole('button', { name: /salir/i }))
    await waitFor(() => expect(haySesion()).toBe(false))
    expect(navegar).toHaveBeenCalledWith('/acceso', { replace: true })
  })
})

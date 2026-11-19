/**
 * Armazón de las pantallas con sesión: menú por rol y salida.
 *
 * Hereda de la antigua barra la decisión con criterio: al salir, si la
 * red falla, la sesión local se cierra igual. Dejar al usuario dentro
 * porque la petición no llegó es lo peor de los dos mundos: parece que
 * salió y no salió.
 */
import { act, render, screen, waitFor, within } from '@testing-library/react'
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

    it('al abrir el cajón el foco entra en él y lo de atrás queda inerte; al cerrar se revierte', async () => {
      const teclado = userEvent.setup()
      const { container } = montar()
      const columna = container.querySelector('.armazon-columna')
      expect(columna).not.toHaveAttribute('inert')
      const boton = screen.getByRole('button', { name: /^menú$/i })
      await teclado.click(boton)
      expect(columna).toHaveAttribute('inert')
      expect(document.getElementById('menu-principal')).toContainElement(document.activeElement)
      await teclado.keyboard('{Escape}')
      expect(columna).not.toHaveAttribute('inert')
      expect(boton).toHaveFocus()
    })

    it('Tab desde el último elemento del cajón no cae en la página de atrás', async () => {
      const teclado = userEvent.setup()
      const { container } = montar()
      await teclado.click(screen.getByRole('button', { name: /^menú$/i }))
      const dentro = [...document.getElementById('menu-principal').querySelectorAll('a, button')]
      dentro.at(-1).focus()
      await teclado.tab()
      expect(container.querySelector('.armazon-columna').contains(document.activeElement)).toBe(false)
    })

    describe('el foco solo se mueve cuando lo de atrás ya no está inerte', () => {
      // jsdom ignora `inert`: se comprueba la invariante directamente. Un focus()
      // dentro de un subárbol inerte no hace nada en un navegador real.
      let llamadas
      let original
      beforeEach(() => {
        llamadas = []
        original = HTMLElement.prototype.focus
        HTMLElement.prototype.focus = function foco(...args) {
          llamadas.push({ el: this, inerte: Boolean(this.closest('[inert]')) })
          return original.apply(this, args)
        }
      })
      afterEach(() => { HTMLElement.prototype.focus = original })

      const delBoton = () => llamadas.filter((l) => l.el.classList.contains('armazon-boton-menu'))

      it('Escape devuelve el foco al botón fuera de lo inerte', async () => {
        const teclado = userEvent.setup()
        montar()
        await teclado.click(screen.getByRole('button', { name: /^menú$/i }))
        llamadas.length = 0
        await teclado.keyboard('{Escape}')
        expect(delBoton().length).toBeGreaterThan(0)
        expect(delBoton().every((l) => !l.inerte)).toBe(true)
        expect(screen.getByRole('button', { name: /^menú$/i })).toHaveFocus()
      })

      it('el botón de cerrar devuelve el foco al botón fuera de lo inerte', async () => {
        const teclado = userEvent.setup()
        montar()
        await teclado.click(screen.getByRole('button', { name: /^menú$/i }))
        llamadas.length = 0
        await teclado.click(screen.getByRole('button', { name: /cerrar navegación/i }))
        expect(delBoton().length).toBeGreaterThan(0)
        expect(delBoton().every((l) => !l.inerte)).toBe(true)
      })

      it('tocar el velo devuelve el foco al botón fuera de lo inerte', async () => {
        const teclado = userEvent.setup()
        const { container } = montar()
        await teclado.click(screen.getByRole('button', { name: /^menú$/i }))
        llamadas.length = 0
        await teclado.click(container.querySelector('.armazon-velo'))
        expect(delBoton().length).toBeGreaterThan(0)
        expect(delBoton().every((l) => !l.inerte)).toBe(true)
      })

      it('navegar desde un enlace del cajón enfoca el h1 fuera de lo inerte y no roba el foco al botón', async () => {
        const teclado = userEvent.setup()
        montar('/panel')
        await teclado.click(screen.getByRole('button', { name: /^menú$/i }))
        llamadas.length = 0
        await teclado.click(screen.getByRole('link', { name: 'Catálogo' }))
        const h1 = screen.getByRole('heading', { level: 1 })
        const deH1 = llamadas.filter((l) => l.el === h1)
        expect(deH1.length).toBeGreaterThan(0)
        expect(deH1.every((l) => !l.inerte)).toBe(true)
        expect(h1).toHaveFocus()
        expect(delBoton()).toHaveLength(0)
      })
    })

    describe('al pasar a escritorio con el cajón abierto', () => {
      let oyentes
      let coincide
      beforeEach(() => {
        oyentes = []
        coincide = true
        window.matchMedia = vi.fn((consulta) => ({
          media: consulta,
          get matches() { return coincide },
          addEventListener: (_, f) => oyentes.push(f),
          removeEventListener: (_, f) => { oyentes = oyentes.filter((x) => x !== f) },
        }))
      })
      afterEach(() => { delete window.matchMedia })
      const agrandar = () => act(() => { coincide = false; oyentes.forEach((f) => f({ matches: false })) })

      it('usa la misma consulta que el CSS del celular y cierra el cajón', async () => {
        const teclado = userEvent.setup()
        montar()
        await teclado.click(screen.getByRole('button', { name: /^menú$/i }))
        expect(window.matchMedia).toHaveBeenCalledWith('(max-width: 48rem)')
        agrandar()
        expect(screen.getByRole('button', { name: /^menú$/i })).toHaveAttribute('aria-expanded', 'false')
      })

      it('si el foco estaba dentro del cajón, pasa al título de la página (el botón del menú ya no se ve)', async () => {
        const teclado = userEvent.setup()
        montar()
        await teclado.click(screen.getByRole('button', { name: /^menú$/i }))
        agrandar()
        expect(screen.getByRole('heading', { level: 1 })).toHaveFocus()
        expect(screen.getByRole('button', { name: /^menú$/i })).not.toHaveFocus()
      })

      it('si el foco estaba fuera del cajón, lo deja donde está', async () => {
        const teclado = userEvent.setup()
        montar()
        await teclado.click(screen.getByRole('button', { name: /^menú$/i }))
        document.activeElement.blur()
        agrandar()
        expect(screen.getByRole('button', { name: /^menú$/i })).not.toHaveFocus()
      })
    })

    describe('altura real de la barra', () => {
      let observadores
      beforeEach(() => {
        observadores = []
        globalThis.ResizeObserver = class {
          constructor(f) { this.f = f; this.desconectado = false; observadores.push(this) }
          observe(el) { this.el = el }
          disconnect() { this.desconectado = true }
        }
      })
      afterEach(() => {
        delete globalThis.ResizeObserver
        document.documentElement.style.removeProperty('--alto-barra-real')
      })

      it('escribe la altura medida de la barra en --alto-barra y se desconecta al salir', () => {
        const { unmount } = montar()
        expect(observadores).toHaveLength(1)
        const barra = document.querySelector('.armazon-barra')
        expect(observadores[0].el).toBe(barra)
        Object.defineProperty(barra, 'offsetHeight', { configurable: true, value: 92 })
        act(() => observadores[0].f([]))
        const raiz = document.documentElement.style
        expect(raiz.getPropertyValue('--alto-barra-real')).toBe('92px')
        expect(raiz.getPropertyValue('--alto-barra')).toBe('')
        unmount()
        expect(observadores[0].desconectado).toBe(true)
      })
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

/** Título de la pestaña y foco al navegar dentro del armazón. */
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { lazy, Suspense } from 'react'
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import Armazon from './Armazon.jsx'
import { guardarSesion } from '../servicios/sesion.js'

vi.mock('../servicios/api.js', () => ({ api: { salir: vi.fn() }, ErrorApi: class extends Error {} }))

function Enlaces() {
  return (
    <nav aria-label="prueba">
      <Link to="/catalogo?q=pc">buscar</Link>
      <Link to="/proveedores">proveedores</Link>
      <Link to="/sin-seccion">sin sección</Link>
      <Link to="/catalogo">catálogo</Link>
    </nav>
  )
}

function montar(ruta = '/catalogo') {
  return render(
    <MemoryRouter initialEntries={[ruta]}>
      <Routes>
        <Route element={<Armazon />}>
          <Route path="/catalogo" element={<><Enlaces /><h1>Catálogo</h1></>} />
          <Route path="/proveedores" element={<><Enlaces /><h1>Proveedores</h1></>} />
          <Route path="/sin-seccion" element={<><Enlaces /><p>sin título</p></>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

describe('al navegar dentro del armazón', () => {
  beforeEach(() => {
    sessionStorage.clear()
    guardarSesion({ token: 'abc', nombre: 'Ana', rol: 'ADMINISTRADOR' })
    document.title = 'SGIP'
    document.body.focus()
  })

  it('en la primera carga no cambia el título ni mueve el foco', () => {
    montar()
    expect(document.title).toBe('SGIP')
    expect(document.activeElement).toBe(document.body)
  })

  it('pone «<sección> · SGIP» en el título y el foco en el h1 de la página nueva', async () => {
    montar()
    await userEvent.click(screen.getByRole('link', { name: 'proveedores' }))
    expect(document.title).toBe('Proveedores · SGIP')
    expect(screen.getByRole('heading', { level: 1, name: 'Proveedores' })).toHaveFocus()
  })

  it('si la página no tiene h1, el foco va al main', async () => {
    montar()
    await userEvent.click(screen.getByRole('link', { name: 'sin sección' }))
    expect(screen.getByRole('main')).toHaveFocus()
    expect(document.title).toBe('SGIP')
  })

  it('al cambiar solo la búsqueda de la misma página no hace nada', async () => {
    montar()
    const enlace = screen.getByRole('link', { name: 'buscar' })
    await userEvent.click(enlace)
    expect(document.title).toBe('SGIP')
    expect(enlace).toHaveFocus()
  })

  it('el foco llega al h1 de una página perezosa que aparece después de navegar', async () => {
    let soltar
    const puerta = new Promise((ok) => { soltar = ok })
    const Perezosa = lazy(() => puerta.then(() => ({ default: () => <h1>Perezosa</h1> })))
    render(
      <MemoryRouter initialEntries={['/catalogo']}>
        <Routes>
          <Route element={<Armazon />}>
            <Route path="/catalogo" element={<Enlaces />} />
            <Route path="/proveedores" element={<Suspense fallback={<p>cargando</p>}><Perezosa /></Suspense>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    )
    await userEvent.click(screen.getByRole('link', { name: 'proveedores' }))
    expect(screen.getByRole('main')).toHaveFocus()
    await act(async () => { soltar() })
    expect(await screen.findByRole('heading', { level: 1, name: 'Perezosa' })).toHaveFocus()
  })

  it('una página que mueve su propio foco al montarse lo conserva', async () => {
    function ConFoco() {
      return <><h1>Con foco</h1><input aria-label="buscar aquí" ref={(el) => el?.focus()} /></>
    }
    render(
      <MemoryRouter initialEntries={['/catalogo']}>
        <Routes>
          <Route element={<Armazon />}>
            <Route path="/catalogo" element={<Enlaces />} />
            <Route path="/proveedores" element={<ConFoco />} />
          </Route>
        </Routes>
      </MemoryRouter>,
    )
    await userEvent.click(screen.getByRole('link', { name: 'proveedores' }))
    expect(screen.getByLabelText('buscar aquí')).toHaveFocus()
  })
})

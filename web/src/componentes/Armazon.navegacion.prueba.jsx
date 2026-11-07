/** Título de la pestaña y foco al navegar dentro del armazón. */
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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
    await userEvent.click(screen.getByRole('link', { name: 'buscar' }))
    expect(document.title).toBe('SGIP')
    expect(document.activeElement).not.toBe(screen.getByRole('main'))
  })
})

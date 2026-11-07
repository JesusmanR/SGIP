/**
 * Pantalla del catálogo: cabecera, estados, tabla o tarjetas y teclado.
 *
 * Complementa a Catalogo.prueba.jsx, que cubre las reglas de precio.
 * Aquí se protege lo que ve y hace quien usa la pantalla: un único
 * título, la carga, el error con reintento, el vacío con su salida, el
 * estado de existencia con palabras y los diálogos que se cierran con
 * el teclado.
 */
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import Catalogo from './Catalogo.jsx'
import { api } from '../servicios/api.js'
import { guardarSesion } from '../servicios/sesion.js'
import { subirImagenes } from '../servicios/subidas.js'
import { CATEGORIAS, CEL, MON, PC, PROVEEDORES, fila } from '../prueba/productos.js'

vi.mock('../servicios/api.js', () => ({
  api: {
    catalogo: vi.fn(),
    categorias: vi.fn(),
    proveedores: vi.fn(),
    imagenesDe: vi.fn(),
    hacerPrincipal: vi.fn(),
  },
  ErrorApi: class extends Error {},
  imagenAutenticada: vi.fn(async () => 'blob:simulada'),
}))
vi.mock('../servicios/subidas.js', () => ({
  MAXIMO_POR_PRODUCTO: 5,
  subirImagenes: vi.fn(),
}))

const montar = () => render(<MemoryRouter><Catalogo /></MemoryRouter>)
const pasarLaEspera = () => act(async () => { await vi.advanceTimersByTimeAsync(400) })
async function conFilas() {
  await pasarLaEspera()
  await waitFor(() => expect(screen.getByText('PC-001', { exact: false })).toBeInTheDocument())
}
const teclado = () => userEvent.setup({ advanceTimers: vi.advanceTimersByTime })

describe('pantalla del catálogo', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    sessionStorage.clear()
    guardarSesion({ token: 't', nombre: 'Ana', rol: 'ADMINISTRADOR' })
    api.catalogo.mockResolvedValue([PC, CEL, MON])
    api.categorias.mockResolvedValue(CATEGORIAS)
    api.proveedores.mockResolvedValue(PROVEEDORES)
    api.imagenesDe.mockResolvedValue([])
  })
  afterEach(() => { vi.useRealTimers(); vi.clearAllMocks() })

  describe('cabecera', () => {
    it('tiene un único h1, «Catálogo»', async () => {
      montar()
      await conFilas()
      const h1 = screen.getAllByRole('heading', { level: 1 })
      expect(h1).toHaveLength(1)
      expect(h1[0]).toHaveTextContent('Catálogo')
    })

    it('el administrador tiene sus acciones en la cabecera', async () => {
      montar()
      await conFilas()
      const cabecera = screen.getByRole('banner')
      expect(within(cabecera).getByRole('button', { name: 'Categorías' })).toBeInTheDocument()
      expect(within(cabecera).getByRole('button', { name: 'Nuevo producto' })).toBeInTheDocument()
    })

    it('quien vende ve el título y ninguna acción de administración', async () => {
      guardarSesion({ token: 't', nombre: 'Luis', rol: 'VENDEDOR' })
      montar()
      await conFilas()
      expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Catálogo')
      expect(screen.queryByRole('button', { name: 'Categorías' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Nuevo producto' })).not.toBeInTheDocument()
    })
  })

  describe('estados', () => {
    it('mientras carga dice que está cargando y no muestra una tabla vacía', async () => {
      api.catalogo.mockReturnValue(new Promise(() => {}))
      montar()
      await pasarLaEspera()
      expect(screen.getByRole('status')).toHaveTextContent(/cargando/i)
      expect(screen.queryByRole('table')).not.toBeInTheDocument()
      expect(screen.queryByText(/catálogo está vacío/i)).not.toBeInTheDocument()
    })

    it('si falla dice qué pasó y deja reintentar', async () => {
      api.catalogo.mockRejectedValueOnce(new Error('El servidor no respondió.'))
      montar()
      await pasarLaEspera()
      const alerta = await screen.findByRole('alert')
      expect(alerta).toHaveTextContent('El servidor no respondió.')

      await teclado().click(within(alerta).getByRole('button', { name: 'Reintentar' }))
      await pasarLaEspera()
      await waitFor(() => expect(fila(screen, 'PC-001')).toBeInTheDocument())
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      expect(api.catalogo).toHaveBeenCalledTimes(2)
    })

    it('un catálogo vacío lo dice y no ofrece limpiar filtros que no hay', async () => {
      api.catalogo.mockResolvedValue([])
      montar()
      await pasarLaEspera()
      expect(await screen.findByText(/catálogo está vacío/i)).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /limpiar filtros/i })).not.toBeInTheDocument()
    })

    it('sin coincidencias con filtros ofrece quitarlos, y al quitarlos vuelve a consultar', async () => {
      api.catalogo.mockResolvedValueOnce([PC, CEL, MON]).mockResolvedValueOnce([])
      montar()
      await conFilas()
      await teclado().type(screen.getByLabelText(/buscar/i), 'zzz')
      await pasarLaEspera()
      expect(await screen.findByText(/ningún producto coincide/i)).toBeInTheDocument()

      api.catalogo.mockResolvedValue([PC, CEL, MON])
      const vacio = screen.getByText(/ningún producto coincide/i).closest('.estado-comun')
      await teclado().click(within(vacio).getByRole('button', { name: /limpiar filtros/i }))
      await pasarLaEspera()
      await waitFor(() => expect(fila(screen, 'PC-001')).toBeInTheDocument())
      expect(screen.getByLabelText(/buscar/i)).toHaveValue('')
    })
  })

  describe('la lista', () => {
    it('es una tabla con nombre y no va dentro de otra tarjeta', async () => {
      montar()
      await conFilas()
      expect(screen.getByRole('table', { name: /productos del catálogo/i })).toBeInTheDocument()
      expect(document.querySelector('.tarjeta .tabla-tarjetas-caja')).toBeNull()
    })

    it('el estado de existencia se dice con palabras, no con el código del servidor', async () => {
      montar()
      await conFilas()
      expect(fila(screen, 'PC-001')).toHaveTextContent('Disponible')
      expect(fila(screen, 'CEL-002')).toHaveTextContent('Stock bajo')
      expect(fila(screen, 'MON-001')).toHaveTextContent('Agotado')
      for (const codigo of ['ACTIVO', 'CRITICO', 'AGOTADO']) {
        expect(screen.queryByText(codigo)).not.toBeInTheDocument()
      }
    })

    it('la existencia lleva su mínimo debajo', async () => {
      montar()
      await conFilas()
      expect(fila(screen, 'PC-001')).toHaveTextContent('mín. 3')
    })

    it('el filtro de existencia usa las mismas palabras', async () => {
      montar()
      await conFilas()
      const filtro = screen.getByLabelText(/existencia/i)
      expect(within(filtro).getByRole('option', { name: 'Disponible' })).toHaveValue('ACTIVO')
      expect(within(filtro).getByRole('option', { name: 'Stock bajo' })).toHaveValue('CRITICO')
    })

    it('cada celda lleva su rótulo para la vista de tarjetas', async () => {
      montar()
      await conFilas()
      const celda = within(fila(screen, 'PC-001')).getByText('Computadores').closest('td')
      expect(celda).toHaveAttribute('data-etiqueta', 'Categoría')
    })
  })

  describe('lo que no ve quien vende', () => {
    it('aunque la respuesta trajera costo, proveedor o margen, no se pintan', async () => {
      guardarSesion({ token: 't', nombre: 'Luis', rol: 'VENDEDOR' })
      montar()
      await conFilas()
      const texto = document.body.textContent
      expect(texto).not.toContain('2.100.000')
      expect(texto).not.toContain(PC.proveedor)
      expect(texto).not.toMatch(/margen|kárdex|kardex/i)
      expect(screen.queryByRole('columnheader', { name: /costo|margen|proveedor/i }))
        .not.toBeInTheDocument()
    })
  })

  describe('imágenes', () => {
    it('lo que el servidor rechaza al subir se dice y no se pierde', async () => {
      subirImagenes.mockResolvedValue({ creadas: [], problemas: ['gato.heic: formato no admitido'] })
      montar()
      await conFilas()
      const entrada = fila(screen, 'PC-001').querySelector('input[type="file"]')
      await userEvent.setup({ applyAccept: false, advanceTimers: vi.advanceTimersByTime })
        .upload(entrada, new File(['x'], 'gato.heic', { type: 'image/heic' }))
      expect(await screen.findByRole('alert')).toHaveTextContent('gato.heic: formato no admitido')
    })
  })

  describe('teclado en los diálogos', () => {
    it('Escape cierra la ficha y el foco vuelve al botón que la abrió', async () => {
      montar()
      await conFilas()
      const boton = within(fila(screen, 'PC-001')).getByRole('button', { name: /editar/i })
      await teclado().click(boton)
      expect(screen.getByRole('dialog')).toBeInTheDocument()
      await teclado().keyboard('{Escape}')
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
      expect(boton).toHaveFocus()
    })

    it('Escape cierra las categorías', async () => {
      montar()
      await conFilas()
      await teclado().click(screen.getByRole('button', { name: 'Categorías' }))
      expect(screen.getByRole('dialog', { name: /categorías/i })).toBeInTheDocument()
      await teclado().keyboard('{Escape}')
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })

    it('Escape cierra la galería', async () => {
      montar()
      await conFilas()
      await teclado().click(within(fila(screen, 'PC-001')).getByRole('button', { name: /añadir imágenes/i }))
      expect(screen.getByRole('dialog', { name: /imágenes de/i })).toBeInTheDocument()
      await teclado().keyboard('{Escape}')
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
  })
})

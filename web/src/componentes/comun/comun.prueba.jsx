import { readFileSync } from 'node:fs'
import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ErrorApi } from '../../servicios/api.js'
import Aviso from './Aviso.jsx'
import AvisoConexion from './AvisoConexion.jsx'
import EncabezadoPagina from './EncabezadoPagina.jsx'
import EstadoCarga from './EstadoCarga.jsx'
import EstadoError from './EstadoError.jsx'
import EstadoSinPermiso from './EstadoSinPermiso.jsx'
import EstadoVacio from './EstadoVacio.jsx'
import TablaOTarjetas from './TablaOTarjetas.jsx'

describe('EncabezadoPagina', () => {
  it('lleva un solo h1, la descripción y las acciones', () => {
    render(<EncabezadoPagina titulo="Usuarios" descripcion="Quién entra" acciones={<button>Nuevo</button>} />)
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(screen.getByRole('heading', { level: 1, name: 'Usuarios' })).toBeInTheDocument()
    expect(screen.getByText('Quién entra')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Nuevo' })).toBeInTheDocument()
  })

  it('sin descripción ni acciones no pinta huecos', () => {
    const { container } = render(<EncabezadoPagina titulo="Solo" />)
    expect(container.querySelector('p')).toBeNull()
    expect(container.querySelector('.encabezado-acciones')).toBeNull()
  })
})

describe('TablaOTarjetas', () => {
  const columnas = [
    { clave: 'nombre', titulo: 'Nombre', principal: true },
    { clave: 'precio', titulo: 'Precio', render: (f) => `$ ${f.precio}` },
  ]
  const filas = [{ id: 1, nombre: 'Mouse', precio: 10 }, { id: 2, nombre: 'Teclado', precio: 20 }]
  const montar = () => render(
    <TablaOTarjetas columnas={columnas} filas={filas} claveFila={(f) => f.id} etiqueta="Productos" />,
  )

  it('va sobre una caja que hace de tarjeta', () => {
    montar()
    expect(screen.getByRole('table', { name: 'Productos' }).parentElement).toHaveClass('tabla-tarjetas-caja')
  })

  it('el contenido de cada celda va en un solo bloque, aunque tenga varios elementos', () => {
    render(
      <TablaOTarjetas
        columnas={[{ clave: 'n', titulo: 'Nombre', principal: true, render: (f) => <><span>{f.n}</span><small>nota</small></> }]}
        filas={[{ id: 1, n: 'Mouse' }]} claveFila={(f) => f.id} etiqueta="Uno" />,
    )
    const celda = screen.getByRole('rowheader')
    expect(celda.children).toHaveLength(1)
    expect(celda.firstElementChild).toHaveClass('celda-valor')
    expect(celda.firstElementChild).toHaveTextContent('Mousenota')
  })

  it('es una tabla real con nombre, cabeceras y una fila por dato', () => {
    montar()
    const tabla = screen.getByRole('table', { name: 'Productos' })
    expect(within(tabla).getAllByRole('columnheader').map((c) => c.textContent)).toEqual(['Nombre', 'Precio'])
    expect(within(tabla).getAllByRole('row')).toHaveLength(3)
    expect(within(tabla).getByRole('rowheader', { name: 'Mouse' })).toBeInTheDocument()
    expect(within(tabla).getByRole('cell', { name: '$ 20' })).toBeInTheDocument()
  })

  it('una columna numérica se alinea a la derecha con cifras tabulares', () => {
    render(
      <TablaOTarjetas
        columnas={[
          { clave: 'n', titulo: 'Nombre', principal: true },
          { clave: 'c', titulo: 'Cantidad', numerica: true },
        ]}
        filas={[{ id: 1, n: 'Mouse', c: 3 }]}
        claveFila={(f) => f.id}
        etiqueta="Stock"
      />,
    )
    expect(screen.getByRole('columnheader', { name: 'Cantidad' })).toHaveClass('numerica')
    expect(screen.getByRole('cell', { name: '3' })).toHaveClass('numerica')
    expect(screen.getByRole('rowheader', { name: 'Mouse' })).not.toHaveClass('numerica')
  })

  it('cada dato lleva su etiqueta para la tarjeta de celular, solo por CSS', () => {
    montar()
    expect(screen.getByRole('cell', { name: '$ 10' })).toHaveAttribute('data-etiqueta', 'Precio')
    // La etiqueta no es texto del DOM: el lector de pantalla no la oye dos veces.
    expect(screen.getAllByText('Precio')).toHaveLength(1)
  })
})

describe('estados', () => {
  it('carga: avisa a lectores de pantalla', () => {
    const { unmount } = render(<EstadoCarga />)
    expect(screen.getByRole('status')).toHaveTextContent(/cargando/i)
    unmount()
    render(<EstadoCarga mensaje="Cargando usuarios…" />)
    expect(screen.getByText('Cargando usuarios…')).toBeInTheDocument()
  })

  it('vacío: título, detalle y acción opcionales', () => {
    const { rerender } = render(
      <EstadoVacio titulo="Sin proveedores" detalle="Crea el primero" accion={<button>Crear</button>} />,
    )
    expect(screen.getByText('Sin proveedores')).toBeInTheDocument()
    expect(screen.getByText('Crea el primero')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Crear' })).toBeInTheDocument()
    rerender(<EstadoVacio titulo="Sin proveedores" />)
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('error: dice qué pasó y reintenta', async () => {
    const alReintentar = vi.fn()
    render(<EstadoError error={new ErrorApi(500, 'No se pudo cargar')} alReintentar={alReintentar} />)
    expect(screen.getByRole('alert')).toHaveTextContent('No se pudo cargar')
    await userEvent.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(alReintentar).toHaveBeenCalledOnce()
  })

  it('error: acepta un texto y sin reintento no muestra el botón', () => {
    render(<EstadoError error="Se cayó" />)
    expect(screen.getByRole('alert')).toHaveTextContent('Se cayó')
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('sin permiso: lo dice con palabras', () => {
    render(<EstadoSinPermiso />)
    expect(screen.getByText(/no tienes permiso/i)).toBeInTheDocument()
  })
})

describe('Aviso', () => {
  it.each([
    ['exito', 'status', /listo/i],
    ['info', 'status', /informaci/i],
    ['aviso', 'status', /atenci/i],
    ['peligro', 'alert', /error/i],
  ])('%s usa role %s y un rótulo de texto, no solo color', (tipo, rol, rotulo) => {
    render(<Aviso tipo={tipo}>Pasó algo</Aviso>)
    const aviso = screen.getByRole(rol)
    expect(aviso).toHaveTextContent('Pasó algo')
    expect(aviso).toHaveTextContent(rotulo)
    // No reutiliza la clase global .aviso, que tiene otras reglas.
    expect(aviso).toHaveClass(`aviso-estado--${tipo}`)
    expect(aviso).not.toHaveClass('aviso')
  })
})

describe('estados con el tono del diseño', () => {
  it('vacío, carga y error llevan un icono decorativo y conservan sus roles', () => {
    const { container, rerender } = render(<EstadoVacio titulo="Nada" />)
    expect(container.querySelector('.estado-icono[aria-hidden="true"]')).not.toBeNull()
    rerender(<EstadoCarga />)
    expect(container.querySelector('.estado-esqueleto[aria-hidden="true"]')).not.toBeNull()
    expect(screen.getByRole('status')).toHaveTextContent('Cargando…')
    rerender(<EstadoError error="Se cayó" />)
    expect(container.querySelector('.estado-icono[aria-hidden="true"]')).not.toBeNull()
    expect(screen.getByRole('alert')).toBeInTheDocument()
  })

  it('el error ya no pinta toda la tarjeta de rojo: el texto basta', () => {
    expect(readFileSync('src/estilos/comun.css', 'utf8')).not.toMatch(/\.estado-error\s*\{[^}]*background/)
  })
})

describe('AvisoConexion', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks() })

  it('sin aviso mientras hay conexión: la región existe, vacía', () => {
    render(<AvisoConexion />)
    expect(screen.getByRole('status')).toBeEmptyDOMElement()
  })

  it('la región es la misma antes y después del aviso, para que se anuncie el cambio', () => {
    render(<AvisoConexion />)
    const region = screen.getByRole('status')
    act(() => { window.dispatchEvent(new Event('offline')) })
    expect(screen.getByRole('status')).toBe(region)
    expect(region).toHaveTextContent(/sin conexión/i)
    act(() => { window.dispatchEvent(new Event('online')) })
    act(() => { vi.advanceTimersByTime(6000) })
    expect(screen.getByRole('status')).toBe(region)
    expect(region).toBeEmptyDOMElement()
  })

  it('«restablecida» solo sale si antes hubo una pérdida', () => {
    render(<AvisoConexion />)
    act(() => { window.dispatchEvent(new Event('online')) })
    expect(screen.getByRole('status')).toBeEmptyDOMElement()
  })

  it('una región vacía no ocupa sitio', () => {
    const css = readFileSync('src/estilos/armazon.css', 'utf8')
    expect(css).toMatch(/\.aviso-conexion:empty\s*\{[^}]*display:\s*none/)
  })

  it('al perder la conexión lo dice con palabras', () => {
    render(<AvisoConexion />)
    act(() => { window.dispatchEvent(new Event('offline')) })
    expect(screen.getByRole('status')).toHaveTextContent(/sin conexión/i)
  })

  it('al volver avisa que volvió y se quita solo', () => {
    render(<AvisoConexion />)
    act(() => { window.dispatchEvent(new Event('offline')) })
    act(() => { window.dispatchEvent(new Event('online')) })
    expect(screen.getByRole('status')).toHaveTextContent(/conexión restablecida/i)
    act(() => { vi.advanceTimersByTime(6000) })
    expect(screen.getByRole('status')).toBeEmptyDOMElement()
  })

  it('si ya empieza sin conexión, avisa desde el principio', () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    render(<AvisoConexion />)
    expect(screen.getByRole('status')).toHaveTextContent(/sin conexión/i)
  })
})

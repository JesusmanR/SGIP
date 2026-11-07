import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { ErrorApi } from '../../servicios/api.js'
import Aviso from './Aviso.jsx'
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

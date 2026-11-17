import { render, screen } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

import { pesos } from './ayuda.js'
import ReciboImprimible from './ReciboImprimible.jsx'

const RECIBO = {
  empresa: { razonSocial: 'Black Steel Parts', nit: '900123456-7' },
  consecutivo: 'V-0042',
  fecha: '2026-11-16T10:30:00-05:00',
  cajero: 'Marta Ruiz',
  cliente: { nombre: 'Ana Pérez', identificacion: '1020304050' },
  lineas: [
    {
      nombre: 'Tornillo M6', cantidad: 2, precioUnitario: 10000, subtotal: 20000, descuento: 2000,
      iva: 3420, total: 21420, encargo: false, nuevo: false, entregaEstimada: null,
    },
    {
      nombre: 'Llave de paso', cantidad: 1, precioUnitario: 15000, subtotal: 15000, descuento: 0,
      iva: 0, total: 15000, encargo: true, nuevo: true, entregaEstimada: '2026-12-01',
    },
  ],
  subtotal: 35000, descuento: 2000, iva: 3420, total: 36420, cambio: 3580,
  pagos: [{ medio: 'Efectivo', monto: 36420, recibido: 40000, referencia: null }],
  domicilio: { direccion: 'Calle 1 # 2-3', ciudad: 'Cali', telefono: '3001112233', recibe: 'Ana', contraEntrega: false },
  porCobrar: false,
}

describe('recibo', () => {
  it('reciboImprimibleTieneLasMarcas: empresa, consecutivo, fecha, líneas, descuento, IVA, total, pagos y cambio', () => {
    const { container } = render(<ReciboImprimible recibo={RECIBO} />)
    expect(container.querySelector('.recibo')).toBeInTheDocument()
    expect(screen.getByText('Black Steel Parts')).toBeInTheDocument()
    expect(screen.getByText(/900123456-7/)).toBeInTheDocument()
    expect(screen.getByText(/V-0042/)).toBeInTheDocument()
    expect(screen.getByText(/16 nov 2026/)).toBeInTheDocument()
    expect(screen.getByText(/Marta Ruiz/)).toBeInTheDocument()
    expect(screen.getByText(/Ana Pérez/)).toBeInTheDocument()
    expect(screen.getByText('Tornillo M6')).toBeInTheDocument()
    expect(screen.getByText(/descuento/i, { selector: '.recibo-descuento' })).toHaveTextContent(pesos(2000))
    expect(screen.getByText(/^IVA/)).toHaveTextContent(pesos(3420))
    expect(screen.getByText(/^Total/)).toHaveTextContent(pesos(36420))
    expect(screen.getByText(/Efectivo/)).toHaveTextContent(pesos(36420))
    expect(screen.getByText(/^Cambio/)).toHaveTextContent(pesos(3580))
  })

  it('lleva las marcas de encargo, producto nuevo y domicilio', () => {
    render(<ReciboImprimible recibo={RECIBO} />)
    expect(screen.getByText(/encargo — entrega estimada: 1 dic 2026/i)).toBeInTheDocument()
    expect(screen.getByText(/domicilio/i, { selector: '.recibo-marca' })).toHaveTextContent('Calle 1 # 2-3')
  })

  it('una venta por cobrar en la entrega lo dice y no muestra pagos ni cambio', () => {
    render(<ReciboImprimible recibo={{ ...RECIBO, pagos: [], cambio: 0, porCobrar: true, domicilio: { ...RECIBO.domicilio, contraEntrega: true } }} />)
    expect(screen.getByText(/por cobrar en la entrega/i)).toBeInTheDocument()
    expect(screen.queryByText(/^Cambio/)).not.toBeInTheDocument()
  })

  it('no lleva costo ni utilidad', () => {
    render(<ReciboImprimible recibo={RECIBO} />)
    expect(document.body).not.toHaveTextContent(/costo|utilidad|margen/i)
  })
})

describe('hoja de estilos de la caja', () => {
  const css = readFileSync('src/estilos/caja.css', 'utf8')

  it('imprime en tirilla de 80 mm y solo el recibo', () => {
    expect(css).toMatch(/@page\s*\{\s*size:\s*80mm auto/)
    expect(css).toMatch(/@media print/)
  })

  it('no escribe colores sueltos: todo sale de tokens.css', () => {
    const sinComentarios = css.replace(/\/\*[\s\S]*?\*\//g, '')
    expect(sinComentarios).not.toMatch(/#[0-9a-fA-F]{3,8}\b/)
    expect(sinComentarios).not.toMatch(/\b(rgb|rgba|hsl|hsla|oklch)\(/)
  })
})

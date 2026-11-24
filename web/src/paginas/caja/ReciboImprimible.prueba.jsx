import { render, screen } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

import { pesos } from './ayuda.js'
import ReciboImprimible from './ReciboImprimible.jsx'
import { armarRecibo, empresaDelRecibo } from './venta.js'

const RECIBO = {
  empresa: {
    nombre: 'Black Steel Parts', tipoDocumento: 'NIT', nit: '900123456',
    direccion: 'Carrera 5 # 10-20', telefono: '6025551234',
  },
  consecutivo: 'V-0042',
  fecha: '2026-11-16T10:30:00-05:00',
  cajero: 'Marta Ruiz',
  cliente: { nombre: 'Ana Pérez', identificacion: '1020304050' },
  lineas: [
    {
      nombre: 'Tornillo M6', cantidad: 2, precioUnitario: 10000, subtotal: 20000, descuento: 2000,
      iva: 3420, total: 21420, encargo: false, entregaEstimada: null,
    },
    {
      nombre: 'Llave de paso', cantidad: 1, precioUnitario: 15000, subtotal: 15000, descuento: 0,
      iva: 0, total: 15000, encargo: true, entregaEstimada: '2026-12-01',
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
    expect(screen.getByText('NIT 900123456')).toBeInTheDocument()
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

  it('lleva las marcas de encargo y domicilio', () => {
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
    expect(css).not.toMatch(/size:\s*80mm\s+auto/)
    expect(css).toMatch(/@media print/)
    expect(css).toMatch(/display:\s*none/)
  })

  it('el mensaje de por qué no se puede cobrar conserva su color de aviso y su peso', () => {
    const regla = css.match(/\.caja-total-mensaje\s*\{([^}]*)\}/)
    expect(regla, 'falta la regla .caja-total-mensaje').not.toBeNull()
    expect(regla[1]).toMatch(/color:\s*var\(--aviso\)/)
    expect(regla[1]).toMatch(/font-weight:\s*(600|700)/)
  })

  it('el resumen fijo no deja «Cobrar» fuera de alcance en una pantalla baja', () => {
    const regla = css.match(/\.caja-columna-resumen\s*\{([^}]*)\}/)
    expect(regla[1]).toMatch(/max-height:\s*calc\(100vh/)
    expect(regla[1]).toMatch(/overflow-y:\s*auto/)
  })

  it('las dos columnas pasan a una sola por debajo de 72 rem', () => {
    expect(css).toMatch(/@media \(max-width: 72rem\)\s*\{\s*\.caja-rejilla/)
  })

  it('no escribe colores sueltos: todo sale de tokens.css', () => {
    const sinComentarios = css.replace(/\/\*[\s\S]*?\*\//g, '')
    expect(sinComentarios).not.toMatch(/#[0-9a-fA-F]{3,8}\b/)
    expect(sinComentarios).not.toMatch(/\b(rgb|rgba|hsl|hsla|oklch)\(/)
  })

  it('reciboMuestraDireccionYTelefono: la dirección y el teléfono de la empresa, y CC si es cédula', () => {
    const { rerender } = render(<ReciboImprimible recibo={RECIBO} />)
    const cabecera = document.querySelector('.recibo-cabecera')
    expect(cabecera).toHaveTextContent('Carrera 5 # 10-20')
    expect(cabecera).toHaveTextContent('6025551234')
    rerender(<ReciboImprimible recibo={{ ...RECIBO, empresa: { ...RECIBO.empresa, tipoDocumento: 'CC', nit: '1020304050', direccion: null, telefono: null } }} />)
    expect(screen.getByText('CC 1020304050')).toBeInTheDocument()
    expect(document.querySelector('.recibo-cabecera').querySelectorAll('p')).toHaveLength(2)
  })

  it('reciboInmediatoYReimpresionMuestranLoMismo: la copia de la venta y la del Recibo dan el mismo papel', () => {
    const copia = { nombre: 'Black Steel Parts', tipoDocumento: 'NIT', nit: '900123456', direccion: 'Carrera 5 # 10-20', telefono: '6025551234' }
    const inmediato = armarRecibo({
      empresa: { razonSocial: 'Otro nombre hoy', nit: '1' },
      venta: { consecutivo: 'V-0042', total: 0, cambio: 0, lineas: [], empresa: copia },
      lineas: [], pagos: [], cliente: null, cajero: 'Marta Ruiz', domicilio: null,
    })
    const reimpreso = {
      ...inmediato,
      empresa: empresaDelRecibo({
        empresa: 'Black Steel Parts', tipoDocumentoEmpresa: 'NIT', nitEmpresa: '900123456',
        direccionEmpresa: 'Carrera 5 # 10-20', telefonoEmpresa: '6025551234',
      }),
    }
    expect(inmediato.empresa).toEqual(copia)
    expect(reimpreso.empresa).toEqual(copia)
    const a = render(<ReciboImprimible recibo={inmediato} />)
    const textoA = a.container.querySelector('.recibo-cabecera').textContent
    a.unmount()
    const b = render(<ReciboImprimible recibo={reimpreso} />)
    expect(b.container.querySelector('.recibo-cabecera').textContent).toBe(textoA)
  })

  it('si la venta no trae copia (reintento anterior) cae a los datos actuales de la empresa', () => {
    const r = armarRecibo({
      empresa: { razonSocial: 'Razón S.A.S.', nombreComercial: 'Comercial', tipoDocumento: 'CC', nit: '55', direccion: 'Calle 1', telefono: '300' },
      venta: { consecutivo: 'V-1', total: 0, cambio: 0, lineas: [], empresa: null },
      lineas: [], pagos: [], cliente: null, cajero: 'M', domicilio: null,
    })
    expect(r.empresa).toEqual({ nombre: 'Comercial', tipoDocumento: 'CC', nit: '55', direccion: 'Calle 1', telefono: '300' })
  })
})

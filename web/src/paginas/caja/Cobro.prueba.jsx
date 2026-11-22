import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { MEDIOS, pesos } from './ayuda.js'
import Cobro from './Cobro.jsx'

const alCobrar = vi.fn()
beforeEach(() => vi.clearAllMocks())

const VISTA = { subtotal: 10000, descuento: 0, iva: 1900, total: 11900, motivo: null, lineas: [{}] }
const MEDIOS_CAJA = MEDIOS.filter((m) => m.tipo !== 'SALDO_A_FAVOR')

function Pantalla({ vista = VISTA, saldo = 0, bloqueo = null, contraEntrega = false, sinConexion = false, medios = MEDIOS }) {
  const [pagos, setPagos] = useState([])
  return (
    <Cobro
      vista={vista} medios={medios} saldo={saldo} pagos={pagos} alCambiarPagos={setPagos}
      bloqueo={bloqueo} contraEntrega={contraEntrega} sinConexion={sinConexion} enviando={false} alCobrar={alCobrar}
    />
  )
}

const cobrar = () => screen.getByRole('button', { name: /^cobrar$/i })
async function agregarPago(monto, extra = {}) {
  const campoMonto = screen.getByLabelText(/^monto$/i)
  await userEvent.clear(campoMonto)
  await userEvent.type(campoMonto, String(monto))
  if (extra.recibido) await userEvent.type(screen.getByLabelText(/^recibido$/i), String(extra.recibido))
  await userEvent.click(screen.getByRole('button', { name: /agregar pago/i }))
}

describe('cobro', () => {
  it('muestra el total, el desglose y el monto sugerido', () => {
    render(<Pantalla />)
    expect(screen.getByTestId('total-a-cobrar')).toHaveTextContent(pesos(11900))
    expect(screen.getByText(/^IVA$/)).toBeInTheDocument()
    expect(screen.getByLabelText(/^monto$/i)).toHaveValue('11900')
  })

  it('cobrarDeshabilitadoHastaCuadrar: solo se habilita cuando lo pagado es igual al total', async () => {
    render(<Pantalla />)
    expect(cobrar()).toBeDisabled()
    await agregarPago(5000)
    expect(cobrar()).toBeDisabled()
    expect(screen.getByText(/falta por pagar/i)).toHaveTextContent(pesos(6900))
    expect(screen.getByLabelText(/^monto$/i)).toHaveValue('6900')
    await userEvent.click(screen.getByRole('button', { name: /agregar pago/i }))
    expect(cobrar()).toBeEnabled()
    await userEvent.click(cobrar())
    expect(alCobrar).toHaveBeenCalledTimes(1)
  })

  it('un pago de más no se agrega', async () => {
    render(<Pantalla />)
    await agregarPago(12000)
    expect(screen.getByText(/no puede pasar de lo que falta/i)).toBeInTheDocument()
    expect(cobrar()).toBeDisabled()
  })

  it('cambioEnEfectivo: calcula las vueltas con lo recibido', async () => {
    render(<Pantalla />)
    await agregarPago(11900, { recibido: 20000 })
    expect(screen.getByText(/^cambio/i)).toHaveTextContent(pesos(8100))
    expect(cobrar()).toBeEnabled()
  })

  it('el efectivo recibido no puede ser menos que el monto', async () => {
    render(<Pantalla />)
    await agregarPago(11900, { recibido: 5000 })
    expect(screen.getByText(/lo recibido no alcanza/i)).toBeInTheDocument()
  })

  it('tarjeta pide el número de aprobación y transferencia la referencia', async () => {
    render(<Pantalla />)
    await userEvent.selectOptions(screen.getByLabelText(/medio de pago/i), 'Tarjeta')
    expect(screen.getByLabelText(/n\.º de aprobación/i)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /agregar pago/i }))
    expect(screen.getByText(/escribe el número de aprobación/i)).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText(/n\.º de aprobación/i), 'A123')
    await userEvent.click(screen.getByRole('button', { name: /agregar pago/i }))
    expect(screen.getByRole('button', { name: /quitar pago de tarjeta/i })).toBeInTheDocument()
    expect(cobrar()).toBeEnabled()
    await userEvent.click(screen.getByRole('button', { name: /quitar pago de tarjeta/i }))
    expect(cobrar()).toBeDisabled()
    await userEvent.selectOptions(screen.getByLabelText(/medio de pago/i), 'Transferencia')
    expect(screen.getByLabelText(/^referencia$/i)).toBeInTheDocument()
  })

  it('el saldo a favor solo aparece con saldo, y no se pasa del saldo', async () => {
    const { rerender } = render(<Pantalla saldo={0} />)
    expect(within(screen.getByLabelText(/medio de pago/i)).queryByRole('option', { name: /saldo a favor/i })).toBeNull()
    rerender(<Pantalla saldo={5000} />)
    await userEvent.selectOptions(screen.getByLabelText(/medio de pago/i), 'Saldo a favor')
    await userEvent.click(screen.getByRole('button', { name: /agregar pago/i }))
    expect(screen.getByText(/el saldo a favor disponible es/i)).toHaveTextContent(pesos(5000))
    expect(cobrar()).toBeDisabled()
  })

  it('contra entrega no lleva pagos y el botón dice lo que hace', async () => {
    render(<Pantalla contraEntrega />)
    expect(screen.queryByLabelText(/medio de pago/i)).not.toBeInTheDocument()
    const boton = screen.getByRole('button', { name: /registrar para cobrar en la entrega/i })
    expect(boton).toBeEnabled()
    await userEvent.click(boton)
    expect(alCobrar).toHaveBeenCalled()
  })

  it('un bloqueo se dice con palabras y deshabilita el botón', async () => {
    render(<Pantalla bloqueo="Una venta no puede quedar en cero." />)
    expect(screen.getByText('Una venta no puede quedar en cero.')).toBeInTheDocument()
    expect(cobrar()).toBeDisabled()
  })

  it('sin conexión no se puede cobrar aunque todo cuadre', async () => {
    render(<Pantalla sinConexion />)
    await agregarPago(11900)
    expect(cobrar()).toBeDisabled()
  })

  it('no habla de costo ni de margen', () => {
    render(<Pantalla medios={MEDIOS_CAJA} />)
    expect(document.body).not.toHaveTextContent(/costo|margen|utilidad/i)
  })
})

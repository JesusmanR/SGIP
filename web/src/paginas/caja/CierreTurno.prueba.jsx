import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { cerrarTurno, resumenDelTurno } from '../../servicios/api/caja.js'
import { entrar, errorApi, pesos } from './ayuda.js'
import CierreTurno from './CierreTurno.jsx'

vi.mock('../../servicios/api/caja.js')

const RESUMEN = {
  id: 5, cajero: 'Marta Ruiz', apertura: '2026-11-16T08:00:00-05:00', cierre: null,
  baseEfectivo: 50000, efectivoEsperado: 150000, efectivoContado: null, diferencia: null,
  porMedio: { EFECTIVO: 100000, TARJETA: 30000, TRANSFERENCIA: 20000 }, observacion: null,
}

beforeEach(() => {
  vi.resetAllMocks()
  entrar('VENDEDOR')
  resumenDelTurno.mockResolvedValue(RESUMEN)
  cerrarTurno.mockImplementation(async (s) => ({
    ...RESUMEN, cierre: '2026-11-16T18:00:00-05:00', efectivoContado: s.efectivoContado,
    diferencia: s.efectivoContado - 150000, observacion: s.observacion,
  }))
})

async function abrir() {
  render(<MemoryRouter><CierreTurno /></MemoryRouter>)
  await screen.findByLabelText(/efectivo contado/i)
}
const contado = () => screen.getByLabelText(/efectivo contado/i)
const cerrar = () => screen.getByRole('button', { name: /^cerrar turno$/i })
/** El valor de una fila «rótulo / cifra» del resumen. */
const cifra = (resumen, rotulo) => within(resumen).getByText(rotulo, { selector: 'dt' }).nextElementSibling

describe('cierre del turno', () => {
  it('muestra la base, el efectivo esperado y los totales por medio', async () => {
    await abrir()
    expect(screen.getByRole('heading', { level: 1, name: /cerrar turno/i })).toBeInTheDocument()
    const resumen = screen.getByRole('region', { name: /resumen del turno/i })
    expect(cifra(resumen, 'Base de efectivo')).toHaveTextContent(pesos(50000))
    expect(cifra(resumen, 'Efectivo esperado')).toHaveTextContent(pesos(150000))
    expect(cifra(resumen, 'Efectivo')).toHaveTextContent(pesos(100000))
    expect(cifra(resumen, 'Tarjeta')).toHaveTextContent(pesos(30000))
    expect(cifra(resumen, 'Transferencia')).toHaveTextContent(pesos(20000))
  })

  it('cierreMuestraSobranteYFaltante: dice cuánto sobra o falta con palabras', async () => {
    await abrir()
    await userEvent.type(contado(), '152000')
    expect(screen.getByRole('status', { name: /diferencia/i })).toHaveTextContent(`Sobran ${pesos(2000)}`)
    await userEvent.clear(contado())
    await userEvent.type(contado(), '148000')
    expect(screen.getByRole('status', { name: /diferencia/i })).toHaveTextContent(`Faltan ${pesos(2000)}`)
    await userEvent.clear(contado())
    await userEvent.type(contado(), '150000')
    expect(screen.getByRole('status', { name: /diferencia/i })).toHaveTextContent(/el efectivo cuadra/i)
  })

  it('observacionObligatoriaConDiferencia: con diferencia no cierra sin observación; sin diferencia no la pide', async () => {
    await abrir()
    await userEvent.type(contado(), '152000')
    await userEvent.click(cerrar())
    expect(screen.getByText(/explica la diferencia/i)).toBeInTheDocument()
    expect(cerrarTurno).not.toHaveBeenCalled()
    await userEvent.type(screen.getByLabelText(/observación/i), 'Se dio de menos en una venta')
    await userEvent.click(cerrar())
    expect(cerrarTurno).toHaveBeenCalledWith({ efectivoContado: 152000, observacion: 'Se dio de menos en una venta' })
    expect(await screen.findByRole('heading', { name: /turno cerrado/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /volver a la caja/i })).toHaveAttribute('href', '/caja')
  })

  it('con el efectivo cuadrado cierra sin observación', async () => {
    await abrir()
    await userEvent.type(contado(), '150000')
    await userEvent.click(cerrar())
    expect(cerrarTurno).toHaveBeenCalledWith({ efectivoContado: 150000, observacion: null })
    expect(await screen.findByRole('heading', { name: /turno cerrado/i })).toBeInTheDocument()
  })

  it('el efectivo contado es obligatorio y en pesos enteros', async () => {
    await abrir()
    await userEvent.click(cerrar())
    expect(screen.getByText(/escribe el efectivo que contaste/i)).toBeInTheDocument()
    await userEvent.type(contado(), '1.5')
    await userEvent.click(cerrar())
    expect(screen.getByText(/pesos enteros/i)).toBeInTheDocument()
    expect(cerrarTurno).not.toHaveBeenCalled()
  })

  it('un fallo al cerrar se anuncia y deja volver a intentar', async () => {
    cerrarTurno.mockRejectedValueOnce(errorApi(409, 'Este turno ya está cerrado.'))
    await abrir()
    await userEvent.type(contado(), '150000')
    await userEvent.click(cerrar())
    expect(await screen.findByRole('alert')).toHaveTextContent('Este turno ya está cerrado.')
    expect(cerrar()).toBeEnabled()
  })

  it('la observación admite hasta 200 caracteres, lo que cabe en el servidor, y lo dice', async () => {
    await abrir()
    expect(screen.getByLabelText(/observación/i)).toHaveAttribute('maxlength', '200')
    expect(screen.getByText(/hasta 200 caracteres/i)).toBeInTheDocument()
  })

  it('sin turno abierto al cerrar (409), muestra el mensaje del servidor y no cierra nada', async () => {
    cerrarTurno.mockRejectedValueOnce(errorApi(409, 'No tienes un turno abierto para cerrar.'))
    await abrir()
    await userEvent.type(contado(), '150000')
    await userEvent.click(cerrar())
    expect(await screen.findByRole('alert')).toHaveTextContent('No tienes un turno abierto para cerrar.')
    expect(screen.queryByRole('heading', { name: /turno cerrado/i })).not.toBeInTheDocument()
  })

  it('si el servidor ve una diferencia que la pantalla no vio (400), muestra su mensaje y deja explicarla', async () => {
    cerrarTurno.mockRejectedValueOnce(
      errorApi(400, 'El efectivo contado no coincide con el esperado: explica la diferencia en la observación.'),
    )
    await abrir()
    await userEvent.type(contado(), '150000')
    await userEvent.click(cerrar())
    expect(await screen.findByRole('alert')).toHaveTextContent(/explica la diferencia en la observación/i)
    expect(screen.getByLabelText(/observación/i)).toBeEnabled()
    expect(cerrar()).toBeEnabled()
  })

  it('un resumen nulo (204, sin turno) se trata como «no hay turno abierto» y no revienta', async () => {
    resumenDelTurno.mockResolvedValue(null)
    render(<MemoryRouter><CierreTurno /></MemoryRouter>)
    expect(await screen.findByText(/no hay un turno abierto/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /volver a la caja/i })).toBeInTheDocument()
    expect(screen.queryByLabelText(/efectivo contado/i)).not.toBeInTheDocument()
  })

  it('sin turno abierto lo dice y lleva a la caja', async () => {
    resumenDelTurno.mockRejectedValue(errorApi(404, 'No hay un turno abierto.'))
    render(<MemoryRouter><CierreTurno /></MemoryRouter>)
    expect(await screen.findByText(/no hay un turno abierto/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /volver a la caja/i })).toBeInTheDocument()
  })
})

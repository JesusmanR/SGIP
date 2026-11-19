import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { listarTurnos } from '../../servicios/api/caja.js'
import { entrar, errorApi, pesos } from './ayuda.js'
import Turnos from './Turnos.jsx'

vi.mock('../../servicios/api/caja.js')

const CIERRE_SOBRA = {
  id: 5, cajero: 'Marta Ruiz', apertura: '2026-11-16T08:00:00-05:00', cierre: '2026-11-16T18:00:00-05:00',
  baseEfectivo: 50000, efectivoEsperado: 150000, efectivoContado: 152000, diferencia: 2000,
  porMedio: { EFECTIVO: 100000 }, observacion: 'Un billete de más',
}
const CIERRE_FALTA = { ...CIERRE_SOBRA, id: 6, cajero: 'Luis Soto', efectivoContado: 148000, diferencia: -2000, observacion: 'Faltó cambio' }
const CIERRE_CUADRA = { ...CIERRE_SOBRA, id: 7, cajero: 'Marta Ruiz', efectivoContado: 150000, diferencia: 0, observacion: null }
const ABIERTO = { ...CIERRE_SOBRA, id: 8, cierre: null, efectivoContado: null, diferencia: null }
const FECHA = expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/)

function montar() {
  return render(<MemoryRouter><Turnos /></MemoryRouter>)
}

beforeEach(() => {
  vi.resetAllMocks()
  listarTurnos.mockResolvedValue([CIERRE_SOBRA, CIERRE_FALTA, CIERRE_CUADRA, ABIERTO])
})

describe('turnos de caja', () => {
  it('el administrador ve todos los cierres con su diferencia dicha con palabras', async () => {
    entrar('ADMINISTRADOR')
    montar()
    expect(await screen.findByRole('heading', { level: 1, name: /turnos de caja/i })).toBeInTheDocument()
    const tabla = await screen.findByRole('table', { name: /turnos de caja/i })
    expect(within(tabla).getAllByRole('row')).toHaveLength(5)
    expect(within(tabla).getByText(`Sobran ${pesos(2000)}`)).toBeInTheDocument()
    expect(within(tabla).getByText(`Faltan ${pesos(2000)}`)).toBeInTheDocument()
    expect(within(tabla).getByText('Cuadra')).toBeInTheDocument()
    expect(within(tabla).getByText('Luis Soto')).toBeInTheDocument()
    expect(within(tabla).getByText('Faltó cambio')).toBeInTheDocument()
    expect(within(tabla).getByText('Abierto')).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: /cajero/i })).toBeInTheDocument()
  })

  it('vendedorNoVeTurnosAjenos: el vendedor ve solo «Mis cierres», sin la columna de cajero', async () => {
    entrar('VENDEDOR')
    listarTurnos.mockResolvedValue([CIERRE_SOBRA, CIERRE_CUADRA])
    montar()
    expect(await screen.findByRole('heading', { level: 1, name: /mis cierres/i })).toBeInTheDocument()
    const tabla = await screen.findByRole('table', { name: /mis cierres/i })
    expect(within(tabla).getAllByRole('row')).toHaveLength(3)
    expect(screen.queryByRole('columnheader', { name: /cajero/i })).not.toBeInTheDocument()
    expect(screen.queryByText('Luis Soto')).not.toBeInTheDocument()
    // Quien filtra es el servidor: el front no manda ni acepta un cajero.
    expect(JSON.stringify(listarTurnos.mock.calls)).not.toMatch(/cajero/i)
    expect(screen.getByRole('link', { name: /volver a la caja/i })).toHaveAttribute('href', '/caja')
  })

  it('pide un rango de fechas y vuelve a pedir cuando cambia', async () => {
    entrar('ADMINISTRADOR')
    montar()
    await screen.findByRole('table')
    expect(listarTurnos).toHaveBeenCalledWith({ desde: FECHA, hasta: FECHA })
    const desde = screen.getByLabelText(/^desde$/i)
    await userEvent.clear(desde)
    await userEvent.type(desde, '2026-11-01')
    await waitFor(() => expect(listarTurnos).toHaveBeenLastCalledWith(expect.objectContaining({ desde: '2026-11-01' })))
  })

  it('sin cierres en el rango lo dice', async () => {
    entrar('ADMINISTRADOR')
    listarTurnos.mockResolvedValue([])
    montar()
    expect(await screen.findByText(/no hay turnos en estas fechas/i)).toBeInTheDocument()
  })

  it('un fallo se muestra con reintento', async () => {
    entrar('ADMINISTRADOR')
    listarTurnos.mockRejectedValueOnce(errorApi(500, 'Se cayó'))
    montar()
    expect(await screen.findByRole('alert')).toHaveTextContent('Se cayó')
    await userEvent.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByRole('table')).toBeInTheDocument()
  })
})

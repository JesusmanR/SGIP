/**
 * Sección Datos tributarios de la plataforma: UVT por año, catálogo nacional
 * de impuestos y alerta de la UVT. Módulo de API simulado, ErrorApi real.
 */
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ErrorApi } from '../../servicios/api.js'
import { apiPlataformaTributario } from '../../servicios/api/plataforma-tributario.js'
import Tributario from './index.jsx'

vi.mock('../../servicios/api/plataforma-tributario.js', () => ({
  apiPlataformaTributario: {
    listarUvt: vi.fn(), alertaUvt: vi.fn(), fijarUvt: vi.fn(),
    listarImpuestos: vi.fn(), cambiarImpuesto: vi.fn(),
  },
}))

const UVT = [
  { anio: 2026, valor: 52374, resolucion: 'Res. 193' },
  { anio: 2025, valor: 49799, resolucion: null },
]
const IVA = {
  tipoImpuestoId: 1, codigo: 'IVA_GENERAL', nombre: 'IVA general', tratamiento: 'GRAVADO', tarifa: 0.19,
  umbralUvt: null, fundamento: 'Art. 468', vigenteDesde: '2020-01-01', vigenteHasta: null, activo: true,
}
const TOPE = {
  ...IVA, tipoImpuestoId: 2, codigo: 'IVA_UMBRAL', nombre: 'Exclusión por tope', tratamiento: 'EXCLUIDO_HASTA_UMBRAL',
  tarifa: 0.19, umbralUvt: 3500, fundamento: 'Art. 424',
}
const SIN_ALERTA = { activa: false, anio: 2027, diasRestantes: 0 }

function montar() {
  return render(
    <MemoryRouter initialEntries={['/plataforma/tributario']}>
      <Routes>
        <Route path="/plataforma/tributario/*" element={<Tributario />} />
      </Routes>
    </MemoryRouter>,
  )
}

function datosNormales() {
  apiPlataformaTributario.listarUvt.mockResolvedValue(UVT)
  apiPlataformaTributario.listarImpuestos.mockResolvedValue([IVA, TOPE])
  apiPlataformaTributario.alertaUvt.mockResolvedValue(SIN_ALERTA)
}

beforeEach(() => {
  Object.values(apiPlataformaTributario).forEach((f) => f.mockReset())
})

describe('alerta de la UVT', () => {
  it('activa: informa el año y los días, sin alarma y sin decir que se detiene la venta', async () => {
    datosNormales()
    apiPlataformaTributario.alertaUvt.mockResolvedValue({ activa: true, anio: 2027, diasRestantes: 55 })
    montar()
    const aviso = await screen.findByRole('status', { name: /uvt del 2027/i })
    expect(aviso).toHaveTextContent(/2027/)
    expect(aviso).toHaveTextContent(/55 días/)
    expect(aviso).toHaveTextContent(/las tiendas siguen vendiendo/i)
    expect(aviso).toHaveTextContent(/opcional/i)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('inactiva: no muestra nada', async () => {
    datosNormales()
    montar()
    await screen.findByRole('table', { name: /uvt por año/i })
    expect(screen.queryByRole('status', { name: /uvt del/i })).not.toBeInTheDocument()
  })

  it('si la alerta falla, el resto de la pantalla sigue sirviendo', async () => {
    datosNormales()
    apiPlataformaTributario.alertaUvt.mockRejectedValue(new ErrorApi(500, 'x'))
    montar()
    expect(await screen.findByRole('table', { name: /uvt por año/i })).toBeInTheDocument()
  })
})

describe('UVT por año', () => {
  it('lista los años con el valor en pesos', async () => {
    datosNormales()
    montar()
    const tabla = await screen.findByRole('table', { name: /uvt por año/i })
    const filas = within(tabla).getAllByRole('row')
    expect(filas).toHaveLength(3)
    expect(filas[1]).toHaveTextContent('2026')
    expect(filas[1]).toHaveTextContent(/52\.374/)
    expect(filas[1]).toHaveTextContent('Res. 193')
    expect(apiPlataformaTributario.listarUvt).toHaveBeenCalledTimes(1)
  })

  it('vacío', async () => {
    datosNormales()
    apiPlataformaTributario.listarUvt.mockResolvedValue([])
    montar()
    expect(await screen.findByText(/todavía no hay valores de la uvt/i)).toBeInTheDocument()
  })

  it('error con reintento', async () => {
    datosNormales()
    apiPlataformaTributario.listarUvt.mockRejectedValueOnce(new ErrorApi(500, 'Falló UVT'))
    montar()
    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('Falló UVT')
    await userEvent.click(within(alerta).getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByRole('table', { name: /uvt por año/i })).toBeInTheDocument()
  })

  it('403: sin permiso', async () => {
    apiPlataformaTributario.listarUvt.mockRejectedValue(new ErrorApi(403, 'No'))
    apiPlataformaTributario.listarImpuestos.mockRejectedValue(new ErrorApi(403, 'No'))
    apiPlataformaTributario.alertaUvt.mockRejectedValue(new ErrorApi(403, 'No'))
    montar()
    expect((await screen.findAllByText(/no tienes permiso/i)).length).toBeGreaterThan(0)
  })

  it('fija el valor de un año con su resolución y motivo, y recarga la lista', async () => {
    datosNormales()
    apiPlataformaTributario.fijarUvt.mockResolvedValue({ anio: 2027, valor: 55000, resolucion: 'R9' })
    montar()
    await screen.findByRole('table', { name: /uvt por año/i })
    await userEvent.type(screen.getByLabelText('Año'), '2027')
    await userEvent.type(screen.getByLabelText(/valor de la uvt/i), '55000')
    await userEvent.type(screen.getByLabelText('Resolución'), 'R9')
    await userEvent.type(screen.getByLabelText('Motivo'), 'Nuevo año')
    await userEvent.click(screen.getByRole('button', { name: 'Fijar UVT' }))
    await waitFor(() => expect(apiPlataformaTributario.fijarUvt).toHaveBeenCalledWith(
      2027, { valor: 55000, resolucion: 'R9', motivo: 'Nuevo año' },
    ))
    expect(await screen.findByText(/uvt del 2027 guardada/i)).toBeInTheDocument()
    await waitFor(() => expect(apiPlataformaTributario.listarUvt).toHaveBeenCalledTimes(2))
    expect(apiPlataformaTributario.alertaUvt).toHaveBeenCalledTimes(2)
  })

  it('«Cambiar» en una fila precarga el año y el valor', async () => {
    datosNormales()
    montar()
    const tabla = await screen.findByRole('table', { name: /uvt por año/i })
    await userEvent.click(within(tabla).getAllByRole('button', { name: /cambiar/i })[0])
    expect(screen.getByLabelText('Año')).toHaveValue(2026)
    expect(screen.getByLabelText(/valor de la uvt/i)).toHaveValue(52374)
  })

  it('año o valor inválidos: no envía, marca el campo y enfoca el primero', async () => {
    datosNormales()
    montar()
    await screen.findByRole('table', { name: /uvt por año/i })
    await userEvent.click(screen.getByRole('button', { name: 'Fijar UVT' }))
    const anio = screen.getByLabelText('Año')
    expect(anio).toHaveAttribute('aria-invalid', 'true')
    expect(anio).toHaveFocus()
    expect(screen.getByText('Escribe el año, por ejemplo 2027.')).toBeInTheDocument()
    expect(screen.getByText('Escribe el valor de la UVT en pesos.')).toBeInTheDocument()
    await userEvent.type(anio, '2027')
    await userEvent.type(screen.getByLabelText(/valor de la uvt/i), '0')
    await userEvent.click(screen.getByRole('button', { name: 'Fijar UVT' }))
    expect(screen.getByText('El valor tiene que ser mayor que cero.')).toBeInTheDocument()
    expect(apiPlataformaTributario.fijarUvt).not.toHaveBeenCalled()
  })

  it('un 400 del servidor se muestra con su detalle', async () => {
    datosNormales()
    apiPlataformaTributario.fijarUvt.mockRejectedValue(new ErrorApi(400, 'El valor admite hasta diez enteros.'))
    montar()
    await screen.findByRole('table', { name: /uvt por año/i })
    await userEvent.type(screen.getByLabelText('Año'), '2027')
    await userEvent.type(screen.getByLabelText(/valor de la uvt/i), '55000')
    await userEvent.click(screen.getByRole('button', { name: 'Fijar UVT' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('El valor admite hasta diez enteros.')
    expect(screen.getByRole('button', { name: 'Fijar UVT' })).toBeEnabled()
  })
})

describe('catálogo nacional de impuestos', () => {
  it('lista cada tipo con su tarifa en porcentaje y su tope en UVT', async () => {
    datosNormales()
    montar()
    const tabla = await screen.findByRole('table', { name: /impuestos nacionales/i })
    const filas = within(tabla).getAllByRole('row')
    expect(filas).toHaveLength(3)
    expect(filas[1]).toHaveTextContent('IVA general')
    expect(filas[1]).toHaveTextContent('Gravado')
    expect(filas[1]).toHaveTextContent(/19\s?%/)
    expect(filas[2]).toHaveTextContent(/3\.500/)
    expect(filas[2]).toHaveTextContent(/excluido hasta el tope/i)
  })

  it('vacío y error con reintento', async () => {
    datosNormales()
    apiPlataformaTributario.listarImpuestos.mockRejectedValueOnce(new ErrorApi(500, 'Falló impuestos'))
    apiPlataformaTributario.listarImpuestos.mockResolvedValueOnce([])
    montar()
    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('Falló impuestos')
    await userEvent.click(within(alerta).getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByText(/todavía no hay impuestos/i)).toBeInTheDocument()
  })

  it('editar un impuesto con tope muestra el campo del tope y envía la tarifa como fracción', async () => {
    datosNormales()
    apiPlataformaTributario.cambiarImpuesto.mockResolvedValue({ ...TOPE, umbralUvt: 4000 })
    montar()
    const tabla = await screen.findByRole('table', { name: /impuestos nacionales/i })
    await userEvent.click(within(tabla).getByRole('button', { name: /editar exclusión por tope/i }))
    const formulario = screen.getByRole('form', { name: /editar exclusión por tope/i })
    expect(within(formulario).getByLabelText(/tarifa/i)).toHaveValue('0.19')
    const tope = within(formulario).getByLabelText(/tope en uvt/i)
    await userEvent.clear(tope)
    await userEvent.type(tope, '4000')
    await userEvent.type(within(formulario).getByLabelText('Motivo'), 'Nueva norma')
    await userEvent.click(within(formulario).getByRole('button', { name: 'Guardar impuesto' }))
    await waitFor(() => expect(apiPlataformaTributario.cambiarImpuesto).toHaveBeenCalledWith('IVA_UMBRAL', {
      tarifa: 0.19, umbralUvt: 4000, fundamento: 'Art. 424', motivo: 'Nueva norma',
    }))
    expect(await screen.findByText(/exclusión por tope guardado/i)).toBeInTheDocument()
    await waitFor(() => expect(apiPlataformaTributario.listarImpuestos).toHaveBeenCalledTimes(2))
  })

  it('un impuesto sin tope no pide el tope y lo manda vacío', async () => {
    datosNormales()
    apiPlataformaTributario.cambiarImpuesto.mockResolvedValue(IVA)
    montar()
    const tabla = await screen.findByRole('table', { name: /impuestos nacionales/i })
    await userEvent.click(within(tabla).getByRole('button', { name: /editar iva general/i }))
    const formulario = screen.getByRole('form', { name: /editar iva general/i })
    expect(within(formulario).queryByLabelText(/tope en uvt/i)).not.toBeInTheDocument()
    await userEvent.click(within(formulario).getByRole('button', { name: 'Guardar impuesto' }))
    await waitFor(() => expect(apiPlataformaTributario.cambiarImpuesto).toHaveBeenCalledWith(
      'IVA_GENERAL', expect.objectContaining({ tarifa: 0.19, umbralUvt: null }),
    ))
  })

  it('tarifa fuera de 0 a 1 o vacía: no envía y enfoca el campo', async () => {
    datosNormales()
    montar()
    const tabla = await screen.findByRole('table', { name: /impuestos nacionales/i })
    await userEvent.click(within(tabla).getByRole('button', { name: /editar iva general/i }))
    const formulario = screen.getByRole('form', { name: /editar iva general/i })
    const tarifa = within(formulario).getByLabelText(/tarifa/i)
    await userEvent.clear(tarifa)
    await userEvent.type(tarifa, '19')
    await userEvent.click(within(formulario).getByRole('button', { name: 'Guardar impuesto' }))
    expect(tarifa).toHaveAttribute('aria-invalid', 'true')
    expect(tarifa).toHaveFocus()
    expect(screen.getByText(/entre 0 y 1/i)).toBeInTheDocument()
    expect(apiPlataformaTributario.cambiarImpuesto).not.toHaveBeenCalled()
  })

  it('un 422 del servidor se muestra y se puede cancelar la edición', async () => {
    datosNormales()
    apiPlataformaTributario.cambiarImpuesto.mockRejectedValue(new ErrorApi(422, 'El tope es obligatorio.'))
    montar()
    const tabla = await screen.findByRole('table', { name: /impuestos nacionales/i })
    await userEvent.click(within(tabla).getByRole('button', { name: /editar iva general/i }))
    await userEvent.click(screen.getByRole('button', { name: 'Guardar impuesto' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('El tope es obligatorio.')
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(screen.queryByRole('form', { name: /editar iva general/i })).not.toBeInTheDocument()
  })
})

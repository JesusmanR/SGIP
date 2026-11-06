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
import { NUMERO } from '../../servicios/formato.js'
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
const UVT_CON_DECIMALES = [{ anio: 2024, valor: 47065.5, resolucion: null }]
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

  it('falta un día: lo dice en singular', async () => {
    datosNormales()
    apiPlataformaTributario.alertaUvt.mockResolvedValue({ activa: true, anio: 2027, diasRestantes: 1 })
    montar()
    const aviso = await screen.findByRole('status', { name: /uvt del 2027/i })
    expect(aviso).toHaveTextContent(/falta 1 día para que empiece/i)
    expect(aviso).not.toHaveTextContent(/faltan/i)
  })

  it('ya empezó el año: no habla de días', async () => {
    datosNormales()
    apiPlataformaTributario.alertaUvt.mockResolvedValue({ activa: true, anio: 2026, diasRestantes: 0 })
    montar()
    const aviso = await screen.findByRole('status', { name: /uvt del 2026/i })
    expect(aviso).toHaveTextContent(/ese año ya empezó/i)
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
    expect(filas[1]).toHaveTextContent(NUMERO.format(52374))
    expect(filas[1]).toHaveTextContent('Res. 193')
    expect(apiPlataformaTributario.listarUvt).toHaveBeenCalledTimes(1)
  })

  it('muestra los decimales de la UVT si los tiene', async () => {
    datosNormales()
    apiPlataformaTributario.listarUvt.mockResolvedValue([...UVT, ...UVT_CON_DECIMALES])
    montar()
    const tabla = await screen.findByRole('table', { name: /uvt por año/i })
    const fila = within(tabla).getAllByRole('row')[3]
    expect(fila).toHaveTextContent(NUMERO.format(47065.5))
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
    expect(screen.getByText('El valor tiene que ser de al menos 1 peso.')).toBeInTheDocument()
    expect(apiPlataformaTributario.fijarUvt).not.toHaveBeenCalled()
  })

  it.each(['2005', '2101'])('el año %s está fuera de 2006 a 2100: no envía', async (anio) => {
    datosNormales()
    montar()
    await screen.findByRole('table', { name: /uvt por año/i })
    await userEvent.type(screen.getByLabelText('Año'), anio)
    await userEvent.type(screen.getByLabelText(/valor de la uvt/i), '55000')
    await userEvent.click(screen.getByRole('button', { name: 'Fijar UVT' }))
    expect(screen.getByText('El año va de 2006 a 2100.')).toBeInTheDocument()
    expect(screen.getByLabelText('Año')).toHaveAttribute('aria-invalid', 'true')
    expect(apiPlataformaTributario.fijarUvt).not.toHaveBeenCalled()
  })

  it.each(['2006', '2100'])('el año %s, en el borde del rango, sí se envía', async (anio) => {
    datosNormales()
    apiPlataformaTributario.fijarUvt.mockResolvedValue({})
    montar()
    await screen.findByRole('table', { name: /uvt por año/i })
    await userEvent.type(screen.getByLabelText('Año'), anio)
    await userEvent.type(screen.getByLabelText(/valor de la uvt/i), '55000')
    await userEvent.click(screen.getByRole('button', { name: 'Fijar UVT' }))
    await waitFor(() => expect(apiPlataformaTributario.fijarUvt).toHaveBeenCalledWith(
      Number(anio), expect.objectContaining({ valor: 55000 }),
    ))
  })

  it('un valor menor que 1 no se envía', async () => {
    datosNormales()
    montar()
    await screen.findByRole('table', { name: /uvt por año/i })
    await userEvent.type(screen.getByLabelText('Año'), '2027')
    await userEvent.type(screen.getByLabelText(/valor de la uvt/i), '0.5')
    await userEvent.click(screen.getByRole('button', { name: 'Fijar UVT' }))
    expect(screen.getByText('El valor tiene que ser de al menos 1 peso.')).toBeInTheDocument()
    expect(apiPlataformaTributario.fijarUvt).not.toHaveBeenCalled()
  })

  it('un valor con tres decimales no se envía; con dos sí', async () => {
    datosNormales()
    apiPlataformaTributario.fijarUvt.mockResolvedValue({})
    montar()
    await screen.findByRole('table', { name: /uvt por año/i })
    await userEvent.type(screen.getByLabelText('Año'), '2027')
    const valor = screen.getByLabelText(/valor de la uvt/i)
    await userEvent.type(valor, '55000.123')
    await userEvent.click(screen.getByRole('button', { name: 'Fijar UVT' }))
    expect(screen.getByText('El valor admite como máximo 2 decimales.')).toBeInTheDocument()
    expect(apiPlataformaTributario.fijarUvt).not.toHaveBeenCalled()
    await userEvent.clear(valor)
    await userEvent.type(valor, '55000.12')
    await userEvent.click(screen.getByRole('button', { name: 'Fijar UVT' }))
    await waitFor(() => expect(apiPlataformaTributario.fijarUvt).toHaveBeenCalledWith(
      2027, expect.objectContaining({ valor: 55000.12 }),
    ))
  })

  it('un 400 del servidor con el nombre del campo queda junto a ese campo y se limpia al editarlo', async () => {
    datosNormales()
    apiPlataformaTributario.fijarUvt.mockRejectedValue(new ErrorApi(
      400, 'valor: El valor de la UVT tiene que ser mayor que cero; motivo: El motivo no puede pasar de 200 caracteres',
    ))
    montar()
    await screen.findByRole('table', { name: /uvt por año/i })
    await userEvent.type(screen.getByLabelText('Año'), '2027')
    const valor = screen.getByLabelText(/valor de la uvt/i)
    await userEvent.type(valor, '55000')
    await userEvent.click(screen.getByRole('button', { name: 'Fijar UVT' }))
    await waitFor(() => expect(valor).toHaveAttribute('aria-invalid', 'true'))
    expect(screen.getByText('El valor de la UVT tiene que ser mayor que cero')).toBeInTheDocument()
    expect(screen.getByLabelText('Motivo')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(valor).toHaveFocus()
    await userEvent.type(valor, '1')
    expect(valor).not.toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Motivo')).toHaveAttribute('aria-invalid', 'true')
  })

  it('un 403 al fijar la UVT lo dice y deja el botón listo', async () => {
    datosNormales()
    apiPlataformaTributario.fijarUvt.mockRejectedValue(new ErrorApi(403, 'Sin permiso para esta operación.'))
    montar()
    await screen.findByRole('table', { name: /uvt por año/i })
    await userEvent.type(screen.getByLabelText('Año'), '2027')
    await userEvent.type(screen.getByLabelText(/valor de la uvt/i), '55000')
    await userEvent.click(screen.getByRole('button', { name: 'Fijar UVT' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Sin permiso para esta operación.')
    expect(screen.getByRole('button', { name: 'Fijar UVT' })).toBeEnabled()
  })

  it('un doble clic real en «Fijar UVT» envía una sola vez', async () => {
    datosNormales()
    let resolver
    apiPlataformaTributario.fijarUvt.mockReturnValue(new Promise((r) => { resolver = r }))
    montar()
    await screen.findByRole('table', { name: /uvt por año/i })
    await userEvent.type(screen.getByLabelText('Año'), '2027')
    await userEvent.type(screen.getByLabelText(/valor de la uvt/i), '55000')
    await userEvent.dblClick(screen.getByRole('button', { name: 'Fijar UVT' }))
    expect(apiPlataformaTributario.fijarUvt).toHaveBeenCalledTimes(1)
    resolver({})
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
    expect(filas[2]).toHaveTextContent(NUMERO.format(3500))
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
    expect(within(formulario).getByLabelText(/tarifa/i)).toHaveValue('0,19')
    const tope = within(formulario).getByLabelText(/tope en uvt/i)
    await userEvent.clear(tope)
    await userEvent.type(tope, '4000')
    await userEvent.type(within(formulario).getByLabelText('Motivo'), 'Nueva norma')
    await userEvent.click(within(formulario).getByRole('button', { name: 'Guardar impuesto' }))
    expect(apiPlataformaTributario.cambiarImpuesto).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: 'Sí, guardar' }))
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
    await userEvent.click(screen.getByRole('button', { name: 'Sí, guardar' }))
    await waitFor(() => expect(apiPlataformaTributario.cambiarImpuesto).toHaveBeenCalledWith(
      'IVA_GENERAL', expect.objectContaining({ tarifa: 0.19, umbralUvt: null }),
    ))
  })

  async function abrirEdicion(nombre) {
    const tabla = await screen.findByRole('table', { name: /impuestos nacionales/i })
    await userEvent.click(within(tabla).getByRole('button', { name: new RegExp(`editar ${nombre}`, 'i') }))
    return screen.getByRole('form', { name: new RegExp(`editar ${nombre}`, 'i') })
  }

  it('guardar pide confirmación que dice que afecta a todas las tiendas al instante', async () => {
    datosNormales()
    montar()
    const formulario = await abrirEdicion('iva general')
    await userEvent.click(within(formulario).getByRole('button', { name: 'Guardar impuesto' }))
    const caja = screen.getByRole('group', { name: /guardar los cambios de iva general/i })
    expect(caja).toHaveTextContent(/todas las tiendas/i)
    expect(caja).toHaveTextContent(/al instante/i)
    expect(apiPlataformaTributario.cambiarImpuesto).not.toHaveBeenCalled()
  })

  it('«Volver a editar» no envía nada y devuelve el foco al botón de guardar', async () => {
    datosNormales()
    montar()
    const formulario = await abrirEdicion('iva general')
    await userEvent.click(within(formulario).getByRole('button', { name: 'Guardar impuesto' }))
    await userEvent.click(screen.getByRole('button', { name: 'Volver a editar' }))
    expect(apiPlataformaTributario.cambiarImpuesto).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Guardar impuesto' })).toHaveFocus()
  })

  it('un doble clic real en «Sí, guardar» envía una sola vez', async () => {
    datosNormales()
    let resolver
    apiPlataformaTributario.cambiarImpuesto.mockReturnValue(new Promise((r) => { resolver = r }))
    montar()
    const formulario = await abrirEdicion('iva general')
    await userEvent.click(within(formulario).getByRole('button', { name: 'Guardar impuesto' }))
    await userEvent.dblClick(screen.getByRole('button', { name: 'Sí, guardar' }))
    expect(apiPlataformaTributario.cambiarImpuesto).toHaveBeenCalledTimes(1)
    resolver(IVA)
  })

  it('un tope vacío en un impuesto con tope no se envía y enfoca el campo', async () => {
    datosNormales()
    montar()
    const formulario = await abrirEdicion('exclusión por tope')
    const tope = within(formulario).getByLabelText(/tope en uvt/i)
    await userEvent.clear(tope)
    await userEvent.click(within(formulario).getByRole('button', { name: 'Guardar impuesto' }))
    expect(tope).toHaveAttribute('aria-invalid', 'true')
    expect(tope).toHaveFocus()
    expect(screen.getByText('Escribe el tope en UVT, mayor que cero.')).toBeInTheDocument()
    expect(screen.queryByRole('group', { name: /guardar los cambios/i })).not.toBeInTheDocument()
    expect(apiPlataformaTributario.cambiarImpuesto).not.toHaveBeenCalled()
  })

  it('la tarifa admite coma o punto y hasta cuatro decimales', async () => {
    datosNormales()
    apiPlataformaTributario.cambiarImpuesto.mockResolvedValue(IVA)
    montar()
    const formulario = await abrirEdicion('iva general')
    const tarifa = within(formulario).getByLabelText(/tarifa/i)
    await userEvent.clear(tarifa)
    await userEvent.type(tarifa, '0,1925')
    await userEvent.click(within(formulario).getByRole('button', { name: 'Guardar impuesto' }))
    await userEvent.click(screen.getByRole('button', { name: 'Sí, guardar' }))
    await waitFor(() => expect(apiPlataformaTributario.cambiarImpuesto).toHaveBeenCalledWith(
      'IVA_GENERAL', expect.objectContaining({ tarifa: 0.1925 }),
    ))
  })

  it('una tarifa con cinco decimales no se envía', async () => {
    datosNormales()
    montar()
    const formulario = await abrirEdicion('iva general')
    const tarifa = within(formulario).getByLabelText(/tarifa/i)
    await userEvent.clear(tarifa)
    await userEvent.type(tarifa, '0,19251')
    await userEvent.click(within(formulario).getByRole('button', { name: 'Guardar impuesto' }))
    expect(tarifa).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText(/como máximo 4 decimales/i)).toBeInTheDocument()
    expect(apiPlataformaTributario.cambiarImpuesto).not.toHaveBeenCalled()
  })

  it('un tope con tres decimales no se envía', async () => {
    datosNormales()
    montar()
    const formulario = await abrirEdicion('exclusión por tope')
    const tope = within(formulario).getByLabelText(/tope en uvt/i)
    await userEvent.clear(tope)
    await userEvent.type(tope, '3500.555')
    await userEvent.click(within(formulario).getByRole('button', { name: 'Guardar impuesto' }))
    expect(tope).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText(/como máximo 2 decimales/i)).toBeInTheDocument()
    expect(apiPlataformaTributario.cambiarImpuesto).not.toHaveBeenCalled()
  })

  it('un 400 del servidor con el nombre del campo vuelve al formulario y queda junto a la tarifa', async () => {
    datosNormales()
    apiPlataformaTributario.cambiarImpuesto.mockRejectedValue(new ErrorApi(
      400, 'tarifa: La tarifa es una fracción: 0,1900 para el 19 %, nunca mayor que 1',
    ))
    montar()
    const formulario = await abrirEdicion('iva general')
    await userEvent.click(within(formulario).getByRole('button', { name: 'Guardar impuesto' }))
    await userEvent.click(screen.getByRole('button', { name: 'Sí, guardar' }))
    const tarifa = await screen.findByLabelText(/tarifa/i)
    await waitFor(() => expect(tarifa).toHaveAttribute('aria-invalid', 'true'))
    expect(screen.getByText(/La tarifa es una fracción/)).toBeInTheDocument()
    expect(screen.queryByRole('group', { name: /guardar los cambios/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    await userEvent.type(tarifa, '1')
    expect(tarifa).not.toHaveAttribute('aria-invalid', 'true')
  })

  it('un 403 al guardar un impuesto lo dice', async () => {
    datosNormales()
    apiPlataformaTributario.cambiarImpuesto.mockRejectedValue(new ErrorApi(403, 'Sin permiso para esta operación.'))
    montar()
    const formulario = await abrirEdicion('iva general')
    await userEvent.click(within(formulario).getByRole('button', { name: 'Guardar impuesto' }))
    await userEvent.click(screen.getByRole('button', { name: 'Sí, guardar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Sin permiso para esta operación.')
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
    await userEvent.click(screen.getByRole('button', { name: 'Sí, guardar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('El tope es obligatorio.')
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(screen.queryByRole('form', { name: /editar iva general/i })).not.toBeInTheDocument()
  })
})

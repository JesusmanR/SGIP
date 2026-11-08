/**
 * Sección Inventario (P-18, 42–45, 78), solo del administrador. Se prueba
 * montada en sus rutas reales, con el módulo de API simulado y la clase
 * ErrorApi real: la pantalla decide con `estado` y con el `detail` que trae.
 */
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ErrorApi } from '../../servicios/api.js'
import { apiInventario } from '../../servicios/api/inventario.js'
import Inventario from './index.jsx'

vi.mock('../../servicios/api/inventario.js', () => ({
  apiInventario: {
    movimientos: vi.fn(), kardex: vi.fn(), cuadre: vi.fn(), producto: vi.fn(),
    buscarProductos: vi.fn(), ingresar: vi.fn(), ajustar: vi.fn(),
  },
}))

const base = {
  productoId: 5, codigo: 'AUD-001', producto: 'Auriculares Nova', recepcionId: null,
  usuarioId: 1, usuario: 'Luis Gómez', documento: null, motivo: null,
}
const INGRESO = {
  ...base, movimientoId: 104, tipo: 'INGRESO', cantidad: 24, existenciaAnterior: 9,
  existenciaPosterior: 33, documento: 'OC-0024', recepcionId: 7, fecha: '2026-10-21T10:42:00',
}
const EGRESO = {
  ...base, movimientoId: 103, tipo: 'EGRESO', cantidad: 2, existenciaAnterior: 33,
  existenciaPosterior: 31, documento: 'F-1048', fecha: '2026-10-21T10:20:00',
}
const AJUSTE = {
  ...base, movimientoId: 102, tipo: 'AJUSTE', cantidad: 3, existenciaAnterior: 31,
  existenciaPosterior: 28, motivo: 'Conteo físico', fecha: '2026-10-21T09:15:00',
}
const DEVOLUCION = {
  ...base, movimientoId: 101, tipo: 'DEVOLUCION', cantidad: 1, existenciaAnterior: 28,
  existenciaPosterior: 29, fecha: '2026-10-20T16:48:00',
}

const PRODUCTO = {
  productoId: 5, codigo: 'AUD-001', nombre: 'Auriculares Nova', existencia: 33, categoria: 'Audio',
}
const OTRO = { productoId: 6, codigo: 'TEC-002', nombre: 'Teclado K2', existencia: 4 }
const CUADRA = { productoId: 5, existencia: 33, sumaDeMovimientos: 33, cuadra: true }

function Ubicacion() {
  return <p data-testid="ruta">{useLocation().pathname}</p>
}

function montar(ruta = '/inventario') {
  return render(
    <MemoryRouter initialEntries={[ruta]}>
      <Ubicacion />
      <Routes>
        <Route path="/inventario/*" element={<Inventario />} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  Object.values(apiInventario).forEach((f) => f.mockReset())
})

/** Busca en el selector de producto y elige el resultado con ese nombre. */
async function elegirProducto(texto, nombre) {
  await userEvent.type(screen.getByRole('textbox', { name: /^producto/i }), `${texto}{Enter}`)
  await userEvent.click(await screen.findByRole('button', { name: new RegExp(nombre, 'i') }))
}

describe('movimientos', () => {
  it('muestra la carga y luego una fila por movimiento, con tipo en texto y variación con signo', async () => {
    apiInventario.movimientos.mockResolvedValue([INGRESO, EGRESO, AJUSTE, DEVOLUCION])
    montar()
    expect(screen.getByRole('status')).toHaveTextContent(/cargando/i)
    const tabla = await screen.findByRole('table', { name: /movimientos/i })
    const filas = within(tabla).getAllByRole('row')
    expect(filas).toHaveLength(5)
    expect(within(filas[1]).getByText('Ingreso')).toBeInTheDocument()
    expect(within(filas[1]).getByText('+24')).toBeInTheDocument()
    expect(within(filas[1]).getByText('9 → 33')).toBeInTheDocument()
    expect(within(filas[1]).getByText(/OC-0024/)).toBeInTheDocument()
    expect(within(filas[1]).getByText('Luis Gómez')).toBeInTheDocument()
    expect(within(filas[2]).getByText('Salida')).toBeInTheDocument()
    expect(within(filas[2]).getByText('-2')).toBeInTheDocument()
    // La cantidad de un ajuste es absoluta: el signo sale de las dos existencias.
    expect(within(filas[3]).getByText('Ajuste')).toBeInTheDocument()
    expect(within(filas[3]).getByText('-3')).toBeInTheDocument()
    expect(within(filas[3]).getByText(/Conteo físico/)).toBeInTheDocument()
    expect(within(filas[4]).getByText('Devolución')).toBeInTheDocument()
    expect(within(filas[4]).getByText('+1')).toBeInTheDocument()
    expect(apiInventario.movimientos).toHaveBeenCalledTimes(1)
    expect(apiInventario.movimientos).toHaveBeenCalledWith(100)
  })

  it('dice que los movimientos no se editan ni se borran y cómo corregir', async () => {
    apiInventario.movimientos.mockResolvedValue([INGRESO])
    montar()
    expect(await screen.findByText(/no se editan ni se borran/i)).toHaveTextContent(/otro ajuste/i)
  })

  it('cada producto lleva a su historial y hay accesos a ajustar e ingresar', async () => {
    apiInventario.movimientos.mockResolvedValue([INGRESO])
    montar()
    expect(await screen.findByRole('link', { name: /auriculares nova/i }))
      .toHaveAttribute('href', '/inventario/productos/5')
    expect(screen.getByRole('link', { name: /ajustar existencia/i }))
      .toHaveAttribute('href', '/inventario/ajustes/nuevo')
    expect(screen.getByRole('link', { name: /registrar ingreso/i }))
      .toHaveAttribute('href', '/inventario/ingresos/nuevo')
  })

  it('cambiar cuántos mostrar vuelve a pedir con ese límite', async () => {
    apiInventario.movimientos.mockResolvedValue([INGRESO])
    montar()
    await userEvent.selectOptions(await screen.findByLabelText(/mostrar los últimos/i), '250')
    await waitFor(() => expect(apiInventario.movimientos).toHaveBeenLastCalledWith(250))
    expect(apiInventario.movimientos).toHaveBeenCalledTimes(2)
  })

  it('avisa que la lista no tiene páginas cuando llena el límite', async () => {
    const cien = Array.from({ length: 100 }, (_, i) => ({ ...INGRESO, movimientoId: i + 1 }))
    apiInventario.movimientos.mockResolvedValueOnce(cien)
    montar()
    expect(await screen.findByText(/se muestran los últimos 100/i)).toHaveTextContent(/puede haber más antiguos/i)
  })

  it('con menos filas que el límite no habla de más antiguos', async () => {
    apiInventario.movimientos.mockResolvedValue([INGRESO])
    montar()
    await screen.findByRole('table')
    expect(screen.queryByText(/puede haber más antiguos/i)).not.toBeInTheDocument()
  })

  it('sin movimientos explica qué los genera y ofrece el primer ajuste', async () => {
    apiInventario.movimientos.mockResolvedValue([])
    montar()
    expect(await screen.findByText(/todavía no hay movimientos/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /registrar primer ajuste/i }))
      .toHaveAttribute('href', '/inventario/ajustes/nuevo')
  })

  it('un error dice qué pasó y reintenta', async () => {
    apiInventario.movimientos
      .mockRejectedValueOnce(new ErrorApi(500, 'El servidor no respondió.'))
      .mockResolvedValueOnce([INGRESO])
    montar()
    expect(await screen.findByRole('alert')).toHaveTextContent('El servidor no respondió.')
    await userEvent.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByRole('table')).toBeInTheDocument()
    expect(apiInventario.movimientos).toHaveBeenCalledTimes(2)
  })

  it('un 403 muestra la pantalla de sin permiso', async () => {
    apiInventario.movimientos.mockRejectedValue(new ErrorApi(403, 'no'))
    montar()
    expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument()
  })

  it('elegir un producto en la búsqueda abre su historial', async () => {
    apiInventario.movimientos.mockResolvedValue([INGRESO])
    apiInventario.buscarProductos.mockResolvedValue([PRODUCTO, OTRO])
    apiInventario.producto.mockResolvedValue(PRODUCTO)
    apiInventario.kardex.mockResolvedValue([INGRESO])
    apiInventario.cuadre.mockResolvedValue(CUADRA)
    montar()
    await screen.findByRole('table')
    await userEvent.type(screen.getByRole('textbox', { name: /historial de un producto/i }), 'aur{Enter}')
    await userEvent.click(await screen.findByRole('button', { name: /auriculares nova/i }))
    expect(await screen.findByRole('heading', { level: 1, name: /auriculares nova/i })).toBeInTheDocument()
    expect(apiInventario.buscarProductos).toHaveBeenCalledWith('aur')
  })
})

describe('historial de un producto y cuadre', () => {
  function datos({ cuadre = CUADRA, kardex = [INGRESO, EGRESO] } = {}) {
    apiInventario.producto.mockResolvedValue(PRODUCTO)
    apiInventario.kardex.mockResolvedValue(kardex)
    apiInventario.cuadre.mockResolvedValue(cuadre)
  }

  it('muestra el nombre, el kárdex y que la existencia cuadra, con las dos cifras', async () => {
    datos()
    montar('/inventario/productos/5')
    expect(await screen.findByRole('heading', { level: 1, name: /auriculares nova/i })).toBeInTheDocument()
    expect(apiInventario.kardex).toHaveBeenCalledWith('5', 100)
    expect(apiInventario.cuadre).toHaveBeenCalledWith('5')
    expect(screen.getByRole('table', { name: /historial/i })).toBeInTheDocument()
    const cuadre = screen.getByRole('region', { name: /cuadre/i })
    expect(cuadre).toHaveTextContent(/cuadra/i)
    expect(cuadre).not.toHaveTextContent(/no cuadra/i)
    expect(cuadre).toHaveTextContent('33')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('si no cuadra salta a la vista, dice las cifras y qué pasa', async () => {
    datos({ cuadre: { productoId: 5, existencia: 30, sumaDeMovimientos: 28, cuadra: false } })
    montar('/inventario/productos/5')
    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent(/no cuadra/i)
    expect(alerta).toHaveTextContent('30')
    expect(alerta).toHaveTextContent('28')
    expect(alerta).toHaveTextContent(/2 unidades más/i)
    // Un ajuste mueve las dos cifras por igual: no lo arregla, y no debe prometerlo.
    expect(alerta).toHaveTextContent(/un ajuste no lo arregla/i)
  })

  it('si la existencia es menor que la suma, dice unidades menos', async () => {
    datos({ cuadre: { productoId: 5, existencia: 25, sumaDeMovimientos: 28, cuadra: false } })
    montar('/inventario/productos/5')
    expect(await screen.findByRole('alert')).toHaveTextContent(/3 unidades menos/i)
  })

  it('una existencia negativa se dice como lo que es: mercancía por reponer', async () => {
    datos({ cuadre: { productoId: 5, existencia: -3, sumaDeMovimientos: -3, cuadra: true } })
    montar('/inventario/productos/5')
    expect(await screen.findByText(/debes reponer 3 unidades/i)).toBeInTheDocument()
  })

  it('marca con texto un hueco en la cadena de existencias', async () => {
    // El movimiento más nuevo dice que partió de 40, pero el anterior terminó en 31.
    const roto = { ...EGRESO, existenciaAnterior: 40, existenciaPosterior: 38 }
    const previo = { ...AJUSTE, existenciaPosterior: 31 }
    datos({ kardex: [roto, previo] })
    montar('/inventario/productos/5')
    expect(await screen.findByText(/no sigue al movimiento anterior \(terminó en 31\)/i)).toBeInTheDocument()
  })

  it('una cadena sin huecos no marca nada', async () => {
    datos({ kardex: [EGRESO, INGRESO] })
    montar('/inventario/productos/5')
    await screen.findByRole('table')
    expect(screen.queryByText(/no sigue al movimiento anterior/i)).not.toBeInTheDocument()
  })

  it('un producto sin movimientos lo dice y sigue mostrando el cuadre', async () => {
    datos({ kardex: [], cuadre: { productoId: 5, existencia: 0, sumaDeMovimientos: 0, cuadra: true } })
    montar('/inventario/productos/5')
    expect(await screen.findByText(/todavía no tiene movimientos/i)).toBeInTheDocument()
    expect(screen.getByRole('region', { name: /cuadre/i })).toBeInTheDocument()
  })

  it('ofrece ajustar e ingresar ese mismo producto', async () => {
    datos()
    montar('/inventario/productos/5')
    expect(await screen.findByRole('link', { name: /ajustar existencia/i }))
      .toHaveAttribute('href', '/inventario/ajustes/nuevo?producto=5')
    expect(screen.getByRole('link', { name: /registrar ingreso/i }))
      .toHaveAttribute('href', '/inventario/ingresos/nuevo?producto=5')
  })

  it('un producto que no es de la empresa dice que no se encontró', async () => {
    apiInventario.producto.mockRejectedValue(new ErrorApi(404, 'No se pudo completar la operación.'))
    apiInventario.kardex.mockResolvedValue([])
    apiInventario.cuadre.mockRejectedValue(new ErrorApi(404, 'x'))
    montar('/inventario/productos/99')
    expect(await screen.findByText(/no encontramos ese producto/i)).toBeInTheDocument()
  })

  it('un error reintenta', async () => {
    apiInventario.producto.mockResolvedValue(PRODUCTO)
    apiInventario.kardex.mockRejectedValueOnce(new ErrorApi(500, 'Falló.')).mockResolvedValueOnce([INGRESO])
    apiInventario.cuadre.mockResolvedValue(CUADRA)
    montar('/inventario/productos/5')
    await userEvent.click(await screen.findByRole('button', { name: /reintentar/i }))
    expect(await screen.findByRole('table', { name: /historial/i })).toBeInTheDocument()
  })

  it('un 403 muestra sin permiso', async () => {
    apiInventario.producto.mockRejectedValue(new ErrorApi(403, 'no'))
    apiInventario.kardex.mockRejectedValue(new ErrorApi(403, 'no'))
    apiInventario.cuadre.mockRejectedValue(new ErrorApi(403, 'no'))
    montar('/inventario/productos/5')
    expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument()
  })
})

describe('selector de producto', () => {
  it('busca en el servidor al enviar, no a cada tecla, y con el texto tal cual', async () => {
    apiInventario.buscarProductos.mockResolvedValue([PRODUCTO])
    montar('/inventario/ajustes/nuevo')
    await userEvent.type(screen.getByRole('textbox', { name: /^producto/i }), 'aur')
    expect(apiInventario.buscarProductos).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: /^buscar/i }))
    expect(apiInventario.buscarProductos).toHaveBeenCalledTimes(1)
    expect(apiInventario.buscarProductos).toHaveBeenCalledWith('aur')
    expect(await screen.findByRole('button', { name: /auriculares nova/i })).toHaveTextContent(/AUD-001/)
  })

  it('sin texto no pide nada y dice qué hacer', async () => {
    montar('/inventario/ajustes/nuevo')
    await userEvent.click(screen.getByRole('button', { name: /^buscar/i }))
    expect(apiInventario.buscarProductos).not.toHaveBeenCalled()
    expect(screen.getAllByText(/escribe el nombre o el código/i).length).toBeGreaterThan(0)
  })

  it('sin coincidencias lo dice', async () => {
    apiInventario.buscarProductos.mockResolvedValue([])
    montar('/inventario/ajustes/nuevo')
    await userEvent.type(screen.getByRole('textbox', { name: /^producto/i }), 'zzz{Enter}')
    expect(await screen.findByText(/no hay productos con esa búsqueda/i)).toBeInTheDocument()
  })

  it('si la búsqueda falla dice qué pasó y reintenta', async () => {
    apiInventario.buscarProductos
      .mockRejectedValueOnce(new ErrorApi(500, 'Falló la búsqueda.'))
      .mockResolvedValueOnce([PRODUCTO])
    montar('/inventario/ajustes/nuevo')
    await userEvent.type(screen.getByRole('textbox', { name: /^producto/i }), 'aur{Enter}')
    expect(await screen.findByRole('alert')).toHaveTextContent('Falló la búsqueda.')
    await userEvent.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByRole('button', { name: /auriculares nova/i })).toBeInTheDocument()
  })

  it('si la búsqueda da 403 muestra sin permiso', async () => {
    apiInventario.buscarProductos.mockRejectedValue(new ErrorApi(403, 'no'))
    montar('/inventario/ajustes/nuevo')
    await userEvent.type(screen.getByRole('textbox', { name: /^producto/i }), 'aur{Enter}')
    expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument()
  })

  it('si llena el tope de resultados pide afinar la búsqueda', async () => {
    const diez = Array.from({ length: 10 }, (_, i) => ({ ...PRODUCTO, productoId: i + 1, nombre: `Pieza ${i + 1}` }))
    apiInventario.buscarProductos.mockResolvedValue(diez)
    montar('/inventario/ajustes/nuevo')
    await userEvent.type(screen.getByRole('textbox', { name: /^producto/i }), 'pieza{Enter}')
    expect(await screen.findByText(/primeros 10/i)).toBeInTheDocument()
  })
})

describe('ajuste', () => {
  async function llenar({ contada = '30', motivo = 'Conteo físico' } = {}) {
    await elegirProducto('aur', 'auriculares nova')
    if (contada !== null) await userEvent.type(screen.getByLabelText(/cantidad contada/i), contada)
    // Pegado y no tecleado: escribir 201 letras una a una tarda segundos y,
    // con la máquina cargada, pasa del límite de 5 s de la prueba.
    if (motivo !== null) {
      await userEvent.click(screen.getByLabelText(/motivo del ajuste/i))
      await userEvent.paste(motivo)
    }
  }

  beforeEach(() => {
    apiInventario.buscarProductos.mockResolvedValue([PRODUCTO])
  })

  it('con el formulario vacío no envía y explica junto al campo', async () => {
    montar('/inventario/ajustes/nuevo')
    await userEvent.click(screen.getByRole('button', { name: /revisar ajuste/i }))
    expect(screen.getByText(/elige el producto/i)).toBeInTheDocument()
    expect(apiInventario.ajustar).not.toHaveBeenCalled()
    expect(screen.queryByRole('region', { name: /revisa antes de confirmar/i })).not.toBeInTheDocument()
  })

  it('exige cantidad entera y motivo, y no pasa a la revisión', async () => {
    montar('/inventario/ajustes/nuevo')
    await elegirProducto('aur', 'auriculares nova')
    await userEvent.click(screen.getByRole('button', { name: /revisar ajuste/i }))
    expect(screen.getByLabelText(/cantidad contada/i)).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText(/cantidad contada/i)).toHaveFocus()
    expect(screen.getByText(/escribe cuántas unidades cuentas/i)).toBeInTheDocument()
    expect(screen.getByText(/escribe el motivo/i)).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText(/cantidad contada/i), '2,5')
    await userEvent.type(screen.getByLabelText(/motivo del ajuste/i), 'x')
    await userEvent.click(screen.getByRole('button', { name: /revisar ajuste/i }))
    expect(screen.getByText(/número entero, sin puntos, comas ni signos/i)).toBeInTheDocument()
    expect(apiInventario.ajustar).not.toHaveBeenCalled()
  })

  it('el cero es un conteo válido: estante vacío', async () => {
    montar('/inventario/ajustes/nuevo')
    await llenar({ contada: '0' })
    await userEvent.click(screen.getByRole('button', { name: /revisar ajuste/i }))
    expect(await screen.findByRole('region', { name: /revisa antes de confirmar/i })).toBeInTheDocument()
  })

  it('rechaza un motivo de más de 200 caracteres', async () => {
    montar('/inventario/ajustes/nuevo')
    await llenar({ motivo: 'a'.repeat(201) })
    await userEvent.click(screen.getByRole('button', { name: /revisar ajuste/i }))
    expect(screen.getByText(/no puede pasar de 200 caracteres/i)).toBeInTheDocument()
  })

  it('al elegir el producto se ve su existencia actual', async () => {
    montar('/inventario/ajustes/nuevo')
    await elegirProducto('aur', 'auriculares nova')
    expect(screen.getByText(/existencia actual/i).closest('div')).toHaveTextContent('33')
    expect(screen.getByRole('button', { name: /cambiar producto/i })).toBeInTheDocument()
  })

  it('la revisión muestra existencia actual, cambio y resultante como vista previa, sin enviar nada', async () => {
    montar('/inventario/ajustes/nuevo')
    await llenar()
    await userEvent.click(screen.getByRole('button', { name: /revisar ajuste/i }))
    const revision = await screen.findByRole('region', { name: /revisa antes de confirmar/i })
    expect(within(revision).getByText('Existencia actual').nextSibling).toHaveTextContent('33')
    expect(within(revision).getByText('Cambio').nextSibling).toHaveTextContent('-3')
    expect(within(revision).getByText(/existencia resultante/i).nextSibling).toHaveTextContent('30')
    expect(revision).toHaveTextContent(/vista previa/i)
    expect(revision).toHaveTextContent(/conteo físico/i)
    expect(revision).toHaveTextContent(/no se puede editar ni borrar/i)
    expect(apiInventario.ajustar).not.toHaveBeenCalled()
  })

  it('corregir vuelve al formulario con lo escrito', async () => {
    montar('/inventario/ajustes/nuevo')
    await llenar()
    await userEvent.click(screen.getByRole('button', { name: /revisar ajuste/i }))
    await userEvent.click(await screen.findByRole('button', { name: /corregir/i }))
    expect(screen.getByLabelText(/cantidad contada/i)).toHaveValue('30')
    expect(screen.getByLabelText(/motivo del ajuste/i)).toHaveValue('Conteo físico')
  })

  it('si el conteo es igual a la existencia dice que no hay nada que ajustar y no ofrece confirmar', async () => {
    montar('/inventario/ajustes/nuevo')
    await llenar({ contada: '33' })
    await userEvent.click(screen.getByRole('button', { name: /revisar ajuste/i }))
    expect(await screen.findByText(/no hay nada que ajustar/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /confirmar ajuste/i })).not.toBeInTheDocument()
  })

  it('confirma enviando el saldo contado y muestra la existencia que devuelve el servidor', async () => {
    apiInventario.ajustar.mockResolvedValue({ ...AJUSTE, existenciaAnterior: 33, existenciaPosterior: 30 })
    montar('/inventario/ajustes/nuevo')
    await llenar()
    await userEvent.type(screen.getByLabelText(/documento/i), 'Acta 12')
    await userEvent.click(screen.getByRole('button', { name: /revisar ajuste/i }))
    await userEvent.click(await screen.findByRole('button', { name: /confirmar ajuste/i }))
    expect(apiInventario.ajustar).toHaveBeenCalledTimes(1)
    expect(apiInventario.ajustar).toHaveBeenCalledWith({
      productoId: 5, existenciaContada: 30, motivo: 'Conteo físico', documento: 'Acta 12',
    })
    expect(await screen.findByText(/auriculares nova ahora tiene 30 unidades/i)).toBeInTheDocument()
    const listo = screen.getByRole('region', { name: /ajuste registrado/i })
    expect(within(listo).getByText(/existencia resultante/i).nextSibling).toHaveTextContent('30')
    expect(listo).not.toHaveTextContent(/mientras tanto/i)
    expect(screen.getByRole('link', { name: /ver el historial/i })).toHaveAttribute('href', '/inventario/productos/5')
  })

  it('la existencia del servidor manda sobre la vista previa y avisa si el saldo se movió', async () => {
    apiInventario.ajustar.mockResolvedValue({ ...AJUSTE, existenciaAnterior: 31, existenciaPosterior: 30 })
    montar('/inventario/ajustes/nuevo')
    await llenar()
    await userEvent.click(screen.getByRole('button', { name: /revisar ajuste/i }))
    await userEvent.click(await screen.findByRole('button', { name: /confirmar ajuste/i }))
    const listo = await screen.findByRole('region', { name: /ajuste registrado/i })
    expect(listo).toHaveTextContent(/mientras tanto/i)
    expect(within(listo).getByText('Existencia anterior').nextSibling).toHaveTextContent('31')
    expect(within(listo).getByText('Cambio').nextSibling).toHaveTextContent('-1')
  })

  it('un 204 dice que el conteo coincidía y no se registró nada', async () => {
    apiInventario.ajustar.mockResolvedValue(null)
    montar('/inventario/ajustes/nuevo')
    await llenar()
    await userEvent.click(screen.getByRole('button', { name: /revisar ajuste/i }))
    await userEvent.click(await screen.findByRole('button', { name: /confirmar ajuste/i }))
    expect(await screen.findByText(/no se registró ningún movimiento/i)).toBeInTheDocument()
    expect(screen.queryByText(/ajuste registrado/i)).not.toBeInTheDocument()
  })

  it('no duplica el envío mientras espera al servidor', async () => {
    let resolver
    apiInventario.ajustar.mockReturnValue(new Promise((r) => { resolver = r }))
    montar('/inventario/ajustes/nuevo')
    await llenar()
    await userEvent.click(screen.getByRole('button', { name: /revisar ajuste/i }))
    await userEvent.click(await screen.findByRole('button', { name: /confirmar ajuste/i }))
    expect(screen.getByRole('button', { name: /registrando/i })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: /registrando/i }))
    expect(apiInventario.ajustar).toHaveBeenCalledTimes(1)
    resolver({ ...AJUSTE, existenciaAnterior: 33, existenciaPosterior: 30 })
    expect(await screen.findByRole('region', { name: /ajuste registrado/i })).toBeInTheDocument()
  })

  it('un 400 del servidor muestra su detalle y deja reintentar', async () => {
    apiInventario.ajustar
      .mockRejectedValueOnce(new ErrorApi(400, 'motivo: El motivo no puede pasar de 200 caracteres'))
      .mockResolvedValueOnce({ ...AJUSTE, existenciaAnterior: 33, existenciaPosterior: 30 })
    montar('/inventario/ajustes/nuevo')
    await llenar()
    await userEvent.click(screen.getByRole('button', { name: /revisar ajuste/i }))
    await userEvent.click(await screen.findByRole('button', { name: /confirmar ajuste/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent('motivo: El motivo no puede pasar de 200 caracteres')
    expect(screen.getByRole('button', { name: /confirmar ajuste/i })).toBeEnabled()
    await userEvent.click(screen.getByRole('button', { name: /confirmar ajuste/i }))
    expect(await screen.findByRole('region', { name: /ajuste registrado/i })).toBeInTheDocument()
  })

  it('un 404 dice que el producto ya no está en el catálogo', async () => {
    apiInventario.ajustar.mockRejectedValue(new ErrorApi(404, 'No se pudo completar la operación.'))
    montar('/inventario/ajustes/nuevo')
    await llenar()
    await userEvent.click(screen.getByRole('button', { name: /revisar ajuste/i }))
    await userEvent.click(await screen.findByRole('button', { name: /confirmar ajuste/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/ya no está en tu catálogo/i)
  })

  it('un 403 al confirmar lo dice con la voz del servidor', async () => {
    apiInventario.ajustar.mockRejectedValue(new ErrorApi(403, 'Tu rol no tiene permiso para hacer esto.'))
    montar('/inventario/ajustes/nuevo')
    await llenar()
    await userEvent.click(screen.getByRole('button', { name: /revisar ajuste/i }))
    await userEvent.click(await screen.findByRole('button', { name: /confirmar ajuste/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/no tiene permiso/i)
  })

  it('con ?producto= parte con ese producto elegido', async () => {
    apiInventario.producto.mockResolvedValue(PRODUCTO)
    montar('/inventario/ajustes/nuevo?producto=5')
    expect(await screen.findByText(/auriculares nova/i)).toBeInTheDocument()
    expect(apiInventario.producto).toHaveBeenCalledWith('5')
    expect(apiInventario.producto).toHaveBeenCalledTimes(1)
    expect(screen.getByText(/existencia actual/i).closest('div')).toHaveTextContent('33')
  })

  it('con ?producto= que da 403 muestra sin permiso, y con 404 que no existe', async () => {
    apiInventario.producto.mockRejectedValueOnce(new ErrorApi(403, 'no'))
    const primera = montar('/inventario/ajustes/nuevo?producto=5')
    expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument()
    primera.unmount()
    apiInventario.producto.mockRejectedValueOnce(new ErrorApi(404, 'x'))
    montar('/inventario/ajustes/nuevo?producto=99')
    expect(await screen.findByText(/no encontramos ese producto/i)).toBeInTheDocument()
  })

  it('con ?producto= que falla reintenta', async () => {
    apiInventario.producto.mockRejectedValueOnce(new ErrorApi(500, 'Falló.')).mockResolvedValueOnce(PRODUCTO)
    montar('/inventario/ajustes/nuevo?producto=5')
    await userEvent.click(await screen.findByRole('button', { name: /reintentar/i }))
    expect(await screen.findByLabelText(/cantidad contada/i)).toBeInTheDocument()
  })

  it('dice que un error se corrige con otro ajuste, sin sermón', async () => {
    montar('/inventario/ajustes/nuevo')
    expect(screen.getByText(/no se editan ni se borran/i)).toBeInTheDocument()
  })
})

describe('ingreso', () => {
  beforeEach(() => {
    apiInventario.buscarProductos.mockResolvedValue([PRODUCTO])
  })

  it('exige producto y una cantidad entera de al menos 1, y no envía', async () => {
    montar('/inventario/ingresos/nuevo')
    await userEvent.click(screen.getByRole('button', { name: /revisar ingreso/i }))
    expect(screen.getByText(/elige el producto/i)).toBeInTheDocument()
    await elegirProducto('aur', 'auriculares nova')
    await userEvent.type(screen.getByLabelText(/cantidad que entra/i), '0')
    await userEvent.click(screen.getByRole('button', { name: /revisar ingreso/i }))
    expect(screen.getByText(/al menos una unidad/i)).toBeInTheDocument()
    expect(apiInventario.ingresar).not.toHaveBeenCalled()
  })

  it('revisa, confirma y muestra la existencia que devuelve el servidor', async () => {
    apiInventario.ingresar.mockResolvedValue({ ...INGRESO, existenciaAnterior: 33, existenciaPosterior: 57 })
    montar('/inventario/ingresos/nuevo')
    await elegirProducto('aur', 'auriculares nova')
    await userEvent.type(screen.getByLabelText(/cantidad que entra/i), '24')
    await userEvent.type(screen.getByLabelText(/documento/i), 'OC-0024')
    await userEvent.click(screen.getByRole('button', { name: /revisar ingreso/i }))
    const revision = await screen.findByRole('region', { name: /revisa antes de confirmar/i })
    expect(within(revision).getByText('Cambio').nextSibling).toHaveTextContent('+24')
    expect(within(revision).getByText(/existencia resultante/i).nextSibling).toHaveTextContent('57')
    expect(apiInventario.ingresar).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: /confirmar ingreso/i }))
    expect(apiInventario.ingresar).toHaveBeenCalledWith({
      productoId: 5, cantidad: 24, documento: 'OC-0024', motivo: null,
    })
    expect(await screen.findByText(/ahora tiene 57 unidades/i)).toBeInTheDocument()
    expect(screen.getByRole('region', { name: /ingreso registrado/i })).toBeInTheDocument()
  })

  it('un 400 del servidor muestra su detalle', async () => {
    apiInventario.ingresar.mockRejectedValue(new ErrorApi(400, 'cantidad: La cantidad tiene que ser al menos una unidad'))
    montar('/inventario/ingresos/nuevo')
    await elegirProducto('aur', 'auriculares nova')
    await userEvent.type(screen.getByLabelText(/cantidad que entra/i), '5')
    await userEvent.click(screen.getByRole('button', { name: /revisar ingreso/i }))
    await userEvent.click(await screen.findByRole('button', { name: /confirmar ingreso/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent('La cantidad tiene que ser al menos una unidad')
  })

  it('no duplica el envío', async () => {
    apiInventario.ingresar.mockReturnValue(new Promise(() => {}))
    montar('/inventario/ingresos/nuevo')
    await elegirProducto('aur', 'auriculares nova')
    await userEvent.type(screen.getByLabelText(/cantidad que entra/i), '5')
    await userEvent.click(screen.getByRole('button', { name: /revisar ingreso/i }))
    await userEvent.click(await screen.findByRole('button', { name: /confirmar ingreso/i }))
    await userEvent.click(screen.getByRole('button', { name: /registrando/i }))
    expect(apiInventario.ingresar).toHaveBeenCalledTimes(1)
  })
})

describe('foco, ids raros y búsqueda (ronda 1)', () => {
  beforeEach(() => {
    apiInventario.buscarProductos.mockResolvedValue([PRODUCTO])
  })

  async function hastaRevision() {
    montar('/inventario/ajustes/nuevo')
    await elegirProducto('aur', 'auriculares nova')
    await userEvent.type(screen.getByLabelText(/cantidad contada/i), '30')
    await userEvent.type(screen.getByLabelText(/motivo del ajuste/i), 'Conteo físico')
    await userEvent.click(screen.getByRole('button', { name: /revisar ajuste/i }))
  }

  it('al entrar en la revisión el foco va al título del paso', async () => {
    await hastaRevision()
    const titulo = await screen.findByRole('heading', { name: /revisa antes de confirmar/i })
    await waitFor(() => expect(titulo).toHaveFocus())
  })

  it('al terminar, el foco va al título de «listo»', async () => {
    apiInventario.ajustar.mockResolvedValue({ ...AJUSTE, existenciaAnterior: 33, existenciaPosterior: 30 })
    await hastaRevision()
    await userEvent.click(await screen.findByRole('button', { name: /confirmar ajuste/i }))
    const titulo = await screen.findByRole('heading', { name: /ajuste registrado/i })
    await waitFor(() => expect(titulo).toHaveFocus())
  })

  it('un error al confirmar lleva el foco al aviso', async () => {
    apiInventario.ajustar.mockRejectedValue(new ErrorApi(400, 'motivo: Algo falló'))
    await hastaRevision()
    await userEvent.click(await screen.findByRole('button', { name: /confirmar ajuste/i }))
    const alerta = await screen.findByRole('alert')
    await waitFor(() => expect(alerta.parentElement).toHaveFocus())
  })

  it('«Corregir» devuelve el foco al primer campo del formulario', async () => {
    await hastaRevision()
    await userEvent.click(await screen.findByRole('button', { name: /corregir/i }))
    await waitFor(() => expect(screen.getByLabelText(/cantidad contada/i)).toHaveFocus())
  })

  it('elegir producto lleva el foco a la cantidad, y «Cambiar producto» al buscador', async () => {
    montar('/inventario/ajustes/nuevo')
    await elegirProducto('aur', 'auriculares nova')
    await waitFor(() => expect(screen.getByLabelText(/cantidad contada/i)).toHaveFocus())
    await userEvent.click(screen.getByRole('button', { name: /cambiar producto/i }))
    await waitFor(() => expect(screen.getByRole('textbox', { name: /^producto/i })).toHaveFocus())
  })

  it('«Registrar otro ajuste» devuelve el foco al buscador', async () => {
    apiInventario.ajustar.mockResolvedValue({ ...AJUSTE, existenciaAnterior: 33, existenciaPosterior: 30 })
    await hastaRevision()
    await userEvent.click(await screen.findByRole('button', { name: /confirmar ajuste/i }))
    await userEvent.click(await screen.findByRole('button', { name: /registrar otro ajuste/i }))
    await waitFor(() => expect(screen.getByRole('textbox', { name: /^producto/i })).toHaveFocus())
    expect(screen.getByLabelText(/cantidad contada/i)).toHaveValue('')
  })

  it.each(['abc', '0', '-5', '1.5', '1234567890', '99999999999999999999'])(
    '?producto=%s no se envía: dice que no se encontró',
    async (basura) => {
      montar(`/inventario/ajustes/nuevo?producto=${basura}`)
      expect(await screen.findByText(/no encontramos ese producto/i)).toBeInTheDocument()
      expect(apiInventario.producto).not.toHaveBeenCalled()
    },
  )

  it.each(['abc', '0', '1234567890'])('/productos/%s no pide nada: dice que no se encontró', async (basura) => {
    montar(`/inventario/productos/${basura}`)
    expect(await screen.findByText(/no encontramos ese producto/i)).toBeInTheDocument()
    expect(apiInventario.producto).not.toHaveBeenCalled()
    expect(apiInventario.kardex).not.toHaveBeenCalled()
    expect(apiInventario.cuadre).not.toHaveBeenCalled()
  })

  it('un id de nueve cifras sí se pide', async () => {
    apiInventario.producto.mockResolvedValue(PRODUCTO)
    apiInventario.kardex.mockResolvedValue([])
    apiInventario.cuadre.mockResolvedValue(CUADRA)
    montar('/inventario/productos/999999999')
    await screen.findByRole('heading', { level: 1, name: /auriculares nova/i })
    expect(apiInventario.producto).toHaveBeenCalledWith('999999999')
  })

  it('una subruta desconocida lleva a /inventario', async () => {
    apiInventario.movimientos.mockResolvedValue([INGRESO])
    montar('/inventario/nada/de/esto')
    await screen.findByRole('table')
    expect(screen.getByTestId('ruta').textContent).toBe('/inventario')
  })

  it('pulsar Buscar otra vez con el mismo texto no repite la petición', async () => {
    montar('/inventario/ajustes/nuevo')
    await userEvent.type(screen.getByRole('textbox', { name: /^producto/i }), 'aur')
    await userEvent.click(screen.getByRole('button', { name: /^buscar/i }))
    await screen.findByRole('button', { name: /auriculares nova/i })
    await userEvent.click(screen.getByRole('button', { name: /^buscar/i }))
    expect(apiInventario.buscarProductos).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: /auriculares nova/i })).toBeInTheDocument()
  })

  it('al editar el texto sin buscar, los resultados viejos desaparecen', async () => {
    montar('/inventario/ajustes/nuevo')
    await userEvent.type(screen.getByRole('textbox', { name: /^producto/i }), 'aur{Enter}')
    await screen.findByRole('button', { name: /auriculares nova/i })
    await userEvent.type(screen.getByRole('textbox', { name: /^producto/i }), 'x')
    expect(screen.queryByRole('button', { name: /auriculares nova/i })).not.toBeInTheDocument()
  })

  it('«1.000» se rechaza con un mensaje que dice sin puntos', async () => {
    montar('/inventario/ajustes/nuevo')
    await elegirProducto('aur', 'auriculares nova')
    await userEvent.type(screen.getByLabelText(/cantidad contada/i), '1.000')
    await userEvent.type(screen.getByLabelText(/motivo del ajuste/i), 'Conteo')
    await userEvent.click(screen.getByRole('button', { name: /revisar ajuste/i }))
    expect(screen.getByText(/sin puntos, comas ni signos/i)).toBeInTheDocument()
    expect(apiInventario.ajustar).not.toHaveBeenCalled()
  })

  it('la guarda impide un segundo envío aunque el botón aún no esté deshabilitado', async () => {
    apiInventario.ajustar.mockReturnValue(new Promise(() => {}))
    await hastaRevision()
    const boton = await screen.findByRole('button', { name: /confirmar ajuste/i })
    act(() => { boton.click(); boton.click() })
    expect(apiInventario.ajustar).toHaveBeenCalledTimes(1)
  })
})

describe('ingreso: errores y reintento (ronda 1)', () => {
  beforeEach(() => {
    apiInventario.buscarProductos.mockResolvedValue([PRODUCTO])
  })

  async function hastaRevision() {
    montar('/inventario/ingresos/nuevo')
    await elegirProducto('aur', 'auriculares nova')
    await userEvent.type(screen.getByLabelText(/cantidad que entra/i), '5')
    await userEvent.click(screen.getByRole('button', { name: /revisar ingreso/i }))
  }

  it('un 404 dice que el producto ya no está en el catálogo', async () => {
    apiInventario.ingresar.mockRejectedValue(new ErrorApi(404, 'x'))
    await hastaRevision()
    await userEvent.click(await screen.findByRole('button', { name: /confirmar ingreso/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/ya no está en tu catálogo/i)
  })

  it('un 403 lo dice con la voz del servidor', async () => {
    apiInventario.ingresar.mockRejectedValue(new ErrorApi(403, 'Tu rol no tiene permiso para hacer esto.'))
    await hastaRevision()
    await userEvent.click(await screen.findByRole('button', { name: /confirmar ingreso/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/no tiene permiso/i)
  })

  it('tras un error se puede reintentar y sale bien', async () => {
    apiInventario.ingresar
      .mockRejectedValueOnce(new ErrorApi(500, 'Falló.'))
      .mockResolvedValueOnce({ ...INGRESO, existenciaAnterior: 33, existenciaPosterior: 38 })
    await hastaRevision()
    await userEvent.click(await screen.findByRole('button', { name: /confirmar ingreso/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Falló.')
    await userEvent.click(screen.getByRole('button', { name: /confirmar ingreso/i }))
    expect(await screen.findByRole('region', { name: /ingreso registrado/i })).toBeInTheDocument()
    expect(apiInventario.ingresar).toHaveBeenCalledTimes(2)
  })

  it('«Registrar otro ingreso» vuelve a un formulario vacío', async () => {
    apiInventario.ingresar.mockResolvedValue({ ...INGRESO, existenciaAnterior: 33, existenciaPosterior: 38 })
    await hastaRevision()
    await userEvent.click(await screen.findByRole('button', { name: /confirmar ingreso/i }))
    await userEvent.click(await screen.findByRole('button', { name: /registrar otro ingreso/i }))
    expect(screen.getByRole('textbox', { name: /^producto/i })).toBeInTheDocument()
    expect(screen.getByLabelText(/cantidad que entra/i)).toHaveValue('')
  })

  it('con ?producto= parte con ese producto; 403, 404 y error reintentable', async () => {
    apiInventario.producto.mockResolvedValueOnce(PRODUCTO)
    const a = montar('/inventario/ingresos/nuevo?producto=5')
    expect(await screen.findByText(/auriculares nova/i)).toBeInTheDocument()
    a.unmount()
    apiInventario.producto.mockRejectedValueOnce(new ErrorApi(403, 'no'))
    const b = montar('/inventario/ingresos/nuevo?producto=5')
    expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument()
    b.unmount()
    apiInventario.producto.mockRejectedValueOnce(new ErrorApi(404, 'x'))
    const c = montar('/inventario/ingresos/nuevo?producto=5')
    expect(await screen.findByText(/no encontramos ese producto/i)).toBeInTheDocument()
    c.unmount()
    apiInventario.producto.mockRejectedValueOnce(new ErrorApi(500, 'Falló.')).mockResolvedValueOnce(PRODUCTO)
    montar('/inventario/ingresos/nuevo?producto=5')
    await userEvent.click(await screen.findByRole('button', { name: /reintentar/i }))
    expect(await screen.findByLabelText(/cantidad que entra/i)).toBeInTheDocument()
  })
})

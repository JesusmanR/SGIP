/**
 * Sección Reglas de ganancia. Se prueba montada en sus rutas reales, con el
 * módulo de API simulado y la clase ErrorApi real. La fecha de «hoy» se fija
 * (solo Date: con relojes falsos completos userEvent se cuelga) a las 9 de la
 * noche, hora local, para que un `toISOString` ingenuo daría el día siguiente.
 */
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ErrorApi } from '../../servicios/api.js'
import { apiPrecios } from '../../servicios/api/precios.js'
import Precios from './index.jsx'

vi.mock('../../servicios/api/precios.js', () => ({
  apiPrecios: {
    listar: vi.fn(), ver: vi.fn(), alcance: vi.fn(), simular: vi.fn(), crear: vi.fn(),
    corregir: vi.fn(), reemplazar: vi.fn(), cerrar: vi.fn(), categorias: vi.fn(), proveedores: vi.fn(),
  },
}))

const BASE = {
  reglaId: 1, categoriaId: 3, categoria: 'Computadores', proveedorId: null, proveedor: null,
  formaPago: 'CONTADO', formaCalculo: 'SOBRE_COSTO', factor: 1.2,
  vigenteDesde: '2026-01-01', vigenteHasta: null, especificidad: 1, vigente: true,
}
const VIGENTE = BASE
const GENERAL = {
  ...BASE, reglaId: 2, categoriaId: null, categoria: null, formaPago: 'CREDITO',
  formaCalculo: 'SOBRE_PRECIO', factor: 1.25, especificidad: 0,
}
const PROXIMA = {
  ...BASE, reglaId: 3, categoriaId: null, categoria: null, proveedorId: 9, proveedor: 'Mayorista Tech',
  factor: 1.0125, vigenteDesde: '2026-12-01', vigente: false,
}
const CERRADA = {
  ...BASE, reglaId: 4, categoria: 'Antigua', vigenteHasta: '2026-06-30', vigente: false, factor: 1.3,
}
const DESDE_HOY = { ...BASE, reglaId: 5, categoria: 'Accesorios', vigenteDesde: '2026-11-06' }
const CATEGORIAS = [{ id: 3, nombre: 'Computadores' }, { id: 4, nombre: 'Accesorios' }]
const PROVEEDORES = [{ id: 9, razonSocial: 'Mayorista Tech' }]

function Sonda() {
  return <span data-testid="estado-de-navegacion">{useLocation().state ? "con-estado" : "null"}</span>
}

function montar(ruta = '/precios') {
  return render(
    <MemoryRouter initialEntries={[ruta]}>
      <Sonda />
      <Routes>
        <Route path="/precios/*" element={<Precios />} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  Object.values(apiPrecios).forEach((f) => f.mockReset())
  apiPrecios.categorias.mockResolvedValue(CATEGORIAS)
  apiPrecios.proveedores.mockResolvedValue(PROVEEDORES)
  apiPrecios.alcance.mockResolvedValue({ categoriaId: null, proveedorId: null, productos: 12 })
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(2026, 10, 6, 21, 0, 0))
})

afterEach(() => {
  vi.useRealTimers()
})

describe('lista de reglas', () => {
  it('muestra la carga y luego las reglas que rigen o van a regir, con su estado en texto', async () => {
    apiPrecios.listar.mockResolvedValue([VIGENTE, GENERAL, PROXIMA, CERRADA])
    montar()
    expect(screen.getByRole('status')).toHaveTextContent(/cargando/i)
    const tabla = await screen.findByRole('table', { name: /reglas de ganancia/i })
    const filas = within(tabla).getAllByRole('row')
    expect(filas).toHaveLength(4) // cabecera + 3: la cerrada no se ve
    expect(within(filas[1]).getByText('Categoría Computadores')).toBeInTheDocument()
    expect(within(filas[1]).getByText('Contado')).toBeInTheDocument()
    expect(within(filas[1]).getByText('Recargo sobre el costo')).toBeInTheDocument()
    expect(within(filas[1]).getByText(/20,00\s%/)).toBeInTheDocument()
    expect(within(filas[1]).getByText('Rige hoy')).toBeInTheDocument()
    expect(within(filas[2]).getByText('Todos los productos')).toBeInTheDocument()
    expect(within(filas[2]).getByText('Crédito')).toBeInTheDocument()
    expect(within(filas[2]).getByText('Ganancia sobre el precio de venta')).toBeInTheDocument()
    expect(within(filas[2]).getByText(/25,00\s%/)).toBeInTheDocument()
    expect(within(filas[3]).getByText('Proveedor Mayorista Tech')).toBeInTheDocument()
    expect(within(filas[3]).getByText(/1,25\s%/)).toBeInTheDocument()
    expect(within(filas[3]).getByText(/empieza el 1 dic/i)).toBeInTheDocument()
    expect(apiPrecios.listar).toHaveBeenCalledTimes(1)
  })

  it('las cerradas se ven solo si se piden', async () => {
    apiPrecios.listar.mockResolvedValue([VIGENTE, CERRADA])
    montar()
    await screen.findByRole('table')
    expect(screen.queryByText('Categoría Antigua')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('checkbox', { name: /cerradas/i }))
    const fila = screen.getByText('Categoría Antigua').closest('tr')
    expect(within(fila).getByText('Cerrada')).toBeInTheDocument()
    expect(within(fila).queryByRole('button', { name: /cerrar/i })).not.toBeInTheDocument()
  })

  it('dice siempre que las reglas son opcionales y que no alcanzan a los productos con precio propio', async () => {
    apiPrecios.listar.mockResolvedValue([VIGENTE])
    montar()
    await screen.findByRole('table')
    expect(screen.getByText(/son opcionales/i)).toBeInTheDocument()
    expect(screen.getByText(/precio o porcentaje propio/i)).toBeInTheDocument()
  })

  it('sin reglas explica que está bien y dónde se escribe el precio', async () => {
    apiPrecios.listar.mockResolvedValue([])
    montar()
    expect(await screen.findByText(/no hay reglas generales/i)).toBeInTheDocument()
    expect(screen.getByText(/en cada producto/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /nueva regla/i })).toHaveAttribute('href', '/precios/nueva')
  })

  it('un error dice qué pasó y reintenta', async () => {
    apiPrecios.listar
      .mockRejectedValueOnce(new ErrorApi(500, 'El servidor no respondió.'))
      .mockResolvedValueOnce([VIGENTE])
    montar()
    expect(await screen.findByRole('alert')).toHaveTextContent('El servidor no respondió.')
    await userEvent.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByRole('table')).toBeInTheDocument()
    expect(apiPrecios.listar).toHaveBeenCalledTimes(2)
  })

  it('un 403 muestra la pantalla de sin permiso y no ofrece crear', async () => {
    apiPrecios.listar.mockRejectedValue(new ErrorApi(403, 'no'))
    montar()
    expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /nueva regla/i })).not.toBeInTheDocument()
  })

  it('ofrece las acciones que corresponden a cada regla', async () => {
    apiPrecios.listar.mockResolvedValue([VIGENTE, PROXIMA])
    montar()
    await screen.findByRole('table')
    const rige = screen.getByText('Categoría Computadores').closest('tr')
    expect(within(rige).getByRole('link', { name: /reemplazar/i })).toHaveAttribute('href', '/precios/1/reemplazar')
    expect(within(rige).getByRole('button', { name: /cerrar/i })).toBeInTheDocument()
    expect(within(rige).queryByRole('link', { name: /corregir/i })).not.toBeInTheDocument()
    const proxima = screen.getByText('Proveedor Mayorista Tech').closest('tr')
    expect(within(proxima).getByRole('link', { name: /corregir/i })).toHaveAttribute('href', '/precios/3/corregir')
    expect(within(proxima).queryByRole('button', { name: /cerrar/i })).not.toBeInTheDocument()
  })
})

describe('cerrar una regla', () => {
  it('pide confirmar, dice qué pasa y cuántos productos alcanza, y cierra sin fecha', async () => {
    apiPrecios.listar.mockResolvedValue([VIGENTE])
    apiPrecios.cerrar.mockResolvedValue({ ...VIGENTE, vigenteHasta: '2026-11-05', vigente: false })
    montar()
    await screen.findByRole('table')
    await userEvent.click(screen.getByRole('button', { name: /cerrar/i }))
    const caja = await screen.findByRole('group', { name: /cerrar la regla/i })
    expect(caja).toHaveFocus()
    expect(within(caja).getByText(/deja de aplicar desde hoy/i)).toBeInTheDocument()
    expect(within(caja).getByText(/no se borra/i)).toBeInTheDocument()
    expect(await within(caja).findByText(/12 productos/i)).toBeInTheDocument()
    expect(apiPrecios.alcance).toHaveBeenCalledWith(3, null)
    expect(apiPrecios.cerrar).not.toHaveBeenCalled()

    await userEvent.click(within(caja).getByRole('button', { name: /sí, cerrar/i }))
    expect(apiPrecios.cerrar).toHaveBeenCalledWith(1, undefined)
    expect(await screen.findByText(/regla cerrada/i)).toBeInTheDocument()
    expect(apiPrecios.listar).toHaveBeenCalledTimes(2)
  })

  it('cancelar no cierra nada', async () => {
    apiPrecios.listar.mockResolvedValue([VIGENTE])
    montar()
    await screen.findByRole('table')
    await userEvent.click(screen.getByRole('button', { name: /cerrar/i }))
    await userEvent.click(await screen.findByRole('button', { name: /cancelar/i }))
    expect(apiPrecios.cerrar).not.toHaveBeenCalled()
    expect(screen.queryByRole('group', { name: /cerrar la regla/i })).not.toBeInTheDocument()
  })

  it('una regla que empieza hoy se cierra con la fecha de hoy y la pantalla lo dice', async () => {
    apiPrecios.listar.mockResolvedValue([DESDE_HOY])
    apiPrecios.cerrar.mockResolvedValue({ ...DESDE_HOY, vigente: false })
    montar()
    await screen.findByRole('table')
    await userEvent.click(screen.getByRole('button', { name: /cerrar/i }))
    const caja = await screen.findByRole('group', { name: /cerrar la regla/i })
    expect(within(caja).getByText(/hasta el final de hoy/i)).toBeInTheDocument()
    await userEvent.click(within(caja).getByRole('button', { name: /sí, cerrar/i }))
    expect(apiPrecios.cerrar).toHaveBeenCalledWith(5, '2026-11-06')
  })

  it('si el servidor lo rechaza, muestra su motivo y no da la regla por cerrada', async () => {
    apiPrecios.listar.mockResolvedValue([VIGENTE])
    apiPrecios.cerrar.mockRejectedValue(new ErrorApi(400, 'La regla empezó el 2026-01-01 y no puede cerrarse antes.'))
    montar()
    await screen.findByRole('table')
    await userEvent.click(screen.getByRole('button', { name: /cerrar/i }))
    await userEvent.click(await screen.findByRole('button', { name: /sí, cerrar/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/no puede cerrarse antes/i)
    expect(screen.queryByText(/regla cerrada/i)).not.toBeInTheDocument()
  })

  it('no manda dos veces con dos pulsaciones seguidas', async () => {
    apiPrecios.listar.mockResolvedValue([VIGENTE])
    let terminar
    apiPrecios.cerrar.mockReturnValue(new Promise((r) => { terminar = r }))
    montar()
    await screen.findByRole('table')
    await userEvent.click(screen.getByRole('button', { name: /cerrar/i }))
    const confirmar = await screen.findByRole('button', { name: /sí, cerrar/i })
    await userEvent.dblClick(confirmar)
    expect(apiPrecios.cerrar).toHaveBeenCalledTimes(1)
    terminar({ ...VIGENTE, vigente: false })
    await screen.findByText(/regla cerrada/i)
  })
})

describe('formulario de regla nueva', () => {
  async function abrir() {
    montar('/precios/nueva')
    return screen.findByRole('heading', { level: 1, name: /nueva regla/i })
  }

  it('tiene etiquetas visibles, parte sin porcentaje y dice que la regla es opcional', async () => {
    await abrir()
    expect(screen.getByLabelText('Categoría')).toHaveValue('')
    expect(screen.getByLabelText('Proveedor')).toHaveValue('')
    expect(screen.getByLabelText('Se cobra')).toHaveValue('CONTADO')
    expect(screen.getByLabelText('Porcentaje de ganancia')).toHaveValue('')
    expect(screen.getByLabelText('Empieza el')).toHaveValue('2026-11-06')
    expect(screen.getByLabelText(/termina el/i)).toHaveValue('')
    expect(screen.getByRole('radio', { name: /recargo sobre el costo/i })).toBeChecked()
    expect(screen.getByText(/no es obligatoria/i)).toBeInTheDocument()
  })

  it('un formulario inválido no envía nada, marca los errores y enfoca el primero', async () => {
    await abrir()
    await userEvent.click(screen.getByRole('button', { name: /guardar regla/i }))
    const campo = screen.getByLabelText('Porcentaje de ganancia')
    expect(campo).toHaveAttribute('aria-invalid', 'true')
    expect(campo).toHaveFocus()
    expect(screen.getByText('Escribe el porcentaje de ganancia.')).toBeInTheDocument()
    expect(apiPrecios.crear).not.toHaveBeenCalled()
  })

  it.each([
    ['0', /mayor que cero/i],
    ['0,00', /mayor que cero/i],
    ['12.5', /usa coma/i],
    ['12,345', /dos decimales/i],
    ['abc', /solo números/i],
    ['10000', /9\.899,99/],
  ])('rechaza el porcentaje «%s»', async (texto, mensaje) => {
    await abrir()
    await userEvent.type(screen.getByLabelText('Porcentaje de ganancia'), texto)
    await userEvent.click(screen.getByRole('button', { name: /guardar regla/i }))
    expect(screen.getByText(mensaje)).toBeInTheDocument()
    expect(apiPrecios.crear).not.toHaveBeenCalled()
  })

  it('la ganancia sobre el precio no llega al 100 %', async () => {
    await abrir()
    await userEvent.click(screen.getByRole('radio', { name: /ganancia sobre el precio/i }))
    await userEvent.type(screen.getByLabelText('Porcentaje de ganancia'), '100')
    await userEvent.click(screen.getByRole('button', { name: /guardar regla/i }))
    expect(screen.getByText(/menor que 100/i)).toBeInTheDocument()
    expect(apiPrecios.crear).not.toHaveBeenCalled()
  })

  it('la fecha final no puede ser anterior a la inicial', async () => {
    await abrir()
    await userEvent.type(screen.getByLabelText('Porcentaje de ganancia'), '10')
    await userEvent.type(screen.getByLabelText(/termina el/i), '2026-11-01')
    await userEvent.click(screen.getByRole('button', { name: /guardar regla/i }))
    expect(screen.getByText(/no puede ser anterior/i)).toBeInTheDocument()
    expect(apiPrecios.crear).not.toHaveBeenCalled()
  })

  it.each([
    ['20,5', 1.205],
    ['1,25', 1.0125],
    ['0,01', 1.0001],
    ['20,2', 1.202],
    ['9899,99', 99.9999],
  ])('el porcentaje %s se manda como factor %s, sin error de coma flotante', async (texto, factor) => {
    apiPrecios.crear.mockResolvedValue({ ...BASE, reglaId: 10 })
    apiPrecios.listar.mockResolvedValue([])
    await abrir()
    await userEvent.type(screen.getByLabelText('Porcentaje de ganancia'), texto)
    await userEvent.click(screen.getByRole('button', { name: /guardar regla/i }))
    await waitFor(() => expect(apiPrecios.crear).toHaveBeenCalledTimes(1))
    expect(apiPrecios.crear.mock.calls[0][0].factor).toBe(factor)
  })

  it('crea con el alcance elegido y vuelve a la lista con un aviso', async () => {
    apiPrecios.crear.mockResolvedValue({ ...BASE, reglaId: 10 })
    apiPrecios.listar.mockResolvedValue([VIGENTE])
    await abrir()
    await userEvent.selectOptions(screen.getByLabelText('Categoría'), '3')
    await userEvent.selectOptions(screen.getByLabelText('Se cobra'), 'CREDITO')
    await userEvent.click(screen.getByRole('radio', { name: /margen sobre el precio/i }))
    await userEvent.type(screen.getByLabelText('Porcentaje de ganancia'), '25')
    await userEvent.click(screen.getByRole('button', { name: /guardar regla/i }))
    expect(apiPrecios.crear).toHaveBeenCalledWith({
      categoriaId: 3, proveedorId: null, formaPago: 'CREDITO', formaCalculo: 'SOBRE_PRECIO',
      factor: 1.25, vigenteDesde: '2026-11-06', vigenteHasta: null,
    })
    expect(await screen.findByText(/regla creada/i)).toBeInTheDocument()
    expect(await screen.findByRole('table')).toBeInTheDocument()
  })

  it('el aviso de «Regla creada.» no reaparece al recargar: el estado de la navegación se limpia', async () => {
    apiPrecios.crear.mockResolvedValue({ ...BASE, reglaId: 10 })
    apiPrecios.listar.mockResolvedValue([VIGENTE])
    montar('/precios/nueva')
    await userEvent.type(await screen.findByLabelText('Porcentaje de ganancia'), '10')
    await userEvent.click(screen.getByRole('button', { name: /guardar regla/i }))
    expect(await screen.findByText(/regla creada/i)).toBeInTheDocument()
    await waitFor(() => expect(screen.getByTestId('estado-de-navegacion')).toHaveTextContent(/^null$/))
    expect(screen.getByText(/regla creada/i)).toBeInTheDocument()
  })

  it('muestra cuántos productos alcanzaría al elegir categoría o proveedor', async () => {
    apiPrecios.alcance.mockResolvedValue({ productos: 7 })
    await abrir()
    expect(await screen.findByText(/7 productos/i)).toBeInTheDocument()
    expect(apiPrecios.alcance).toHaveBeenLastCalledWith(null, null)
    await userEvent.selectOptions(screen.getByLabelText('Proveedor'), '9')
    await waitFor(() => expect(apiPrecios.alcance).toHaveBeenLastCalledWith(null, 9))
    expect(screen.getByText(/no cuenta los que ya tienen precio o porcentaje propio/i)).toBeInTheDocument()
  })

  it('un solape (409) muestra lo que dice el servidor y deja reintentar', async () => {
    apiPrecios.crear.mockRejectedValue(new ErrorApi(409, 'Ya hay la regla general para contado con una vigencia que se solapa.'))
    await abrir()
    await userEvent.type(screen.getByLabelText('Porcentaje de ganancia'), '10')
    await userEvent.click(screen.getByRole('button', { name: /guardar regla/i }))
    const aviso = await screen.findByRole('alert')
    expect(aviso).toHaveTextContent(/se solapa/i)
    expect(screen.getByRole('button', { name: /guardar regla/i })).toBeEnabled()
  })

  it.each([400, 422])('un %s muestra su detalle', async (estado) => {
    apiPrecios.crear.mockRejectedValue(new ErrorApi(estado, 'Motivo del servidor.'))
    await abrir()
    await userEvent.type(screen.getByLabelText('Porcentaje de ganancia'), '10')
    await userEvent.click(screen.getByRole('button', { name: /guardar regla/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Motivo del servidor.')
  })

  it('deshabilita el botón mientras envía', async () => {
    let terminar
    apiPrecios.crear.mockReturnValue(new Promise((r) => { terminar = r }))
    apiPrecios.listar.mockResolvedValue([])
    await abrir()
    await userEvent.type(screen.getByLabelText('Porcentaje de ganancia'), '10')
    await userEvent.click(screen.getByRole('button', { name: /guardar regla/i }))
    expect(screen.getByRole('button', { name: /guardando/i })).toBeDisabled()
    terminar({ ...BASE })
    await screen.findByText(/regla creada/i)
    expect(apiPrecios.crear).toHaveBeenCalledTimes(1)
  })

  it('si no cargan las categorías, lo dice y reintenta', async () => {
    apiPrecios.categorias
      .mockRejectedValueOnce(new ErrorApi(500, 'Falló.'))
      .mockResolvedValueOnce(CATEGORIAS)
    montar('/precios/nueva')
    expect(await screen.findByRole('alert')).toHaveTextContent('Falló.')
    await userEvent.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByLabelText('Categoría')).toBeInTheDocument()
  })

  it('un 403 al cargar muestra sin permiso', async () => {
    apiPrecios.proveedores.mockRejectedValue(new ErrorApi(403, 'no'))
    montar('/precios/nueva')
    expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument()
  })
})

describe('simulación', () => {
  const BODY = {
    categoriaId: null, proveedorId: null, formaPago: 'CONTADO', formaCalculo: 'SOBRE_COSTO',
    factor: 1.1, vigenteDesde: '2026-11-06', vigenteHasta: null,
  }

  it('no se pide con el formulario inválido', async () => {
    montar('/precios/nueva')
    await userEvent.click(await screen.findByRole('button', { name: /ver qué productos/i }))
    expect(apiPrecios.simular).not.toHaveBeenCalled()
    expect(screen.getByLabelText('Porcentaje de ganancia')).toHaveFocus()
  })

  it('sin cruces dice que ningún producto cambia de impuesto', async () => {
    apiPrecios.simular.mockResolvedValue([])
    montar('/precios/nueva')
    await userEvent.type(await screen.findByLabelText('Porcentaje de ganancia'), '10')
    await userEvent.click(screen.getByRole('button', { name: /ver qué productos/i }))
    expect(await screen.findByText(/ningún producto cambia/i)).toBeInTheDocument()
    expect(apiPrecios.simular).toHaveBeenCalledWith(BODY)
  })

  it('con cruces lista cada producto con su precio y su tratamiento antes y después', async () => {
    apiPrecios.simular.mockResolvedValue([{
      productoId: 5, codigo: 'PC-01', nombre: 'Portátil 14', costo: 1000, precioAntes: 1500000,
      precioDespues: 1700000, umbral: 2500000, tratamientoAntes: 'EXCLUIDO_HASTA_UMBRAL', tratamientoDespues: 'GRAVADO',
    }])
    montar('/precios/nueva')
    await userEvent.type(await screen.findByLabelText('Porcentaje de ganancia'), '10')
    await userEvent.click(screen.getByRole('button', { name: /ver qué productos/i }))
    const tabla = await screen.findByRole('table', { name: /productos que cambian/i })
    const fila = within(tabla).getAllByRole('row')[1]
    expect(within(fila).getByText('Portátil 14')).toBeInTheDocument()
    expect(within(fila).getByText(/1\.500\.000/)).toBeInTheDocument()
    expect(within(fila).getByText(/1\.700\.000/)).toBeInTheDocument()
    expect(within(fila).getByText('Excluido hasta el tope')).toBeInTheDocument()
    expect(within(fila).getByText('Con IVA')).toBeInTheDocument()
  })

  it('un error se dice y no deja resultados viejos', async () => {
    apiPrecios.simular.mockRejectedValue(new ErrorApi(422, 'La categoría 3 no es de esta empresa.'))
    montar('/precios/nueva')
    await userEvent.type(await screen.findByLabelText('Porcentaje de ganancia'), '10')
    await userEvent.click(screen.getByRole('button', { name: /ver qué productos/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/no es de esta empresa/i)
  })

  it('cambiar un dato borra el resultado: ya no corresponde a lo escrito', async () => {
    apiPrecios.simular.mockResolvedValue([])
    montar('/precios/nueva')
    const campo = await screen.findByLabelText('Porcentaje de ganancia')
    await userEvent.type(campo, '10')
    await userEvent.click(screen.getByRole('button', { name: /ver qué productos/i }))
    await screen.findByText(/ningún producto cambia/i)
    await userEvent.type(campo, '5')
    expect(screen.queryByText(/ningún producto cambia/i)).not.toBeInTheDocument()
  })
})

describe('corregir una regla que aún no empieza', () => {
  it('abre con lo guardado, el alcance fijo, y corrige en el sitio', async () => {
    apiPrecios.ver.mockResolvedValue(PROXIMA)
    apiPrecios.corregir.mockResolvedValue({ ...PROXIMA, factor: 1.3 })
    apiPrecios.listar.mockResolvedValue([PROXIMA])
    montar('/precios/3/corregir')
    const campo = await screen.findByLabelText('Porcentaje de ganancia')
    expect(apiPrecios.ver).toHaveBeenCalledWith('3')
    expect(campo).toHaveValue('1,25')
    expect(screen.getByLabelText('Empieza el')).toHaveValue('2026-12-01')
    expect(screen.queryByLabelText('Categoría')).not.toBeInTheDocument()
    expect(screen.getByText('Proveedor Mayorista Tech')).toBeInTheDocument()
    await userEvent.clear(campo)
    await userEvent.type(campo, '30')
    await userEvent.click(screen.getByRole('button', { name: /guardar cambios/i }))
    expect(apiPrecios.corregir).toHaveBeenCalledWith(3, {
      categoriaId: null, proveedorId: 9, formaPago: 'CONTADO', formaCalculo: 'SOBRE_COSTO',
      factor: 1.3, vigenteDesde: '2026-12-01', vigenteHasta: null,
    })
    expect(await screen.findByText(/cambios guardados/i)).toBeInTheDocument()
  })

  it.each([
    [400, 'La vigencia termina antes de empezar.'],
    [409, 'Con esa vigencia, la regla se solaparía con otra del mismo alcance.'],
  ])('un %s del servidor al corregir se muestra con su detalle y deja reintentar', async (estado, detalle) => {
    apiPrecios.ver.mockResolvedValue(PROXIMA)
    apiPrecios.corregir.mockRejectedValue(new ErrorApi(estado, detalle))
    montar('/precios/3/corregir')
    await screen.findByLabelText('Porcentaje de ganancia')
    await userEvent.click(screen.getByRole('button', { name: /guardar cambios/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(detalle)
    expect(screen.getByRole('button', { name: /guardar cambios/i })).toBeEnabled()
  })

  it('una regla que ya rige no se corrige: dice por qué y ofrece reemplazarla', async () => {
    apiPrecios.ver.mockResolvedValue(VIGENTE)
    montar('/precios/1/corregir')
    expect(await screen.findByText(/ya rige/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /reemplazarla/i })).toHaveAttribute('href', '/precios/1/reemplazar')
    expect(screen.queryByLabelText('Porcentaje de ganancia')).not.toBeInTheDocument()
  })
})

describe('reemplazar una regla que rige', () => {
  const CUERPO = {
    categoriaId: 3, proveedorId: null, formaPago: 'CONTADO', formaCalculo: 'SOBRE_COSTO',
    factor: 1.225, vigenteDesde: '2026-11-06', vigenteHasta: null,
  }

  it('lo hace en una sola llamada al servidor, que cierra y abre de forma atómica', async () => {
    apiPrecios.ver.mockResolvedValue(VIGENTE)
    apiPrecios.reemplazar.mockResolvedValue({ ...BASE, reglaId: 11 })
    apiPrecios.listar.mockResolvedValue([])
    montar('/precios/1/reemplazar')
    const campo = await screen.findByLabelText('Porcentaje de ganancia')
    expect(campo).toHaveValue('20,00')
    expect(screen.getByLabelText('Empieza el')).toHaveValue('2026-11-06')
    expect(screen.getByText(/se cierra el 5 nov 2026/i)).toBeInTheDocument()
    await userEvent.clear(campo)
    await userEvent.type(campo, '22,5')
    await userEvent.click(screen.getByRole('button', { name: /cerrar la actual y crear la nueva/i }))
    await waitFor(() => expect(apiPrecios.reemplazar).toHaveBeenCalledTimes(1))
    expect(apiPrecios.reemplazar).toHaveBeenCalledWith(1, CUERPO)
    expect(apiPrecios.cerrar).not.toHaveBeenCalled()
    expect(apiPrecios.crear).not.toHaveBeenCalled()
    expect(await screen.findByText(/regla reemplazada/i)).toBeInTheDocument()
  })

  it('si el servidor rechaza el reemplazo no dice que nada se cerró: no se tocó nada', async () => {
    apiPrecios.ver.mockResolvedValue(VIGENTE)
    apiPrecios.reemplazar.mockRejectedValue(new ErrorApi(409, 'Ya hay una regla con una vigencia que se solapa.'))
    montar('/precios/1/reemplazar')
    await screen.findByLabelText('Porcentaje de ganancia')
    await userEvent.click(screen.getByRole('button', { name: /cerrar la actual y crear la nueva/i }))
    const aviso = await screen.findByRole('alert')
    expect(aviso).toHaveTextContent('Ya hay una regla con una vigencia que se solapa.')
    expect(screen.queryByText(/ya se cerró/i)).not.toBeInTheDocument()
    expect(apiPrecios.cerrar).not.toHaveBeenCalled()
    expect(apiPrecios.crear).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: /cerrar la actual y crear la nueva/i })).toBeEnabled()
  })

  it.each([400, 422])('un %s del servidor se muestra con su detalle', async (estado) => {
    apiPrecios.ver.mockResolvedValue(VIGENTE)
    apiPrecios.reemplazar.mockRejectedValue(new ErrorApi(estado, 'Motivo del servidor.'))
    montar('/precios/1/reemplazar')
    await screen.findByLabelText('Porcentaje de ganancia')
    await userEvent.click(screen.getByRole('button', { name: /cerrar la actual y crear la nueva/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Motivo del servidor.')
    expect(screen.queryByText(/ya se cerró/i)).not.toBeInTheDocument()
  })

  it('no manda dos veces con dos pulsaciones seguidas', async () => {
    apiPrecios.ver.mockResolvedValue(VIGENTE)
    apiPrecios.listar.mockResolvedValue([])
    let terminar
    apiPrecios.reemplazar.mockReturnValue(new Promise((r) => { terminar = r }))
    montar('/precios/1/reemplazar')
    await screen.findByLabelText('Porcentaje de ganancia')
    await userEvent.dblClick(screen.getByRole('button', { name: /cerrar la actual y crear la nueva/i }))
    expect(apiPrecios.reemplazar).toHaveBeenCalledTimes(1)
    terminar({ ...BASE })
    await screen.findByText(/regla reemplazada/i)
  })

  it('si lo escrito es inválido no llama al servidor', async () => {
    apiPrecios.ver.mockResolvedValue(VIGENTE)
    montar('/precios/1/reemplazar')
    const campo = await screen.findByLabelText('Porcentaje de ganancia')
    await userEvent.clear(campo)
    await userEvent.click(screen.getByRole('button', { name: /cerrar la actual y crear la nueva/i }))
    expect(apiPrecios.reemplazar).not.toHaveBeenCalled()
    expect(campo).toHaveFocus()
  })

  it('no deja empezar la nueva antes de hoy ni antes de que empiece la actual', async () => {
    apiPrecios.ver.mockResolvedValue(VIGENTE)
    montar('/precios/1/reemplazar')
    const desde = await screen.findByLabelText('Empieza el')
    await userEvent.clear(desde)
    await userEvent.type(desde, '2026-10-01')
    await userEvent.click(screen.getByRole('button', { name: /cerrar la actual y crear la nueva/i }))
    expect(screen.getByText(/no puede empezar antes de hoy/i)).toBeInTheDocument()
    expect(apiPrecios.reemplazar).not.toHaveBeenCalled()
  })

  it('no deja que la nueva empiece después del fin planeado de la actual: alargaría la regla', async () => {
    apiPrecios.ver.mockResolvedValue({ ...VIGENTE, vigenteHasta: '2026-11-20' })
    montar('/precios/1/reemplazar')
    const desde = await screen.findByLabelText('Empieza el')
    await userEvent.clear(desde)
    await userEvent.type(desde, '2026-12-15')
    await userEvent.click(screen.getByRole('button', { name: /cerrar la actual y crear la nueva/i }))
    expect(screen.getByText(/la regla actual ya termina el 20 nov 2026/i)).toBeInTheDocument()
    expect(desde).toHaveFocus()
    expect(apiPrecios.reemplazar).not.toHaveBeenCalled()
  })

  it('una regla cerrada no se reemplaza', async () => {
    apiPrecios.ver.mockResolvedValue(CERRADA)
    montar('/precios/4/reemplazar')
    expect(await screen.findByText(/ya está cerrada/i)).toBeInTheDocument()
    expect(screen.queryByLabelText('Porcentaje de ganancia')).not.toBeInTheDocument()
  })
})

describe('rutas con un identificador que no existe', () => {
  it.each(['abc', '0', '1x', '-3'])('«%s» dice que no se encontró sin pedir nada al servidor', async (id) => {
    montar(`/precios/${id}/reemplazar`)
    expect(await screen.findByText(/no encontramos esa regla/i)).toBeInTheDocument()
    expect(apiPrecios.ver).not.toHaveBeenCalled()
  })

  it('una regla que el servidor no tiene (404) dice que no se encontró', async () => {
    apiPrecios.ver.mockRejectedValue(new ErrorApi(404, 'No se pudo completar la operación.'))
    montar('/precios/99/corregir')
    expect(await screen.findByText(/no encontramos esa regla/i)).toBeInTheDocument()
  })

  it('un 403 al cargar muestra sin permiso', async () => {
    apiPrecios.ver.mockRejectedValue(new ErrorApi(403, 'no'))
    montar('/precios/3/corregir')
    expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument()
  })

  it('una ruta desconocida lleva a la lista', async () => {
    apiPrecios.listar.mockResolvedValue([VIGENTE])
    montar('/precios/otra/cosa/mas')
    expect(await screen.findByRole('table')).toBeInTheDocument()
  })
})

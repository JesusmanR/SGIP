/**
 * Sección Impuestos. Se prueba montada en sus rutas reales, con el módulo de
 * API simulado y la clase ErrorApi real. «Hoy» es el 6 de nov de 2026, a las
 * 9 de la noche hora local (solo Date falso: con relojes completos userEvent
 * se cuelga).
 */
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ErrorApi } from '../../servicios/api.js'
import { apiImpuestos } from '../../servicios/api/impuestos.js'
import Impuestos from './index.jsx'

vi.mock('../../servicios/api/impuestos.js', () => ({
  apiImpuestos: {
    configuracion: vi.fn(), guardar: vi.fn(), catalogo: vi.fn(), reglas: vi.fn(),
    crearRegla: vi.fn(), cerrarRegla: vi.fn(), categorias: vi.fn(), buscarProductos: vi.fn(),
  },
}))

const CONFIG = {
  responsableIva: false, aplicaExclusionUmbral: false, practicaRetenciones: true,
  tarifaIca: 0.00414, manejaEnvios: true, cotizacionVigenciaDias: 15, configuracionCompleta: true,
}
const CONFIG_SIN_RESPONDER = {
  responsableIva: false, aplicaExclusionUmbral: false, practicaRetenciones: false,
  tarifaIca: null, manejaEnvios: false, cotizacionVigenciaDias: 15, configuracionCompleta: false,
}
const RESPONSABLE = { ...CONFIG, responsableIva: true }

const CATALOGO = [
  {
    tipoImpuestoId: 1, codigo: 'IVA_19', nombre: 'IVA tarifa general (19 %)', tratamiento: 'GRAVADO',
    tarifa: 0.19, umbralUvt: null, fundamento: 'Estatuto Tributario, art. 468', activo: true,
  },
  {
    tipoImpuestoId: 2, codigo: 'EXCL_UMBRAL_PC', nombre: 'Computadores, excluidos hasta 50 UVT',
    tratamiento: 'EXCLUIDO_HASTA_UMBRAL', tarifa: 0.19, umbralUvt: 50, fundamento: 'art. 424', activo: true,
  },
  {
    tipoImpuestoId: 3, codigo: 'EXENTO', nombre: 'Exento (tarifa cero)', tratamiento: 'EXENTO',
    tarifa: 0, umbralUvt: null, fundamento: 'art. 477', activo: true,
  },
]

const REGLA = {
  reglaId: 1, categoriaId: null, categoria: null, productoId: null, producto: null,
  codigoImpuesto: 'IVA_19', impuesto: 'IVA tarifa general (19 %)', tratamiento: 'GRAVADO', tarifa: 0.19,
  vigenteDesde: '2026-01-01', vigenteHasta: null, fundamento: null, especificidad: 0, vigente: true,
}
const DE_CATEGORIA = {
  ...REGLA, reglaId: 2, categoriaId: 3, categoria: 'Libros', codigoImpuesto: 'EXENTO',
  impuesto: 'Exento (tarifa cero)', tratamiento: 'EXENTO', tarifa: 0, especificidad: 1,
}
const FUTURA = {
  ...REGLA, reglaId: 3, productoId: 8, producto: 'Tornillo', codigoImpuesto: 'IVA_19',
  vigenteDesde: '2026-12-01', vigente: false, especificidad: 2,
}
const CERRADA = { ...REGLA, reglaId: 4, vigenteHasta: '2025-12-31', vigente: false, categoria: 'Vieja', categoriaId: 5 }
const DESDE_HOY = { ...REGLA, reglaId: 5, vigenteDesde: '2026-11-06', categoriaId: 3, categoria: 'Papelería' }

function montar(ruta = '/impuestos') {
  return render(
    <MemoryRouter initialEntries={[ruta]}>
      <Routes>
        <Route path="/impuestos/*" element={<Impuestos />} />
      </Routes>
    </MemoryRouter>,
  )
}

const radio = (nombre) => screen.getByRole('radio', { name: nombre })

beforeEach(() => {
  Object.values(apiImpuestos).forEach((f) => f.mockReset())
  apiImpuestos.configuracion.mockResolvedValue(CONFIG)
  apiImpuestos.catalogo.mockResolvedValue(CATALOGO)
  apiImpuestos.reglas.mockResolvedValue([])
  apiImpuestos.categorias.mockResolvedValue([{ id: 3, nombre: 'Libros' }, { id: 4, nombre: 'Papelería' }])
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(2026, 10, 6, 21, 0, 0))
})

afterEach(() => {
  vi.useRealTimers()
})

describe('carga y estados', () => {
  it('muestra la carga y luego la pantalla', async () => {
    montar()
    expect(screen.getByRole('status')).toHaveTextContent(/cargando/i)
    expect(await screen.findByRole('heading', { level: 1, name: 'Impuestos' })).toBeInTheDocument()
    expect(apiImpuestos.configuracion).toHaveBeenCalledTimes(1)
  })

  it('un error dice qué pasó y reintenta', async () => {
    apiImpuestos.configuracion
      .mockRejectedValueOnce(new ErrorApi(500, 'El servidor no respondió.'))
      .mockResolvedValueOnce(CONFIG)
    montar()
    expect(await screen.findByRole('alert')).toHaveTextContent('El servidor no respondió.')
    await userEvent.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByText(/responsable de iva/i)).toBeInTheDocument()
    expect(apiImpuestos.configuracion).toHaveBeenCalledTimes(2)
  })

  it('un 403 muestra la pantalla de sin permiso', async () => {
    apiImpuestos.configuracion.mockRejectedValue(new ErrorApi(403, 'no'))
    montar()
    expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument()
    expect(apiImpuestos.reglas).not.toHaveBeenCalled()
  })

  it('si fallan las reglas, el resto sigue y la sección reintenta sola', async () => {
    apiImpuestos.configuracion.mockResolvedValue(RESPONSABLE)
    apiImpuestos.reglas
      .mockRejectedValueOnce(new ErrorApi(500, 'No hubo respuesta.'))
      .mockResolvedValueOnce([REGLA])
    montar()
    expect(await screen.findByRole('alert')).toHaveTextContent('No hubo respuesta.')
    expect(screen.getByRole('radio', { name: /^sí/i })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByRole('table', { name: /reglas de iva/i })).toBeInTheDocument()
  })

  it('si falla el catálogo, lo dice sin tumbar la pantalla', async () => {
    apiImpuestos.catalogo.mockRejectedValue(new ErrorApi(500, 'Catálogo caído.'))
    montar()
    expect(await screen.findByRole('alert')).toHaveTextContent('Catálogo caído.')
    expect(screen.getByRole('radio', { name: /^no/i })).toBeInTheDocument()
  })
})

describe('la pregunta de IVA', () => {
  it('una empresa sin configurar ve la pregunta sin respuesta y se le dice que vende sin IVA', async () => {
    apiImpuestos.configuracion.mockResolvedValue(CONFIG_SIN_RESPONDER)
    montar()
    await screen.findByText(/responsable de iva/i)
    expect(radio(/^sí/i)).not.toBeChecked()
    expect(radio(/^no/i)).not.toBeChecked()
    expect(screen.getByText(/mientras no respondas/i)).toBeInTheDocument()
    expect(screen.getByText(/se venden al precio, sin iva/i)).toBeInTheDocument()
    expect(screen.queryByText(/19\s?%.*por omisión/i)).not.toBeInTheDocument()
  })

  it('con «No» dice, sin alarma, que puede vender sin configurar nada más', async () => {
    montar()
    await screen.findByText(/responsable de iva/i)
    expect(radio(/^no/i)).toBeChecked()
    expect(screen.getByText(/puedes vender sin configurar nada más/i)).toBeInTheDocument()
    expect(screen.queryByRole('table', { name: /reglas de iva/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /nueva regla/i })).not.toBeInTheDocument()
    expect(screen.queryByText(/falta configurar/i)).not.toBeInTheDocument()
  })

  it('con «Sí» y ninguna regla dice que falta configurar, no que no se pueda vender', async () => {
    apiImpuestos.configuracion.mockResolvedValue(RESPONSABLE)
    montar()
    const aviso = await screen.findByText(/falta configurar/i)
    expect(aviso).toHaveTextContent(/no impide vender/i)
    expect(aviso).not.toHaveTextContent(/no se puede vender/i)
    expect(screen.getByRole('link', { name: /nueva regla/i })).toHaveAttribute('href', '/impuestos/reglas/nueva')
  })

  it('con «Sí» y una regla que rige no avisa de nada', async () => {
    apiImpuestos.configuracion.mockResolvedValue(RESPONSABLE)
    apiImpuestos.reglas.mockResolvedValue([REGLA])
    montar()
    await screen.findByRole('table', { name: /reglas de iva/i })
    expect(screen.queryByText(/falta configurar/i)).not.toBeInTheDocument()
  })

  it('con «Sí» y reglas solo de una categoría avisa que lo demás va sin IVA', async () => {
    apiImpuestos.configuracion.mockResolvedValue(RESPONSABLE)
    apiImpuestos.reglas.mockResolvedValue([DE_CATEGORIA])
    montar()
    expect(await screen.findByText(/los productos que ninguna regla alcanza/i)).toBeInTheDocument()
  })

  it('una regla que rige con la empresa en «No» aclara que no se cobra', async () => {
    apiImpuestos.reglas.mockResolvedValue([REGLA])
    montar()
    const tabla = await screen.findByRole('table', { name: /reglas de iva/i })
    expect(within(tabla).getByText(/no se cobra mientras la empresa no sea responsable de iva/i)).toBeInTheDocument()
    expect(screen.getByText(/se conservan/i)).toBeInTheDocument()
  })
})

describe('guardar la configuración', () => {
  it('cambiar solo la respuesta de IVA manda los demás campos tal como vinieron', async () => {
    apiImpuestos.guardar.mockResolvedValue({ ...CONFIG, responsableIva: true })
    montar()
    await screen.findByText(/responsable de iva/i)
    await userEvent.click(radio(/^sí/i))
    await userEvent.click(screen.getByRole('button', { name: /guardar configuración/i }))
    expect(apiImpuestos.guardar).toHaveBeenCalledWith({
      responsableIva: true, aplicaExclusionUmbral: false, practicaRetenciones: true,
      tarifaIca: 0.00414, manejaEnvios: true, cotizacionVigenciaDias: 15,
    })
    expect(await screen.findByText(/configuración guardada/i)).toBeInTheDocument()
  })

  it('el ICA se muestra con su precisión completa y sin tocarlo se manda igual', async () => {
    apiImpuestos.guardar.mockResolvedValue(CONFIG)
    montar()
    const ica = await screen.findByLabelText(/tarifa de ica/i)
    expect(ica).toHaveValue('0,414')
    await userEvent.click(screen.getByRole('button', { name: /guardar configuración/i }))
    expect(apiImpuestos.guardar.mock.calls[0][0].tarifaIca).toBe(0.00414)
  })

  it('un ICA editado se convierte a fracción sin error de coma flotante', async () => {
    apiImpuestos.guardar.mockResolvedValue(CONFIG)
    montar()
    const ica = await screen.findByLabelText(/tarifa de ica/i)
    await userEvent.clear(ica)
    await userEvent.type(ica, '0,966')
    await userEvent.click(screen.getByRole('button', { name: /guardar configuración/i }))
    expect(apiImpuestos.guardar.mock.calls[0][0].tarifaIca).toBe(0.00966)
  })

  it('un ICA vacío se manda como ausente', async () => {
    apiImpuestos.guardar.mockResolvedValue(CONFIG)
    montar()
    const ica = await screen.findByLabelText(/tarifa de ica/i)
    await userEvent.clear(ica)
    await userEvent.click(screen.getByRole('button', { name: /guardar configuración/i }))
    expect(apiImpuestos.guardar.mock.calls[0][0].tarifaIca).toBeNull()
  })

  it.each([
    ['abc', /solo números/i],
    ['0.5', /usa coma/i],
    ['0,12345', /hasta 4 decimales/i],
    ['101', /entre 0 y 100/i],
  ])('rechaza un ICA «%s» sin enviar', async (texto, mensaje) => {
    montar()
    const ica = await screen.findByLabelText(/tarifa de ica/i)
    await userEvent.clear(ica)
    await userEvent.type(ica, texto)
    await userEvent.click(screen.getByRole('button', { name: /guardar configuración/i }))
    expect(screen.getByText(mensaje)).toBeInTheDocument()
    expect(ica).toHaveFocus()
    expect(apiImpuestos.guardar).not.toHaveBeenCalled()
  })

  it('el ICA solo aparece si se practican retenciones, y sin ellas se manda vacío', async () => {
    apiImpuestos.guardar.mockResolvedValue(CONFIG)
    montar()
    await screen.findByLabelText(/tarifa de ica/i)
    await userEvent.click(screen.getByRole('checkbox', { name: /retenciones/i }))
    expect(screen.queryByLabelText(/tarifa de ica/i)).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /guardar configuración/i }))
    const enviado = apiImpuestos.guardar.mock.calls[0][0]
    expect(enviado.practicaRetenciones).toBe(false)
    expect(enviado.tarifaIca).toBeNull()
  })

  it('sin responder la pregunta de IVA no se envía y se enfoca la pregunta', async () => {
    apiImpuestos.configuracion.mockResolvedValue(CONFIG_SIN_RESPONDER)
    montar()
    await screen.findByText(/responsable de iva/i)
    await userEvent.click(screen.getByRole('button', { name: /guardar configuración/i }))
    expect(screen.getByText(/elige sí o no/i)).toBeInTheDocument()
    expect(radio(/^sí/i)).toHaveFocus()
    expect(apiImpuestos.guardar).not.toHaveBeenCalled()
  })

  it.each([['', /escribe/i], ['0', /entre 1 y 365/i], ['366', /entre 1 y 365/i], ['2,5', /número entero/i]])(
    'rechaza una vigencia de cotización «%s»', async (texto, mensaje) => {
      montar()
      const campo = await screen.findByLabelText(/días que vale una cotización/i)
      await userEvent.clear(campo)
      if (texto) await userEvent.type(campo, texto)
      await userEvent.click(screen.getByRole('button', { name: /guardar configuración/i }))
      expect(screen.getByText(mensaje)).toBeInTheDocument()
      expect(campo).toHaveFocus()
      expect(apiImpuestos.guardar).not.toHaveBeenCalled()
    },
  )

  it('con «No» no se ofrece la exclusión por umbral, pero se conserva lo guardado', async () => {
    apiImpuestos.configuracion.mockResolvedValue({ ...CONFIG, aplicaExclusionUmbral: true })
    apiImpuestos.guardar.mockResolvedValue(CONFIG)
    montar()
    await screen.findByText(/responsable de iva/i)
    expect(screen.queryByRole('checkbox', { name: /exclusión/i })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /guardar configuración/i }))
    expect(apiImpuestos.guardar.mock.calls[0][0].aplicaExclusionUmbral).toBe(true)
  })

  it('con «Sí» se ofrece la exclusión por umbral y se manda lo elegido', async () => {
    apiImpuestos.configuracion.mockResolvedValue(RESPONSABLE)
    apiImpuestos.guardar.mockResolvedValue(RESPONSABLE)
    montar()
    await userEvent.click(await screen.findByRole('checkbox', { name: /exclusión/i }))
    await userEvent.click(screen.getByRole('button', { name: /guardar configuración/i }))
    expect(apiImpuestos.guardar.mock.calls[0][0].aplicaExclusionUmbral).toBe(true)
  })

  it('el rechazo del servidor (400) se muestra con su detalle', async () => {
    apiImpuestos.guardar.mockRejectedValue(new ErrorApi(400, 'La tarifa de ICA solo se puede fijar si la empresa practica retenciones.'))
    montar()
    await screen.findByText(/responsable de iva/i)
    await userEvent.click(screen.getByRole('button', { name: /guardar configuración/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/practica retenciones/i)
    expect(screen.getByRole('button', { name: /guardar configuración/i })).toBeEnabled()
  })

  it('no envía dos veces y deshabilita el botón mientras guarda', async () => {
    let terminar
    apiImpuestos.guardar.mockReturnValue(new Promise((r) => { terminar = r }))
    montar()
    await screen.findByText(/responsable de iva/i)
    await userEvent.dblClick(screen.getByRole('button', { name: /guardar configuración/i }))
    expect(screen.getByRole('button', { name: /guardando/i })).toBeDisabled()
    expect(apiImpuestos.guardar).toHaveBeenCalledTimes(1)
    terminar(CONFIG)
    await screen.findByText(/configuración guardada/i)
  })

  it('tras guardar, la pantalla se pone al día con lo que dice el servidor', async () => {
    apiImpuestos.guardar.mockResolvedValue(RESPONSABLE)
    apiImpuestos.configuracion.mockResolvedValueOnce(CONFIG).mockResolvedValueOnce(RESPONSABLE)
    montar()
    await screen.findByText(/responsable de iva/i)
    await userEvent.click(radio(/^sí/i))
    await userEvent.click(screen.getByRole('button', { name: /guardar configuración/i }))
    expect(await screen.findByText(/falta configurar/i)).toBeInTheDocument()
  })
})

describe('catálogo de impuestos', () => {
  it('lista los impuestos que ofrece la plataforma, de solo lectura, con su tarifa en porcentaje', async () => {
    montar()
    const tabla = await screen.findByRole('table', { name: /impuestos disponibles/i })
    const filas = within(tabla).getAllByRole('row')
    expect(filas).toHaveLength(4)
    expect(within(filas[1]).getByText('IVA tarifa general (19 %)')).toBeInTheDocument()
    expect(within(filas[1]).getByText(/19,00\s%/)).toBeInTheDocument()
    expect(within(filas[1]).getByText('Con IVA')).toBeInTheDocument()
    expect(within(filas[2]).getByText(/excluido hasta 50 uvt/i)).toBeInTheDocument()
    expect(within(filas[3]).getByText('Exento de IVA')).toBeInTheDocument()
    expect(within(tabla).queryByRole('button')).not.toBeInTheDocument()
  })
})

describe('reglas de IVA', () => {
  beforeEach(() => {
    apiImpuestos.configuracion.mockResolvedValue(RESPONSABLE)
  })

  it('lista las reglas con alcance, impuesto, vigencia y estado en texto', async () => {
    apiImpuestos.reglas.mockResolvedValue([REGLA, DE_CATEGORIA, FUTURA, CERRADA])
    montar()
    const tabla = await screen.findByRole('table', { name: /reglas de iva/i })
    const filas = within(tabla).getAllByRole('row')
    expect(filas).toHaveLength(4) // la cerrada no se ve
    expect(within(filas[1]).getByText('Todos los productos')).toBeInTheDocument()
    expect(within(filas[1]).getByText(/desde el 1 ene 2026/i)).toBeInTheDocument()
    expect(within(filas[1]).getByText('Rige hoy')).toBeInTheDocument()
    expect(within(filas[2]).getByText('Categoría Libros')).toBeInTheDocument()
    expect(within(filas[3]).getByText('Producto Tornillo')).toBeInTheDocument()
    expect(within(filas[3]).getByText(/empieza el 1 dic 2026/i)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('checkbox', { name: /cerradas/i }))
    expect(screen.getByText('Categoría Vieja')).toBeInTheDocument()
  })

  it('una regla de exclusión por umbral se explica con palabras, no con el 19 %', async () => {
    apiImpuestos.reglas.mockResolvedValue([{
      ...REGLA, codigoImpuesto: 'EXCL_UMBRAL_PC', impuesto: 'Computadores, excluidos hasta 50 UVT',
      tratamiento: 'EXCLUIDO_HASTA_UMBRAL', tarifa: 0.19,
    }])
    montar()
    const fila = (await screen.findAllByRole('row'))[1]
    expect(within(fila).getByText(/sin iva hasta el tope; sobre el tope, 19,00\s%/i)).toBeInTheDocument()
  })

  it('el estado de las reglas vacías explica qué pasa sin reglas', async () => {
    montar()
    expect(await screen.findByText(/todavía no hay reglas de iva/i)).toBeInTheDocument()
  })

  describe('cerrar y retirar', () => {
    it('una regla que ya rige se cierra sin fecha, tras confirmar, y se explica que las ventas se conservan', async () => {
      apiImpuestos.reglas.mockResolvedValue([REGLA])
      apiImpuestos.cerrarRegla.mockResolvedValue({ ...REGLA, vigente: false })
      montar()
      await screen.findByRole('table', { name: /reglas de iva/i })
      await userEvent.click(screen.getByRole('button', { name: /cerrar/i }))
      const caja = await screen.findByRole('group', { name: /cerrar esta regla/i })
      expect(caja).toHaveFocus()
      expect(within(caja).getByText(/dejará de aplicarse desde hoy/i)).toBeInTheDocument()
      expect(within(caja).getByText(/las ventas ya hechas se siguen explicando con ella/i)).toBeInTheDocument()
      expect(apiImpuestos.cerrarRegla).not.toHaveBeenCalled()
      await userEvent.click(within(caja).getByRole('button', { name: /sí, cerrar/i }))
      expect(apiImpuestos.cerrarRegla).toHaveBeenCalledWith(1, undefined)
      expect(await screen.findByText(/regla cerrada/i)).toBeInTheDocument()
      expect(apiImpuestos.reglas).toHaveBeenCalledTimes(2)
    })

    it('una regla que empieza hoy se cierra con la fecha de hoy', async () => {
      apiImpuestos.reglas.mockResolvedValue([DESDE_HOY])
      apiImpuestos.cerrarRegla.mockResolvedValue({ ...DESDE_HOY, vigente: false })
      montar()
      await screen.findByRole('table', { name: /reglas de iva/i })
      await userEvent.click(screen.getByRole('button', { name: /cerrar/i }))
      const caja = await screen.findByRole('group', { name: /cerrar esta regla/i })
      expect(within(caja).getByText(/hasta el final de hoy/i)).toBeInTheDocument()
      await userEvent.click(within(caja).getByRole('button', { name: /sí, cerrar/i }))
      expect(apiImpuestos.cerrarRegla).toHaveBeenCalledWith(5, '2026-11-06')
    })

    it('una regla que aún no empieza se retira, sin fecha, y se dice que se borra porque no explicó ninguna venta', async () => {
      apiImpuestos.reglas.mockResolvedValue([FUTURA])
      apiImpuestos.cerrarRegla.mockResolvedValue(null)
      montar()
      await screen.findByRole('table', { name: /reglas de iva/i })
      expect(screen.queryByRole('button', { name: /^cerrar/i })).not.toBeInTheDocument()
      await userEvent.click(screen.getByRole('button', { name: /retirar/i }))
      const caja = await screen.findByRole('group', { name: /retirar esta regla/i })
      expect(within(caja).getByText(/se borra/i)).toBeInTheDocument()
      expect(within(caja).getByText(/no explicó ninguna venta/i)).toBeInTheDocument()
      await userEvent.click(within(caja).getByRole('button', { name: /sí, retirar/i }))
      expect(apiImpuestos.cerrarRegla).toHaveBeenCalledWith(3, undefined)
      expect(await screen.findByText(/regla retirada/i)).toBeInTheDocument()
    })

    it('cancelar no toca la regla', async () => {
      apiImpuestos.reglas.mockResolvedValue([REGLA])
      montar()
      await screen.findByRole('table', { name: /reglas de iva/i })
      await userEvent.click(screen.getByRole('button', { name: /cerrar/i }))
      await userEvent.click(await screen.findByRole('button', { name: /cancelar/i }))
      expect(apiImpuestos.cerrarRegla).not.toHaveBeenCalled()
      expect(screen.queryByRole('group', { name: /cerrar esta regla/i })).not.toBeInTheDocument()
    })

    it('el rechazo del servidor se muestra y no da la regla por cerrada', async () => {
      apiImpuestos.reglas.mockResolvedValue([REGLA])
      apiImpuestos.cerrarRegla.mockRejectedValue(new ErrorApi(400, 'La regla ya termina el 2026-12-31.'))
      montar()
      await screen.findByRole('table', { name: /reglas de iva/i })
      await userEvent.click(screen.getByRole('button', { name: /cerrar/i }))
      await userEvent.click(await screen.findByRole('button', { name: /sí, cerrar/i }))
      expect(await screen.findByText(/ya termina el 2026-12-31/i)).toBeInTheDocument()
      expect(screen.queryByText(/regla cerrada/i)).not.toBeInTheDocument()
    })

    it('un doble clic en confirmar manda una sola petición', async () => {
      apiImpuestos.reglas.mockResolvedValue([REGLA])
      let terminar
      apiImpuestos.cerrarRegla.mockReturnValue(new Promise((r) => { terminar = r }))
      montar()
      await screen.findByRole('table', { name: /reglas de iva/i })
      await userEvent.click(screen.getByRole('button', { name: /cerrar/i }))
      await userEvent.dblClick(await screen.findByRole('button', { name: /sí, cerrar/i }))
      expect(apiImpuestos.cerrarRegla).toHaveBeenCalledTimes(1)
      terminar({ ...REGLA, vigente: false })
      await screen.findByText(/regla cerrada/i)
    })
  })
})

describe('regla de IVA nueva', () => {
  beforeEach(() => {
    apiImpuestos.configuracion.mockResolvedValue(RESPONSABLE)
  })

  async function abrir() {
    montar('/impuestos/reglas/nueva')
    return screen.findByRole('heading', { level: 1, name: /nueva regla de iva/i })
  }

  it('el impuesto parte sin elegir: ningún 19 % por omisión', async () => {
    await abrir()
    expect(screen.getByLabelText('Impuesto')).toHaveValue('')
    expect(screen.getByLabelText('Empieza el')).toHaveValue('2026-11-06')
    expect(radio(/todos los productos/i)).toBeChecked()
    expect(screen.getByText(/solo se cobra si la empresa es responsable de iva/i)).toBeInTheDocument()
  })

  it('el formulario inválido no envía nada, marca los errores y enfoca el primero', async () => {
    await abrir()
    await userEvent.click(screen.getByRole('button', { name: /guardar regla/i }))
    const campo = screen.getByLabelText('Impuesto')
    expect(campo).toHaveAttribute('aria-invalid', 'true')
    expect(campo).toHaveFocus()
    expect(screen.getByText('Elige un impuesto.')).toBeInTheDocument()
    expect(apiImpuestos.crearRegla).not.toHaveBeenCalled()
  })

  it('exige la categoría o el producto cuando el alcance lo pide', async () => {
    await abrir()
    await userEvent.selectOptions(screen.getByLabelText('Impuesto'), 'IVA_19')
    await userEvent.click(radio(/una categoría/i))
    await userEvent.click(screen.getByRole('button', { name: /guardar regla/i }))
    expect(screen.getByText('Elige una categoría.')).toBeInTheDocument()
    await userEvent.click(radio(/un producto/i))
    await userEvent.click(screen.getByRole('button', { name: /guardar regla/i }))
    expect(screen.getByText('Busca y elige un producto.')).toBeInTheDocument()
    expect(apiImpuestos.crearRegla).not.toHaveBeenCalled()
  })

  it('la fecha final no puede ser anterior a la inicial y el fundamento tiene tope', async () => {
    await abrir()
    await userEvent.selectOptions(screen.getByLabelText('Impuesto'), 'IVA_19')
    await userEvent.type(screen.getByLabelText(/termina el/i), '2026-11-01')
    await userEvent.click(screen.getByRole('button', { name: /guardar regla/i }))
    expect(screen.getByText(/no puede ser anterior/i)).toBeInTheDocument()
    expect(apiImpuestos.crearRegla).not.toHaveBeenCalled()
  })

  it('crea una regla general y vuelve a la lista con un aviso', async () => {
    apiImpuestos.crearRegla.mockResolvedValue(REGLA)
    apiImpuestos.reglas.mockResolvedValue([REGLA])
    await abrir()
    await userEvent.selectOptions(screen.getByLabelText('Impuesto'), 'IVA_19')
    await userEvent.type(screen.getByLabelText(/fundamento/i), '  Estatuto  ')
    await userEvent.click(screen.getByRole('button', { name: /guardar regla/i }))
    expect(apiImpuestos.crearRegla).toHaveBeenCalledWith({
      categoriaId: null, productoId: null, codigoImpuesto: 'IVA_19',
      vigenteDesde: '2026-11-06', vigenteHasta: null, fundamento: 'Estatuto',
    })
    expect(await screen.findByText(/regla creada/i)).toBeInTheDocument()
    expect(await screen.findByRole('table', { name: /reglas de iva/i })).toBeInTheDocument()
  })

  it('crea una regla de categoría', async () => {
    apiImpuestos.crearRegla.mockResolvedValue(DE_CATEGORIA)
    apiImpuestos.reglas.mockResolvedValue([DE_CATEGORIA])
    await abrir()
    await userEvent.selectOptions(screen.getByLabelText('Impuesto'), 'EXENTO')
    await userEvent.click(radio(/una categoría/i))
    await userEvent.selectOptions(screen.getByLabelText('Categoría'), '3')
    await userEvent.click(screen.getByRole('button', { name: /guardar regla/i }))
    expect(apiImpuestos.crearRegla).toHaveBeenCalledWith({
      categoriaId: 3, productoId: null, codigoImpuesto: 'EXENTO',
      vigenteDesde: '2026-11-06', vigenteHasta: null, fundamento: null,
    })
  })

  it('crea una regla de producto buscándolo en el servidor', async () => {
    apiImpuestos.buscarProductos.mockResolvedValue([
      { productoId: 8, nombre: 'Tornillo 1/4', codigo: 'TOR-1' },
      { productoId: 9, nombre: 'Tornillo 1/2', codigo: 'TOR-2' },
    ])
    apiImpuestos.crearRegla.mockResolvedValue(FUTURA)
    apiImpuestos.reglas.mockResolvedValue([FUTURA])
    await abrir()
    await userEvent.selectOptions(screen.getByLabelText('Impuesto'), 'IVA_19')
    await userEvent.click(radio(/un producto/i))
    await userEvent.type(screen.getByLabelText(/buscar producto/i), 'torni')
    await userEvent.click(screen.getByRole('button', { name: /^buscar$/i }))
    expect(apiImpuestos.buscarProductos).toHaveBeenCalledWith('torni')
    await userEvent.click(await screen.findByRole('button', { name: /tornillo 1\/4/i }))
    expect(screen.getByText(/producto elegido/i)).toHaveTextContent('Tornillo 1/4')
    await userEvent.click(screen.getByRole('button', { name: /guardar regla/i }))
    expect(apiImpuestos.crearRegla.mock.calls[0][0]).toMatchObject({ productoId: 8, categoriaId: null })
  })

  it('buscar sin texto no pide nada al servidor', async () => {
    await abrir()
    await userEvent.click(radio(/un producto/i))
    await userEvent.click(screen.getByRole('button', { name: /^buscar$/i }))
    expect(screen.getByText(/escribe el nombre o el código/i)).toBeInTheDocument()
    expect(apiImpuestos.buscarProductos).not.toHaveBeenCalled()
  })

  it('una búsqueda sin resultados lo dice', async () => {
    apiImpuestos.buscarProductos.mockResolvedValue([])
    await abrir()
    await userEvent.click(radio(/un producto/i))
    await userEvent.type(screen.getByLabelText(/buscar producto/i), 'zzz')
    await userEvent.click(screen.getByRole('button', { name: /^buscar$/i }))
    expect(await screen.findByText(/no hay productos con esa búsqueda/i)).toBeInTheDocument()
  })

  it('un solape (409) muestra lo que dice el servidor y deja reintentar', async () => {
    apiImpuestos.crearRegla.mockRejectedValue(new ErrorApi(409, 'Ya hay la regla general con una vigencia que se solapa.'))
    await abrir()
    await userEvent.selectOptions(screen.getByLabelText('Impuesto'), 'IVA_19')
    await userEvent.click(screen.getByRole('button', { name: /guardar regla/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/se solapa/i)
    expect(screen.getByRole('button', { name: /guardar regla/i })).toBeEnabled()
  })

  it.each([400, 422])('un %s muestra su detalle', async (estado) => {
    apiImpuestos.crearRegla.mockRejectedValue(new ErrorApi(estado, 'Motivo del servidor.'))
    await abrir()
    await userEvent.selectOptions(screen.getByLabelText('Impuesto'), 'IVA_19')
    await userEvent.click(screen.getByRole('button', { name: /guardar regla/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Motivo del servidor.')
  })

  it('deshabilita el botón mientras envía y no duplica', async () => {
    let terminar
    apiImpuestos.crearRegla.mockReturnValue(new Promise((r) => { terminar = r }))
    apiImpuestos.reglas.mockResolvedValue([REGLA])
    await abrir()
    await userEvent.selectOptions(screen.getByLabelText('Impuesto'), 'IVA_19')
    await userEvent.dblClick(screen.getByRole('button', { name: /guardar regla/i }))
    expect(screen.getByRole('button', { name: /guardando/i })).toBeDisabled()
    expect(apiImpuestos.crearRegla).toHaveBeenCalledTimes(1)
    terminar(REGLA)
    await screen.findByText(/regla creada/i)
  })

  it('si no cargan el catálogo o las categorías, lo dice y reintenta', async () => {
    apiImpuestos.catalogo
      .mockRejectedValueOnce(new ErrorApi(500, 'Falló.'))
      .mockResolvedValueOnce(CATALOGO)
    montar('/impuestos/reglas/nueva')
    expect(await screen.findByRole('alert')).toHaveTextContent('Falló.')
    await userEvent.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByLabelText('Impuesto')).toBeInTheDocument()
  })

  it('un 403 al cargar muestra sin permiso', async () => {
    apiImpuestos.categorias.mockRejectedValue(new ErrorApi(403, 'no'))
    montar('/impuestos/reglas/nueva')
    expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument()
  })

  it('una ruta desconocida lleva a la pantalla principal', async () => {
    montar('/impuestos/otra/cosa')
    expect(await screen.findByText(/responsable de iva/i)).toBeInTheDocument()
    await waitFor(() => expect(apiImpuestos.configuracion).toHaveBeenCalled())
  })
})

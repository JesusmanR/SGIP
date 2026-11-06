import { beforeEach, describe, expect, it, vi } from 'vitest'

import { pedir } from '../api.js'
import { apiImpuestos } from './impuestos.js'

vi.mock('../api.js', () => ({ pedir: vi.fn() }))

const CONFIG = {
  responsableIva: true, aplicaExclusionUmbral: false, practicaRetenciones: true,
  tarifaIca: 0.00414, manejaEnvios: false, cotizacionVigenciaDias: 15,
}

describe('apiImpuestos', () => {
  beforeEach(() => {
    vi.mocked(pedir).mockReset()
    vi.mocked(pedir).mockResolvedValue({})
  })

  it('lee la configuración, el catálogo y las reglas con GET', async () => {
    await apiImpuestos.configuracion()
    expect(pedir).toHaveBeenLastCalledWith('/empresa/tributacion')
    await apiImpuestos.catalogo()
    expect(pedir).toHaveBeenLastCalledWith('/empresa/tributacion/impuestos')
    await apiImpuestos.reglas()
    expect(pedir).toHaveBeenLastCalledWith('/empresa/tributacion/reglas')
  })

  it('guarda con PUT los seis campos y nada más', async () => {
    await apiImpuestos.guardar({ ...CONFIG, configuracionCompleta: true })
    expect(pedir).toHaveBeenLastCalledWith('/empresa/tributacion', { method: 'PUT', body: CONFIG })
  })

  it('crea una regla con POST, campo a campo', async () => {
    await apiImpuestos.crearRegla({
      categoriaId: 3, productoId: null, codigoImpuesto: 'IVA_5',
      vigenteDesde: '2026-11-06', vigenteHasta: null, fundamento: 'x', extra: 1,
    })
    expect(pedir).toHaveBeenLastCalledWith('/empresa/tributacion/reglas', {
      method: 'POST',
      body: {
        categoriaId: 3, productoId: null, codigoImpuesto: 'IVA_5',
        vigenteDesde: '2026-11-06', vigenteHasta: null, fundamento: 'x',
      },
    })
  })

  it('cierra con o sin fecha', async () => {
    await apiImpuestos.cerrarRegla(8)
    expect(pedir).toHaveBeenLastCalledWith('/empresa/tributacion/reglas/8/cerrar', { method: 'POST' })
    await apiImpuestos.cerrarRegla(8, '2026-11-06')
    expect(pedir).toHaveBeenLastCalledWith(
      '/empresa/tributacion/reglas/8/cerrar?hasta=2026-11-06', { method: 'POST' },
    )
  })

  it('busca productos en el servidor, pocos a la vez', async () => {
    await apiImpuestos.buscarProductos('tuerca & perno')
    expect(pedir).toHaveBeenLastCalledWith('/productos?busqueda=tuerca%20%26%20perno&limite=10')
  })

  it('trae las categorías para elegir', async () => {
    await apiImpuestos.categorias()
    expect(pedir).toHaveBeenLastCalledWith('/categorias')
  })
})

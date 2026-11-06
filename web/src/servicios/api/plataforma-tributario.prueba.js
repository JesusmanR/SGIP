import { beforeEach, describe, expect, it, vi } from 'vitest'

import { pedir } from '../api.js'
import { apiPlataformaTributario } from './plataforma-tributario.js'

vi.mock('../api.js', () => ({ pedir: vi.fn() }))

describe('apiPlataformaTributario', () => {
  beforeEach(() => {
    vi.mocked(pedir).mockReset()
    vi.mocked(pedir).mockResolvedValue({})
  })

  it('consulta UVT, impuestos y alerta con GET', async () => {
    await apiPlataformaTributario.listarUvt()
    expect(pedir).toHaveBeenLastCalledWith('/plataforma/tributario/uvt')
    await apiPlataformaTributario.listarImpuestos()
    expect(pedir).toHaveBeenLastCalledWith('/plataforma/tributario/impuestos')
    await apiPlataformaTributario.alertaUvt()
    expect(pedir).toHaveBeenLastCalledWith('/plataforma/tributario/alerta-uvt')
  })

  it('fija la UVT de un año con PUT y sin campos de más', async () => {
    await apiPlataformaTributario.fijarUvt(2027, { valor: 50000, resolucion: 'R1', motivo: '', anio: 1 })
    expect(pedir).toHaveBeenLastCalledWith('/plataforma/tributario/uvt/2027', {
      method: 'PUT', body: { valor: 50000, resolucion: 'R1', motivo: null },
    })
  })

  it('cambia un impuesto por su código, sin tratamiento', async () => {
    await apiPlataformaTributario.cambiarImpuesto('IVA 19', { tarifa: 0.19, umbralUvt: null, fundamento: 'x', motivo: 'm', tratamiento: 'GRAVADO' })
    expect(pedir).toHaveBeenLastCalledWith('/plataforma/tributario/impuestos/IVA%2019', {
      method: 'PUT', body: { tarifa: 0.19, umbralUvt: null, fundamento: 'x', motivo: 'm' },
    })
  })
})

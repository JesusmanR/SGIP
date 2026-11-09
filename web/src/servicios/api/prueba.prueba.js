import { beforeEach, describe, expect, it, vi } from 'vitest'

import { pedir } from '../api.js'
import { apiPrueba } from './prueba.js'

vi.mock('../api.js', () => ({ pedir: vi.fn() }))

describe('apiPrueba', () => {
  beforeEach(() => {
    vi.mocked(pedir).mockReset()
    vi.mocked(pedir).mockResolvedValue({ estado: 'ACTIVA', venceEn: null, segundosRestantes: null })
  })

  it('pide el estado de la prueba con un GET', async () => {
    expect(await apiPrueba.estado()).toEqual({ estado: 'ACTIVA', venceEn: null, segundosRestantes: null })
    expect(pedir).toHaveBeenLastCalledWith('/empresa/prueba')
  })
})

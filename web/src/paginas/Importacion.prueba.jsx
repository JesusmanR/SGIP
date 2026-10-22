/**
 * Pantalla P-07 · Importación de lista de precios.
 *
 * Su especificación empieza diciendo que «debe dejar claro en todo
 * momento que la lectura no modifica el catálogo y que solo la
 * aplicación aprobada lo hace». Casi todo lo que se prueba aquí sale de
 * esa frase: el aviso de que el catálogo aún no cambia, la confirmación
 * explícita antes de aplicar, y que quien no es administrador no pueda
 * decidir.
 */
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import Importacion from './Importacion.jsx'
import { api } from '../servicios/api.js'
import { guardarSesion } from '../servicios/sesion.js'

vi.mock('../servicios/api.js', () => ({
  api: {
    proveedores: vi.fn(),
    cargarLista: vi.fn(),
    verCarga: vi.fn(),
    aplicarCarga: vi.fn(),
    rechazarCarga: vi.fn(),
  },
  ErrorApi: class extends Error {},
}))

const PROVEEDORES = [{ id: 1, razonSocial: 'Mayorista Tech SAS' }]

const MUESTRA = [
  { numeroFila: 31, productoId: 9, referencia: 'MT-0031', descripcion: 'Teclado mecánico',
    costo: null, costoAnterior: 185000, aceptado: false,
    motivoRechazo: 'El costo «abc» no es un número.', motivoCodigo: 'COSTO_NO_NUMERICO' },
  { numeroFila: 55, productoId: null, referencia: 'MT-5590', descripcion: null,
    costo: null, costoAnterior: null, aceptado: false,
    motivoRechazo: 'La referencia MT-5590 no existe en el catálogo de esta empresa.',
    motivoCodigo: 'REFERENCIA_INEXISTENTE' },
  { numeroFila: 12, productoId: 1, referencia: 'MT-9841', descripcion: 'Portátil económico',
    costo: 2180000, costoAnterior: 2100000, aceptado: true,
    motivoRechazo: null, motivoCodigo: null },
  { numeroFila: 13, productoId: 2, referencia: 'MT-9842', descripcion: 'Portátil gama media',
    costo: 2310000, costoAnterior: 2200000, aceptado: true,
    motivoRechazo: null, motivoCodigo: null },
]

const CRUCES = [
  { productoId: 2, codigo: 'MT-9842', nombre: 'Portátil gama media', costo: 2310000,
    precioAntes: 2640000, precioDespues: 2772000, umbral: 2700000,
    tratamientoAntes: 'EXCLUIDO', tratamientoDespues: 'GRAVADO' },
]

const carga = (extra = {}) => ({
  cargaId: 77, proveedorId: 1, proveedor: 'Mayorista Tech SAS',
  nombreArchivo: 'lista-septiembre.xlsx', estado: 'VALIDADA',
  fecha: '2026-10-22T10:00:00', fechaAplicacion: null,
  leidos: 412, aceptados: 398, rechazados: 14,
  muestra: MUESTRA, cruces: CRUCES, ...extra,
})

const archivo = () => new File(['codigo,costo\nMT-9841,2180000\n'],
  'lista-septiembre.xlsx', { type: 'text/csv' })

function montar() {
  return render(<MemoryRouter><Importacion /></MemoryRouter>)
}

/** Sube un archivo y espera a que aparezca la vista previa. */
async function subirLista(teclado, devuelve = carga()) {
  api.cargarLista.mockResolvedValue(devuelve)
  await teclado.selectOptions(await screen.findByLabelText(/proveedor/i), '1')
  await teclado.upload(screen.getByLabelText(/archivo/i), archivo())
  await screen.findByText(/vista previa/i)
}

describe('importación de lista de precios', () => {
  let teclado
  beforeEach(() => {
    teclado = userEvent.setup()
    sessionStorage.clear()
    guardarSesion({ token: 't', nombre: 'Jesús', rol: 'ADMINISTRADOR' })
    api.proveedores.mockResolvedValue(PROVEEDORES)
  })
  afterEach(() => vi.clearAllMocks())


  describe('leer no cambia el catálogo', () => {
    it('lo dice mientras la carga está validada', async () => {
      montar()
      await subirLista(teclado)
      expect(screen.getByText(/el catálogo aún no cambia/i)).toBeInTheDocument()
    })

    it('y deja de decirlo cuando ya se aplicó', async () => {
      montar()
      await subirLista(teclado)
      api.aplicarCarga.mockResolvedValue(carga({ estado: 'APLICADA', cruces: [] }))

      await teclado.click(screen.getByRole('button', { name: /aplicar al catálogo/i }))
      await teclado.click(screen.getByRole('button', { name: /sí, aplicar/i }))

      await waitFor(() =>
        expect(screen.queryByText(/el catálogo aún no cambia/i)).not.toBeInTheDocument())
      expect(screen.getByText(/398 referencias actualizadas/i)).toBeInTheDocument()
    })

    it('subir el archivo no aplica nada por sí solo', async () => {
      montar()
      await subirLista(teclado)
      expect(api.aplicarCarga).not.toHaveBeenCalled()
    })
  })


  describe('aplicar exige confirmación explícita', () => {
    it('un solo clic no aplica', async () => {
      montar()
      await subirLista(teclado)

      await teclado.click(screen.getByRole('button', { name: /aplicar al catálogo/i }))

      expect(api.aplicarCarga).not.toHaveBeenCalled()
      expect(screen.getByRole('button', { name: /sí, aplicar/i })).toBeInTheDocument()
    })

    it('y la confirmación dice qué alcance tiene', async () => {
      montar()
      await subirLista(teclado)
      await teclado.click(screen.getByRole('button', { name: /aplicar al catálogo/i }))

      const dialogo = screen.getByRole('alertdialog')
      // No basta con preguntar «¿seguro?»: lo que hay que confirmar es
      // cuántos costos cambian y cuántas referencias pasan a causar IVA.
      expect(dialogo).toHaveTextContent('398')
      expect(dialogo).toHaveTextContent(/causar IVA/i)
    })

    it('se puede volver a revisar sin aplicar', async () => {
      montar()
      await subirLista(teclado)
      await teclado.click(screen.getByRole('button', { name: /aplicar al catálogo/i }))
      await teclado.click(screen.getByRole('button', { name: /volver a revisar/i }))

      expect(api.aplicarCarga).not.toHaveBeenCalled()
      expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    })

    it('el segundo clic sí aplica', async () => {
      montar()
      await subirLista(teclado)
      api.aplicarCarga.mockResolvedValue(carga({ estado: 'APLICADA', cruces: [] }))

      await teclado.click(screen.getByRole('button', { name: /aplicar al catálogo/i }))
      await teclado.click(screen.getByRole('button', { name: /sí, aplicar/i }))

      await waitFor(() => expect(api.aplicarCarga).toHaveBeenCalledWith(77))
    })

    it('rechazar no la pide, porque no cambia nada', async () => {
      montar()
      await subirLista(teclado)
      api.rechazarCarga.mockResolvedValue(carga({ estado: 'RECHAZADA', cruces: [] }))

      await teclado.click(screen.getByRole('button', { name: /rechazar carga/i }))

      await waitFor(() => expect(api.rechazarCarga).toHaveBeenCalledWith(77))
      expect(await screen.findByText(/carga descartada/i)).toBeInTheDocument()
    })
  })


  describe('la vista previa', () => {
    it('cada rechazo lleva su categoría y su motivo', async () => {
      montar()
      await subirLista(teclado)

      const filaCosto = screen.getAllByRole('row')
        .find((f) => f.textContent.includes('MT-0031'))
      expect(within(filaCosto).getByText('Costo inválido')).toBeInTheDocument()
      // La pastilla dice de qué clase es el problema; el motivo dice
      // cuál fue, que es lo que se usa para corregir el archivo.
      expect(filaCosto).toHaveTextContent('no es un número')
      expect(filaCosto).toHaveTextContent('fila 31')

      const filaSin = screen.getAllByRole('row')
        .find((f) => f.textContent.includes('MT-5590'))
      expect(within(filaSin).getByText('Sin referencia')).toBeInTheDocument()
    })

    it('señala las que cruzan el umbral entre las aceptadas', async () => {
      montar()
      await subirLista(teclado)

      const cruza = screen.getAllByRole('row')
        .find((f) => f.textContent.includes('MT-9842'))
      expect(within(cruza).getByText('Cruza el umbral')).toBeInTheDocument()

      const normal = screen.getAllByRole('row')
        .find((f) => f.textContent.includes('MT-9841'))
      expect(within(normal).getByText('Aceptado')).toBeInTheDocument()
    })

    it('avisa aparte de las que pasarían a causar IVA', async () => {
      montar()
      await subirLista(teclado)
      expect(screen.getByText(/una referencia pasaría a causar iva/i)).toBeInTheDocument()
      expect(screen.getByText(/portátil gama media/i, { selector: '.sub-linea' }))
        .toBeInTheDocument()
    })

    it('y no avisa cuando no hay ninguna', async () => {
      montar()
      await subirLista(teclado, carga({ cruces: [] }))
      expect(screen.queryByText(/causar iva/i)).not.toBeInTheDocument()
    })

    it('dice cuántos registros se muestran de cuántos', async () => {
      montar()
      await subirLista(teclado)
      expect(screen.getByText(/se muestran 4 de 412/i)).toBeInTheDocument()
    })
  })


  describe('quién puede decidir', () => {
    it('un vendedor ve la carga pero no la aplica', async () => {
      guardarSesion({ token: 't', nombre: 'Ana', rol: 'VENDEDOR' })
      montar()
      await subirLista(teclado)

      expect(screen.getByText(/vista previa/i)).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /aplicar al catálogo/i }))
        .not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /rechazar carga/i }))
        .not.toBeInTheDocument()
    })
  })


  describe('lo que puede salir mal', () => {
    it('sin proveedor no se envía el archivo', async () => {
      montar()
      await screen.findByLabelText(/proveedor/i)
      await teclado.upload(screen.getByLabelText(/archivo/i), archivo())

      expect(api.cargarLista).not.toHaveBeenCalled()
      expect(await screen.findByRole('alert')).toHaveTextContent(/proveedor/i)
    })

    it('el motivo del rechazo del archivo es el del servidor', async () => {
      montar()
      api.cargarLista.mockRejectedValue(
        new Error('El archivo supera las 20000 filas admitidas.'))
      await teclado.selectOptions(await screen.findByLabelText(/proveedor/i), '1')
      await teclado.upload(screen.getByLabelText(/archivo/i), archivo())

      expect(await screen.findByRole('alert')).toHaveTextContent('20000 filas')
    })

    it('una carga sin ninguna fila aceptada no se puede aplicar', async () => {
      montar()
      await subirLista(teclado, carga({ aceptados: 0, rechazados: 412, cruces: [] }))
      expect(screen.getByRole('button', { name: /aplicar al catálogo/i })).toBeDisabled()
    })
  })
})

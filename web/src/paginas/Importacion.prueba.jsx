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
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import Importacion from './Importacion.jsx'
import { api } from '../servicios/api.js'
import { guardarSesion } from '../servicios/sesion.js'
import { NUMERO } from '../servicios/formato.js'

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
      expect(screen.getByText(/cambia su ganancia/i)).toBeInTheDocument()
      expect(document.body.textContent).not.toMatch(/margen/i)
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
    // Cambia el sentido de la prueba anterior («un vendedor ve la carga
    // pero no la aplica»): el backend responde 403 a todo el controlador
    // de importaciones para quien no es administrador, de modo que un
    // vendedor nunca llega a tener una carga que ver. La pantalla lo dice
    // de entrada en lugar de ofrecer un formulario que siempre fallaría.
    it('quien no es administrador recibe «sin permiso» y no pide nada', async () => {
      guardarSesion({ token: 't', nombre: 'Ana', rol: 'VENDEDOR' })
      montar()

      expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument()
      expect(api.proveedores).not.toHaveBeenCalled()
      expect(screen.queryByLabelText(/archivo/i)).not.toBeInTheDocument()
      expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    })

    it('un 403 del servidor también se presenta como falta de permiso', async () => {
      api.proveedores.mockRejectedValue(Object.assign(new Error('Prohibido'), { estado: 403 }))
      montar()
      expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument()
    })
  })


  describe('rediseño: encabezado y primer paso', () => {
    it('tiene un único h1 y conserva los tres pasos', async () => {
      montar()
      await screen.findByLabelText(/proveedor/i)
      expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
      const pasos = within(screen.getByRole('list', { name: /progreso/i })).getAllByRole('listitem')
      expect(pasos.map((p) => p.textContent)).toEqual(
        expect.arrayContaining([expect.stringMatching(/cargar archivo/i),
          expect.stringMatching(/revisar/i), expect.stringMatching(/aplicar/i)]))
    })

    it('el estado de la carga se lee en palabras, no en el código del backend', async () => {
      montar()
      await subirLista(teclado)
      const pasos = screen.getByRole('list', { name: /progreso/i })
      expect(pasos).toHaveTextContent(/validada/i)
      expect(pasos).not.toHaveTextContent('VALIDADA')
    })

    it('si no se pudieron traer los proveedores ofrece reintentar', async () => {
      api.proveedores.mockRejectedValueOnce(new Error('x'))
      montar()
      expect(await screen.findByRole('alert')).toHaveTextContent(/no se pudieron cargar los proveedores/i)
      api.proveedores.mockResolvedValue(PROVEEDORES)
      await teclado.click(screen.getByRole('button', { name: /reintentar/i }))
      expect(await screen.findByLabelText(/proveedor/i)).toBeInTheDocument()
      expect(api.proveedores).toHaveBeenCalledTimes(2)
    })

    it('sin proveedores explica que hace falta uno y enlaza a crearlo', async () => {
      api.proveedores.mockResolvedValue([])
      montar()
      expect(await screen.findByText(/todavía no hay proveedores/i)).toBeInTheDocument()
      expect(screen.getByRole('link', { name: /proveedores/i })).toHaveAttribute('href', '/proveedores')
    })
  })


  describe('rediseño: errores que dicen qué hacer', () => {
    it('cada fila rechazada dice qué hacer, además de qué falló', async () => {
      montar()
      await subirLista(teclado)
      const filaCosto = screen.getAllByRole('row').find((f) => f.textContent.includes('MT-0031'))
      expect(filaCosto).toHaveTextContent(/corrige el costo/i)
      const filaSin = screen.getAllByRole('row').find((f) => f.textContent.includes('MT-5590'))
      expect(filaSin).toHaveTextContent(/registra el producto|corrige la referencia/i)
    })

    it('un resumen cuenta las filas que no entrarán y qué opciones hay', async () => {
      montar()
      await subirLista(teclado)
      const resumen = screen.getByText(/14 filas no se aplicarán/i)
      expect(resumen).toBeInTheDocument()
      expect(resumen.closest('[role="status"]')).toHaveTextContent(/vuelve a subirlo|aplica solo/i)
    })

    it('sin rechazos no hay resumen de errores', async () => {
      montar()
      await subirLista(teclado, carga({ rechazados: 0, aceptados: 412, muestra: [MUESTRA[2]], cruces: [] }))
      expect(screen.queryByText(/no se aplicarán/i)).not.toBeInTheDocument()
    })

    it('un archivo rechazado por el servidor añade qué hacer', async () => {
      montar()
      api.cargarLista.mockRejectedValue(new Error('El archivo supera las 20000 filas admitidas.'))
      await teclado.selectOptions(await screen.findByLabelText(/proveedor/i), '1')
      await teclado.upload(screen.getByLabelText(/archivo/i), archivo())
      const alerta = await screen.findByRole('alert')
      expect(alerta).toHaveTextContent('20000 filas')
      expect(alerta).toHaveTextContent(/corrige el archivo y vuelve a subirlo/i)
    })

    it('si el archivo falla se puede volver a intentar sin recargar', async () => {
      montar()
      api.cargarLista.mockRejectedValueOnce(new Error('El encabezado no tiene la columna «costo».'))
      await teclado.selectOptions(await screen.findByLabelText(/proveedor/i), '1')
      await teclado.upload(screen.getByLabelText(/archivo/i), archivo())
      await screen.findByRole('alert')
      api.cargarLista.mockResolvedValue(carga())
      await teclado.upload(screen.getByLabelText(/archivo/i), archivo())
      expect(await screen.findByText(/vista previa/i)).toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })

    it('si aplicar falla muestra el motivo y la carga sigue sin aplicarse', async () => {
      montar()
      await subirLista(teclado)
      api.aplicarCarga.mockRejectedValue(new Error('La carga ya fue aplicada.'))
      await teclado.click(screen.getByRole('button', { name: /aplicar al catálogo/i }))
      await teclado.click(screen.getByRole('button', { name: /sí, aplicar/i }))
      expect(await screen.findByText('La carga ya fue aplicada.')).toBeInTheDocument()
      expect(screen.queryByText(/referencias actualizadas/i)).not.toBeInTheDocument()
    })

    it('si rechazar falla muestra el motivo', async () => {
      montar()
      await subirLista(teclado)
      api.rechazarCarga.mockRejectedValue(new Error('No se pudo descartar la carga.'))
      await teclado.click(screen.getByRole('button', { name: /rechazar carga/i }))
      expect(await screen.findByRole('alert')).toHaveTextContent('No se pudo descartar la carga.')
    })
  })


  describe('rediseño: confirmación y teclado', () => {
    it('al pedir confirmar, el foco va a la opción segura', async () => {
      montar()
      await subirLista(teclado)
      await teclado.click(screen.getByRole('button', { name: /aplicar al catálogo/i }))
      expect(screen.getByRole('button', { name: /volver a revisar/i })).toHaveFocus()
    })

    it('con una sola referencia que cruza, la confirmación concuerda en singular', async () => {
      montar()
      await subirLista(teclado)
      await teclado.click(screen.getByRole('button', { name: /aplicar al catálogo/i }))
      expect(screen.getByRole('alertdialog'))
        .toHaveTextContent(/1 referencia pasará a causar iva/i)
    })

    it('con varias que cruzan, en plural', async () => {
      montar()
      const dos = [...CRUCES, { ...CRUCES[0], productoId: 3, codigo: 'MT-9843' }]
      await subirLista(teclado, carga({ cruces: dos }))
      await teclado.click(screen.getByRole('button', { name: /aplicar al catálogo/i }))
      expect(screen.getByRole('alertdialog'))
        .toHaveTextContent(/2 referencias pasarán a causar iva/i)
    })

    it('Escape cierra la confirmación sin aplicar', async () => {
      montar()
      await subirLista(teclado)
      await teclado.click(screen.getByRole('button', { name: /aplicar al catálogo/i }))
      await teclado.keyboard('{Escape}')
      expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
      expect(api.aplicarCarga).not.toHaveBeenCalled()
    })

    it('con teclado se aplica: foco en la opción segura, Tab al botón de aplicar y Enter', async () => {
      montar()
      await subirLista(teclado)
      api.aplicarCarga.mockResolvedValue(carga({ estado: 'APLICADA', cruces: [] }))
      await teclado.click(screen.getByRole('button', { name: /aplicar al catálogo/i }))
      await teclado.tab()
      expect(screen.getByRole('button', { name: /sí, aplicar/i })).toHaveFocus()
      await teclado.keyboard('{Enter}')
      await waitFor(() => expect(api.aplicarCarga).toHaveBeenCalledWith(77))
    })

    it('tras aplicar se puede importar otra lista', async () => {
      montar()
      await subirLista(teclado, carga({ estado: 'APLICADA', cruces: [] }))
      await teclado.click(screen.getByRole('button', { name: /importar otra lista/i }))
      expect(await screen.findByLabelText(/proveedor/i)).toBeInTheDocument()
    })

    it('la zona de archivo es una etiqueta alcanzable con el teclado', async () => {
      montar()
      await screen.findByLabelText(/proveedor/i)
      const entrada = screen.getByLabelText(/archivo/i)
      await teclado.tab()
      await teclado.tab()
      expect(entrada).toHaveFocus()
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


  describe('ronda 1: confirmación, foco, envío doble, arrastre, singular y cifras', () => {
    const haciaExcluido = { ...CRUCES[0], tratamientoAntes: 'GRAVADO', tratamientoDespues: 'EXCLUIDO' }

    it('la confirmación también cuenta las que dejan de causar IVA', async () => {
      montar()
      await subirLista(teclado, carga({ cruces: [haciaExcluido] }))
      await teclado.click(screen.getByRole('button', { name: /aplicar al catálogo/i }))
      expect(screen.getByRole('alertdialog'))
        .toHaveTextContent(/1 referencia dejará de causar iva/i)
    })

    it('con cruces en las dos direcciones dice las dos', async () => {
      montar()
      await subirLista(teclado, carga({ cruces: [CRUCES[0], { ...haciaExcluido, productoId: 5 }] }))
      await teclado.click(screen.getByRole('button', { name: /aplicar al catálogo/i }))
      const dialogo = screen.getByRole('alertdialog')
      expect(dialogo).toHaveTextContent(/1 referencia pasará a causar iva/i)
      expect(dialogo).toHaveTextContent(/1 referencia dejará de causar iva/i)
    })

    it('Escape devuelve el foco a «Aplicar al catálogo»', async () => {
      montar()
      await subirLista(teclado)
      await teclado.click(screen.getByRole('button', { name: /aplicar al catálogo/i }))
      await teclado.keyboard('{Escape}')
      expect(screen.getByRole('button', { name: /aplicar al catálogo/i })).toHaveFocus()
    })

    it('«Volver a revisar» devuelve el foco a «Aplicar al catálogo»', async () => {
      montar()
      await subirLista(teclado)
      await teclado.click(screen.getByRole('button', { name: /aplicar al catálogo/i }))
      await teclado.click(screen.getByRole('button', { name: /volver a revisar/i }))
      expect(screen.getByRole('button', { name: /aplicar al catálogo/i })).toHaveFocus()
    })

    it('un doble clic en «Sí, aplicar» envía una sola vez', async () => {
      montar()
      await subirLista(teclado)
      api.aplicarCarga.mockReturnValue(new Promise(() => {}))
      await teclado.click(screen.getByRole('button', { name: /aplicar al catálogo/i }))
      await teclado.dblClick(screen.getByRole('button', { name: /sí, aplicar/i }))
      expect(api.aplicarCarga).toHaveBeenCalledTimes(1)
    })

    it('soltar un archivo mientras se lee otro no lo envía', async () => {
      montar()
      api.cargarLista.mockReturnValue(new Promise(() => {}))
      await teclado.selectOptions(await screen.findByLabelText(/proveedor/i), '1')
      await teclado.upload(screen.getByLabelText(/archivo/i), archivo())
      await screen.findByText(/leyendo el archivo/i)
      const zona = screen.getByLabelText(/archivo/i).closest('label')
      fireEvent.drop(zona, { dataTransfer: { files: [archivo()] } })
      expect(api.cargarLista).toHaveBeenCalledTimes(1)
    })

    it('con una sola fila aceptada y una cruzada todo va en singular', async () => {
      montar()
      const una = carga({ aceptados: 1, rechazados: 1, muestra: MUESTRA.slice(0, 1).concat(MUESTRA[3]) })
      await subirLista(teclado, una)
      expect(screen.getByText(/aplica solo la aceptada/i)).toBeInTheDocument()
      expect(screen.getByText(/cruza el umbral del artículo/i)).toBeInTheDocument()
      expect(screen.getByText(/entra el 1 aceptado/i)).toBeInTheDocument()
      await teclado.click(screen.getByRole('button', { name: /aplicar al catálogo/i }))
      expect(screen.getByRole('alertdialog')).toHaveTextContent(/1 costo del catálogo/i)
    })

    it('una referencia actualizada, en singular', async () => {
      montar()
      await subirLista(teclado, carga({ estado: 'APLICADA', aceptados: 1, cruces: [] }))
      expect(screen.getByText('1 referencia actualizada')).toBeInTheDocument()
    })

    it('las cifras llevan el separador de miles del proyecto', async () => {
      montar()
      await subirLista(teclado, carga({ leidos: 12345, aceptados: 12000, rechazados: 345 }))
      expect(screen.getByText(NUMERO.format(12345), { selector: '.cifra-valor' })).toBeInTheDocument()
      expect(screen.getByText(NUMERO.format(12000), { selector: '.cifra-valor' })).toBeInTheDocument()
      expect(screen.getByText(NUMERO.format(345), { selector: '.cifra-valor' })).toBeInTheDocument()
      expect(screen.getByText((t) => t.includes(`4 de ${NUMERO.format(12345)} registros`))).toBeInTheDocument()
    })

    it('el tope de filas se escribe con el mismo formato', async () => {
      montar()
      await screen.findByLabelText(/proveedor/i)
      // Cadena y no expresión regular: el punto del separador casaría con cualquier carácter.
      expect(screen.getByText((t) => t.includes(`${NUMERO.format(20000)} filas`))).toBeInTheDocument()
    })
  })
})

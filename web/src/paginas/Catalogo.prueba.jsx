/**
 * Pantalla P-03 · Catálogo.
 *
 * Lo que se prueba aquí es lo que decide qué ve quien vende: cuándo
 * aparece cada anotación de precio y cuándo no. No es cosmético. Un
 * vendedor que ofrece el crédito mirando una cifra a la que le falta el
 * impuesto se queda corto por ese importe, y esa es justamente la fila
 * que el RF-33 pide mostrar completa.
 *
 * Hasta ahora ninguna prueba miraba ninguna pantalla: las del proyecto
 * cubrían los servicios, y las páginas solo las revisaba una fotografía
 * tomada a mano antes de cada entrega.
 */
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import Catalogo from './Catalogo.jsx'
import { api } from '../servicios/api.js'
import { guardarSesion } from '../servicios/sesion.js'
import { CATEGORIAS, CEL, MON, PC, PROVEEDORES, fila } from '../prueba/productos.js'

vi.mock('../servicios/api.js', () => ({
  api: {
    catalogo: vi.fn(),
    categorias: vi.fn(),
    proveedores: vi.fn(),
    editarProducto: vi.fn(),
  },
  ErrorApi: class extends Error {},
  imagenAutenticada: vi.fn(async () => 'blob:simulada'),
}))

function montar() {
  return render(<MemoryRouter><Catalogo /></MemoryRouter>)
}

/**
 * Adelanta el reloj simulado dentro de act().
 *
 * La pantalla espera 300 ms antes de consultar, para no lanzar una
 * petición por pulsación. Sin el act() el avance provoca cambios de
 * estado fuera del control de React y el registro se llena de avisos
 * que después tapan los que sí importan.
 */
async function pasarLaEspera() {
  await act(async () => { await vi.advanceTimersByTimeAsync(400) })
}

/** Espera a que la tabla esté poblada. */
async function conFilas() {
  await pasarLaEspera()
  await waitFor(() => expect(screen.getByText('PC-001', { exact: false })).toBeInTheDocument())
}

describe('catálogo de productos', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    sessionStorage.clear()
    guardarSesion({ token: 't', nombre: 'Jesús Manuel', rol: 'ADMINISTRADOR' })
    api.catalogo.mockResolvedValue([PC, CEL, MON])
    api.categorias.mockResolvedValue(CATEGORIAS)
    api.proveedores.mockResolvedValue(PROVEEDORES)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  describe('el total con impuesto (RF-33)', () => {
    it('acompaña al precio cuando difiere de él', async () => {
      montar()
      await conFilas()
      // MON-001 está gravado al 19 % en las dos modalidades.
      expect(fila(screen, 'MON-001')).toHaveTextContent('609.875')
      expect(fila(screen, 'MON-001')).toHaveTextContent('634.270')
    })

    it('no se repite cuando el producto está excluido', async () => {
      montar()
      await conFilas()
      // CEL-002 está excluido en las dos: el total ES el precio, y
      // pintarlo debajo obligaría a comparar dos cifras para descubrir
      // que son la misma.
      expect(fila(screen, 'CEL-002')).not.toHaveTextContent('con IVA')
    })

    it('aparece solo en la modalidad gravada, no en las dos', async () => {
      montar()
      await conFilas()
      // PC-001 es el caso que el margen provoca por sí solo: excluido al
      // contado, gravado a crédito. El total con impuesto tiene que
      // salir una sola vez, en la columna de crédito.
      const f = fila(screen, 'PC-001')
      expect(f.textContent.match(/con IVA/g)).toHaveLength(1)
      expect(f).toHaveTextContent('3.123.750')
    })
  })

  describe('el aviso de cruce del umbral', () => {
    it('señala el producto que cambia de tratamiento al dar crédito', async () => {
      montar()
      await conFilas()
      expect(fila(screen, 'PC-001')).toHaveTextContent('cruza el umbral')
    })

    it('no señala a los demás', async () => {
      montar()
      await conFilas()
      expect(fila(screen, 'CEL-002')).not.toHaveTextContent('cruza el umbral')
      expect(fila(screen, 'MON-001')).not.toHaveTextContent('cruza el umbral')
    })

    it('avisa de la cercanía al umbral, que es distinto de cruzarlo', async () => {
      montar()
      await conFilas()
      expect(fila(screen, 'PC-001')).toHaveTextContent('cerca del umbral')
      expect(fila(screen, 'CEL-002')).not.toHaveTextContent('cerca del umbral')
    })
  })

  describe('el aviso de impuestos sin configurar (ADR-0024)', () => {
    // Una empresa responsable de IVA con un producto sin regla: el
    // tratamiento llega vacío, la tarifa en cero y el total igual al
    // precio, que es lo que la base devuelve.
    const SIN_REGLA = {
      ...CEL, productoId: 9, codigo: 'SRV-009', nombre: 'Servicio técnico',
      tratamientoIva: null, tarifaContado: 0, tributacionSinConfigurar: 1,
      cercaUmbral: 0,
    }

    it('dice que los impuestos no están configurados', async () => {
      api.catalogo.mockResolvedValue([PC, SIN_REGLA])
      montar()
      await conFilas()
      expect(fila(screen, 'SRV-009')).toHaveTextContent('Impuestos sin configurar')
    })

    it('no dice ni sugiere que el producto no se pueda vender', async () => {
      api.catalogo.mockResolvedValue([PC, SIN_REGLA])
      montar()
      await conFilas()
      // Se vende al precio: el aviso informa, no bloquea. El precio sigue ahí.
      expect(fila(screen, 'SRV-009')).toHaveTextContent('744.000')
      expect(screen.queryByText(/no se puede vender|no puede vender|no podrás vender/i))
        .not.toBeInTheDocument()
    })

    it('la leyenda explica la consecuencia con palabras', async () => {
      api.catalogo.mockResolvedValue([PC, SIN_REGLA])
      montar()
      await conFilas()
      // El título de una insignia no llega al teclado ni al tacto: lo que
      // pasa con el producto tiene que estar escrito en la página.
      expect(screen.getByText(/se vende al precio, sin impuesto/i)).toBeInTheDocument()
    })

    it('quien vende no ve ni la insignia ni la leyenda', async () => {
      // Configurar los impuestos es del administrador: un aviso que quien
      // vende no puede atender solo le estorba delante del cliente.
      guardarSesion({ token: 't', nombre: 'Ana', rol: 'VENDEDOR' })
      api.catalogo.mockResolvedValue([PC, SIN_REGLA])
      montar()
      await conFilas()
      expect(fila(screen, 'SRV-009')).toBeInTheDocument()
      expect(screen.queryByText('Impuestos sin configurar')).not.toBeInTheDocument()
      expect(screen.queryByText(/se vende al precio, sin impuesto/i)).not.toBeInTheDocument()
    })

    it('no aparece en los productos que sí tienen su impuesto resuelto', async () => {
      montar()
      await conFilas()
      expect(screen.queryByText('Impuestos sin configurar')).not.toBeInTheDocument()
      expect(screen.queryByText(/se vende al precio, sin impuesto/i)).not.toBeInTheDocument()
    })
  })

  describe('la base de cálculo (RF-32)', () => {
    it('se anota cuando el margen va sobre el precio de venta', async () => {
      montar()
      await conFilas()
      expect(fila(screen, 'MON-001')).toHaveTextContent('sobre el precio')
    })

    it('se calla cuando va sobre el costo, que es lo corriente', async () => {
      montar()
      await conFilas()
      expect(fila(screen, 'PC-001')).not.toHaveTextContent('sobre el precio')
    })

    it('y la leyenda explica qué significa esa ausencia', async () => {
      montar()
      await conFilas()
      // Sin la leyenda, la anotación no se puede interpretar: quien no la
      // vea en una fila no sabe si allí se aplicó la otra base o si
      // nadie la anotó.
      expect(screen.getByText(/recargo sobre el costo/i)).toBeInTheDocument()
    })

    it('la leyenda no se queda sola cuando no hay productos', async () => {
      api.catalogo.mockResolvedValue([])
      montar()
      await pasarLaEspera()
      await waitFor(() => expect(screen.getByText(/catálogo está vacío/i)).toBeInTheDocument())
      expect(screen.queryByText(/recargo sobre el costo/i)).not.toBeInTheDocument()
    })
  })

  describe('los filtros (RF-16)', () => {
    it('no consulta una vez por pulsación', async () => {
      const teclado = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      montar()
      await conFilas()
      api.catalogo.mockClear()

      await teclado.type(screen.getByLabelText(/buscar/i), 'lenovo')
      // Cinco letras y, con la espera, una sola consulta.
      await pasarLaEspera()
      await waitFor(() => expect(api.catalogo).toHaveBeenCalledTimes(1))
      expect(api.catalogo).toHaveBeenLastCalledWith(
        expect.objectContaining({ busqueda: 'lenovo' }))
    })

    it('el estado de existencia llega al servidor, no se filtra en el navegador',
      async () => {
        const teclado = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
        montar()
        await conFilas()
        api.catalogo.mockClear()

        await teclado.selectOptions(screen.getByLabelText(/existencia/i), 'AGOTADO')
        await pasarLaEspera()
        await waitFor(() => expect(api.catalogo).toHaveBeenLastCalledWith(
          expect.objectContaining({ estado: 'AGOTADO' })))
      })
  })

  describe('lo que ve cada rol', () => {
    it('un administrador puede editar', async () => {
      montar()
      await conFilas()
      expect(screen.getAllByRole('button', { name: 'editar' }).length).toBeGreaterThan(0)
    })

    it('un vendedor no', async () => {
      guardarSesion({ token: 't', nombre: 'Ana', rol: 'VENDEDOR' })
      montar()
      await conFilas()
      expect(screen.queryByRole('button', { name: 'editar' })).not.toBeInTheDocument()
    })
  })

  describe('el costo y los proveedores son del administrador', () => {
    // La API ya no manda costo ni proveedor a quien vende; estos casos
    // comprueban que la pantalla tampoco los pida ni deje un hueco.
    const paraVenta = ({ costo: _costo, proveedorId: _id, proveedor: _nombre, ...resto }) => resto

    beforeEach(() => {
      guardarSesion({ token: 't', nombre: 'Ana', rol: 'VENDEDOR' })
      api.catalogo.mockResolvedValue([PC, CEL, MON].map(paraVenta))
      api.proveedores.mockRejectedValue(new Error('403'))
    })

    it('un vendedor no pide los proveedores y aun así ve categorías y productos',
      async () => {
        montar()
        await conFilas()
        expect(api.proveedores).not.toHaveBeenCalled()
        expect(api.categorias).toHaveBeenCalled()
        // Las categorías llenan su selector aunque los proveedores no se
        // pidan: antes iban en la misma promesa y una sola rechazada
        // vaciaba las dos.
        expect(screen.getByRole('option', { name: 'Monitores' })).toBeInTheDocument()
        expect(fila(screen, 'PC-001')).toBeInTheDocument()
      })

    it('un vendedor no ve la columna de costo ni el filtro de proveedor', async () => {
      montar()
      await conFilas()
      expect(screen.queryByRole('columnheader', { name: 'Costo' })).not.toBeInTheDocument()
      expect(screen.queryByLabelText(/proveedor/i)).not.toBeInTheDocument()
      expect(screen.getAllByRole('columnheader')).toHaveLength(7)
      expect(fila(screen, 'PC-001').querySelectorAll('td')).toHaveLength(7)
      expect(fila(screen, 'PC-001')).not.toHaveTextContent('2.100.000')
    })

    it('un administrador sí los pide, y ve el costo y el filtro', async () => {
      guardarSesion({ token: 't', nombre: 'Jesús Manuel', rol: 'ADMINISTRADOR' })
      api.catalogo.mockResolvedValue([PC, CEL, MON])
      api.proveedores.mockResolvedValue(PROVEEDORES)
      montar()
      await conFilas()
      expect(api.proveedores).toHaveBeenCalled()
      expect(screen.getByRole('columnheader', { name: 'Costo' })).toBeInTheDocument()
      expect(screen.getAllByRole('columnheader')).toHaveLength(10)
      expect(screen.getByLabelText(/proveedor/i)).toBeInTheDocument()
      expect(fila(screen, 'PC-001')).toHaveTextContent('2.100.000')
    })

    it('un vendedor no ve la columna de margen', async () => {
      montar()
      await conFilas()
      expect(screen.queryByRole('columnheader', { name: 'Margen' })).not.toBeInTheDocument()
      expect(screen.queryByText(/falta el precio de venta/i)).not.toBeInTheDocument()
      expect(fila(screen, 'PC-001')).not.toHaveTextContent('20,00')
    })

    it('un administrador ve el margen de contado y el de crédito si difiere', async () => {
      guardarSesion({ token: 't', nombre: 'Jesús Manuel', rol: 'ADMINISTRADOR' })
      api.catalogo.mockResolvedValue([PC, { ...CEL, margenCredito: 20 }])
      api.proveedores.mockResolvedValue(PROVEEDORES)
      montar()
      await conFilas()
      expect(screen.getByRole('columnheader', { name: 'Margen' })).toBeInTheDocument()
      expect(fila(screen, 'PC-001')).toHaveTextContent('20,00 %')
      expect(fila(screen, 'PC-001')).toHaveTextContent('a crédito 25,00 %')
      expect(fila(screen, 'CEL-002')).toHaveTextContent('20,00 %')
      expect(fila(screen, 'CEL-002')).not.toHaveTextContent('a crédito')
    })

    it('un margen negativo se ve negativo', async () => {
      guardarSesion({ token: 't', nombre: 'Jesús Manuel', rol: 'ADMINISTRADOR' })
      api.catalogo.mockResolvedValue([{ ...PC, margenContado: -10 }])
      api.proveedores.mockResolvedValue(PROVEEDORES)
      montar()
      await conFilas()
      expect(fila(screen, 'PC-001')).toHaveTextContent('-10,00 %')
    })

    it('sin precio dice que falta, en vez de un margen de cero', async () => {
      guardarSesion({ token: 't', nombre: 'Jesús Manuel', rol: 'ADMINISTRADOR' })
      api.catalogo.mockResolvedValue([{
        ...PC, precioContado: null, precioCredito: null, totalContado: null, totalCredito: null,
        margenContado: null, margenCredito: null, cruzaUmbralEnCredito: 0, cercaUmbral: 0,
        baseContado: null, baseCredito: null,
      }])
      api.proveedores.mockResolvedValue(PROVEEDORES)
      montar()
      await conFilas()
      expect(fila(screen, 'PC-001')).toHaveTextContent('Falta el precio de venta')
      expect(fila(screen, 'PC-001')).not.toHaveTextContent('0,00 %')
      // Sin precio tampoco hay nada «escrito».
      expect(fila(screen, 'PC-001')).not.toHaveTextContent('precio escrito')
    })

    it('un precio escrito se anota, y la leyenda dice qué significa', async () => {
      api.catalogo.mockResolvedValue([
        { ...PC, baseContado: null, precioFijoContado: 2500000, precioContado: 2500000 },
      ])
      montar()
      await conFilas()
      expect(fila(screen, 'PC-001')).toHaveTextContent('precio escrito')
      expect(screen.getByText(/se venden a esa cifra aunque cambie el costo/i)).toBeInTheDocument()
    })

    it('si los proveedores fallan, el administrador conserva las categorías', async () => {
      guardarSesion({ token: 't', nombre: 'Jesús Manuel', rol: 'ADMINISTRADOR' })
      api.catalogo.mockResolvedValue([PC, CEL, MON])
      montar()
      await conFilas()
      expect(screen.getByRole('option', { name: 'Monitores' })).toBeInTheDocument()
    })
  })

  it('un fallo del servidor se dice, no se queda en una tabla vacía', async () => {
    api.catalogo.mockRejectedValue(new Error('El servidor no respondió.'))
    montar()
    await pasarLaEspera()
    await waitFor(() => expect(screen.getByRole('alert'))
      .toHaveTextContent('El servidor no respondió.'))
  })

  it('que fallen los selectores no tapa el catálogo', async () => {
    api.categorias.mockRejectedValue(new Error('sin categorías'))
    api.proveedores.mockRejectedValue(new Error('sin proveedores'))
    montar()
    await conFilas()
    expect(fila(screen, 'PC-001')).toBeInTheDocument()
  })
})

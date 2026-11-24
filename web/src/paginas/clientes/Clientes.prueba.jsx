import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { puedeVer } from '../../secciones.js'
import { actualizarCliente, crearCliente, listarClientes } from '../../servicios/api/caja.js'
import { entrar, errorApi, pesos } from '../caja/ayuda.js'
import Clientes from './Clientes.jsx'

vi.mock('../../servicios/api/caja.js')

const ANA = {
  id: 7, tipoDocumento: 'CC', identificacion: '1020304050', nombre: 'Ana Pérez', telefono: '3001112233',
  correo: 'ana@correo.co', direccion: 'Calle 1 # 2-3', ciudad: 'Cali', saldoAFavor: 0, editable: true, autorizoDatos: true,
}
const FERRETERIA = {
  id: 8, tipoDocumento: 'NIT', identificacion: '9001234567', nombre: 'Ferretería El Tornillo', telefono: null,
  correo: 'compras@tornillo.co', direccion: null, ciudad: 'Bogotá', saldoAFavor: 45000, editable: true, autorizoDatos: false,
}
const CONSUMIDOR = {
  id: 1, tipoDocumento: 'CC', identificacion: '222222222222', nombre: 'Consumidor final', telefono: null,
  correo: null, direccion: null, ciudad: 'Cali', saldoAFavor: 0, editable: false, autorizoDatos: true,
}
const pagina = (elementos, extra = {}) => ({ elementos, pagina: 0, tamano: 20, total: elementos.length, ...extra })

function montar() {
  return render(<MemoryRouter><Clientes /></MemoryRouter>)
}
const filaDe = (nombre) => screen.getByRole('row', { name: new RegExp(nombre) })

beforeEach(() => {
  vi.resetAllMocks()
  entrar('ADMINISTRADOR')
  listarClientes.mockResolvedValue(pagina([CONSUMIDOR, ANA, FERRETERIA]))
})

describe('pantalla de clientes', () => {
  it('listaYBusca: muestra la lista con el tipo junto al número y busca por texto', async () => {
    montar()
    expect(await screen.findByRole('heading', { level: 1, name: 'Clientes' })).toBeInTheDocument()
    const tabla = await screen.findByRole('table', { name: /clientes/i })
    expect(within(tabla).getAllByRole('row')).toHaveLength(4)
    expect(listarClientes).toHaveBeenLastCalledWith({ buscar: '', pagina: 0 })
    const ana = within(tabla).getByRole('row', { name: /Ana Pérez/ })
    expect(within(ana).getByText('CC 1020304050')).toBeInTheDocument()
    expect(within(ana).getByText('ana@correo.co')).toBeInTheDocument()
    expect(within(tabla).getByText('NIT 9001234567')).toBeInTheDocument()

    listarClientes.mockResolvedValue(pagina([ANA]))
    await userEvent.type(screen.getByRole('searchbox', { name: /buscar/i }), '  1020  ')
    await userEvent.click(screen.getByRole('button', { name: 'Buscar' }))
    await waitFor(() => expect(listarClientes).toHaveBeenLastCalledWith({ buscar: '1020', pagina: 0 }))
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(2))
  })

  it('paginaSiguiente: pasa de página, no se sale de los extremos y una búsqueda vuelve a la primera', async () => {
    listarClientes.mockResolvedValue(pagina([ANA], { total: 45 }))
    montar()
    expect(await screen.findByText('Página 1 de 3')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Anterior' })).toBeDisabled()

    listarClientes.mockResolvedValue(pagina([FERRETERIA], { pagina: 1, total: 45 }))
    await userEvent.click(screen.getByRole('button', { name: 'Siguiente' }))
    await waitFor(() => expect(listarClientes).toHaveBeenLastCalledWith({ buscar: '', pagina: 1 }))
    expect(await screen.findByText('Página 2 de 3')).toBeInTheDocument()

    await userEvent.type(screen.getByRole('searchbox', { name: /buscar/i }), 'ana')
    await userEvent.click(screen.getByRole('button', { name: 'Buscar' }))
    await waitFor(() => expect(listarClientes).toHaveBeenLastCalledWith({ buscar: 'ana', pagina: 0 }))
  })

  it('creaClienteConAutorizacionObligatoria: sin la casilla no guarda; con ella crea y recarga la lista', async () => {
    crearCliente.mockResolvedValue({ ...ANA, id: 9 })
    montar()
    await screen.findByRole('table')
    await userEvent.click(screen.getByRole('button', { name: 'Nuevo cliente' }))
    const formulario = screen.getByRole('form', { name: /cliente nuevo/i })
    await userEvent.type(within(formulario).getByLabelText('Número de documento'), '555')
    await userEvent.type(within(formulario).getByLabelText('Nombre completo'), 'Luis Soto')
    await userEvent.type(within(formulario).getByLabelText('Correo electrónico'), 'luis@correo.co')
    await userEvent.click(within(formulario).getByRole('button', { name: 'Guardar cliente' }))
    expect(await within(formulario).findByText(/falta la autorización/i)).toBeInTheDocument()
    expect(crearCliente).not.toHaveBeenCalled()

    await userEvent.click(within(formulario).getByLabelText(/autoriza el tratamiento de sus datos/i))
    await userEvent.click(within(formulario).getByRole('button', { name: 'Guardar cliente' }))
    await waitFor(() => expect(crearCliente).toHaveBeenCalledWith({
      nombre: 'Luis Soto', tipoDocumento: 'CC', identificacion: '555', correo: 'luis@correo.co', autorizacionDatos: true,
    }))
    await waitFor(() => expect(screen.queryByRole('form', { name: /cliente nuevo/i })).not.toBeInTheDocument())
    expect(listarClientes).toHaveBeenCalledTimes(2)
  })

  it('editaElDocumento: el tipo y el número se cambian, y la autorización llega marcada según lo guardado', async () => {
    actualizarCliente.mockResolvedValue({ ...ANA, tipoDocumento: 'CE', identificacion: '777' })
    montar()
    await screen.findByRole('table')
    await userEvent.click(within(filaDe('Ana Pérez')).getByRole('button', { name: 'Editar a Ana Pérez' }))
    const formulario = screen.getByRole('form', { name: /editar cliente/i })
    expect(within(formulario).getByLabelText('Número de documento')).toHaveValue('1020304050')
    expect(within(formulario).getByLabelText(/autoriza el tratamiento de sus datos/i)).toBeChecked()
    await userEvent.selectOptions(within(formulario).getByLabelText('Tipo de documento'), 'CE')
    await userEvent.clear(within(formulario).getByLabelText('Número de documento'))
    await userEvent.type(within(formulario).getByLabelText('Número de documento'), '777')
    await userEvent.click(within(formulario).getByRole('button', { name: 'Guardar cambios' }))
    await waitFor(() => expect(actualizarCliente).toHaveBeenCalledWith(7, expect.objectContaining({
      tipoDocumento: 'CE', identificacion: '777', nombre: 'Ana Pérez', autorizacionDatos: true,
    })))
    await waitFor(() => expect(listarClientes).toHaveBeenCalledTimes(2))
  })

  it('editaElDocumento: quien no autorizó no se guarda sin marcar la casilla, y un documento repetido muestra el motivo', async () => {
    actualizarCliente.mockRejectedValue(errorApi(409, 'Ya existe un cliente con ese tipo y número de documento.'))
    montar()
    await screen.findByRole('table')
    await userEvent.click(screen.getByRole('button', { name: 'Editar a Ferretería El Tornillo' }))
    const formulario = screen.getByRole('form', { name: /editar cliente/i })
    const casilla = within(formulario).getByLabelText(/autoriza el tratamiento de sus datos/i)
    expect(casilla).not.toBeChecked()
    await userEvent.click(within(formulario).getByRole('button', { name: 'Guardar cambios' }))
    expect(actualizarCliente).not.toHaveBeenCalled()
    await userEvent.click(casilla)
    await userEvent.click(within(formulario).getByRole('button', { name: 'Guardar cambios' }))
    expect(await within(formulario).findByText(/ya existe un cliente con ese tipo/i)).toBeInTheDocument()
    expect(screen.getByRole('form', { name: /editar cliente/i })).toBeInTheDocument()
  })

  it('editarNoBorraDireccionNiCiudad: al guardar viajan los datos que el formulario no muestra', async () => {
    actualizarCliente.mockResolvedValue(ANA)
    montar()
    await screen.findByRole('table')
    await userEvent.click(screen.getByRole('button', { name: 'Editar a Ana Pérez' }))
    const formulario = screen.getByRole('form', { name: /editar cliente/i })
    await userEvent.clear(within(formulario).getByLabelText('Teléfono (opcional)'))
    await userEvent.type(within(formulario).getByLabelText('Teléfono (opcional)'), '3112223344')
    await userEvent.click(within(formulario).getByRole('button', { name: 'Guardar cambios' }))
    await waitFor(() => expect(actualizarCliente).toHaveBeenCalledWith(7, {
      nombre: 'Ana Pérez', tipoDocumento: 'CC', identificacion: '1020304050', correo: 'ana@correo.co',
      telefono: '3112223344', direccion: 'Calle 1 # 2-3', ciudad: 'Cali', autorizacionDatos: true,
    }))
  })

  it('editaDireccionYCiudad: el formulario trae los valores de la fila y envía los nuevos', async () => {
    actualizarCliente.mockResolvedValue(ANA)
    montar()
    await screen.findByRole('table')
    await userEvent.click(screen.getByRole('button', { name: 'Editar a Ana Pérez' }))
    const formulario = screen.getByRole('form', { name: /editar cliente/i })
    const direccion = within(formulario).getByLabelText('Dirección (opcional)')
    const ciudad = within(formulario).getByLabelText('Ciudad')
    expect(direccion).toHaveValue('Calle 1 # 2-3')
    expect(ciudad).toHaveValue('Cali')
    expect(within(formulario).getByText('Si la dejas vacía, se usa la ciudad de la empresa.')).toBeInTheDocument()
    await userEvent.clear(direccion)
    await userEvent.type(direccion, 'Carrera 9 # 8-7')
    await userEvent.clear(ciudad)
    await userEvent.type(ciudad, 'Medellín')
    await userEvent.click(within(formulario).getByRole('button', { name: 'Guardar cambios' }))
    await waitFor(() => expect(actualizarCliente).toHaveBeenCalledWith(7, expect.objectContaining({
      direccion: 'Carrera 9 # 8-7', ciudad: 'Medellín', telefono: '3001112233',
    })))
  })

  it('creaSinCiudad: no envía la ciudad y, si el servidor la rechaza, se ve su mensaje', async () => {
    crearCliente.mockRejectedValue(errorApi(400, 'La ciudad es obligatoria: el cliente no la trae y la empresa no tiene una por omisión.'))
    montar()
    await screen.findByRole('table')
    await userEvent.click(screen.getByRole('button', { name: 'Nuevo cliente' }))
    const formulario = screen.getByRole('form', { name: /cliente nuevo/i })
    expect(within(formulario).getByLabelText('Ciudad')).toHaveValue('')
    await userEvent.type(within(formulario).getByLabelText('Número de documento'), '555')
    await userEvent.type(within(formulario).getByLabelText('Nombre completo'), 'Luis Soto')
    await userEvent.type(within(formulario).getByLabelText('Correo electrónico'), 'luis@correo.co')
    await userEvent.type(within(formulario).getByLabelText('Dirección (opcional)'), 'Calle 5')
    await userEvent.click(within(formulario).getByLabelText(/autoriza el tratamiento de sus datos/i))
    await userEvent.click(within(formulario).getByRole('button', { name: 'Guardar cliente' }))
    await waitFor(() => expect(crearCliente).toHaveBeenCalledWith({
      nombre: 'Luis Soto', tipoDocumento: 'CC', identificacion: '555', correo: 'luis@correo.co',
      direccion: 'Calle 5', autorizacionDatos: true,
    }))
    expect(await within(formulario).findByText(/la ciudad es obligatoria/i)).toBeInTheDocument()
  })

  it('consumidorFinalSinBotonEditar: el cliente del sistema se ve pero no se edita', async () => {
    montar()
    await screen.findByRole('table')
    expect(within(filaDe('Consumidor final')).queryByRole('button')).not.toBeInTheDocument()
    expect(within(filaDe('Ana Pérez')).getByRole('button', { name: 'Editar a Ana Pérez' })).toBeInTheDocument()
  })

  it('muestraElSaldoSinEditarlo: el saldo a favor sale en la lista y no es un campo del formulario', async () => {
    montar()
    await screen.findByRole('table')
    expect(within(filaDe('Ferretería')).getByText(pesos(45000))).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Editar a Ferretería El Tornillo' }))
    const formulario = screen.getByRole('form', { name: /editar cliente/i })
    expect(within(formulario).queryByLabelText(/saldo/i)).not.toBeInTheDocument()
  })

  it('estadoVacioYError: dice que no hay clientes, que la búsqueda no encontró, y deja reintentar un fallo', async () => {
    listarClientes.mockResolvedValue(pagina([]))
    montar()
    expect(await screen.findByText('Todavía no hay clientes.')).toBeInTheDocument()
    await userEvent.type(screen.getByRole('searchbox', { name: /buscar/i }), 'zzz')
    await userEvent.click(screen.getByRole('button', { name: 'Buscar' }))
    expect(await screen.findByText('Ningún cliente coincide con «zzz».')).toBeInTheDocument()

    listarClientes.mockRejectedValueOnce(errorApi(500, 'Falló el servidor.'))
    await userEvent.click(screen.getByRole('button', { name: 'Buscar' }))
    expect(await screen.findByText('Falló el servidor.')).toBeInTheDocument()
    listarClientes.mockResolvedValue(pagina([ANA]))
    await userEvent.click(screen.getByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('Ana Pérez')).toBeInTheDocument()
  })

  it('elVendedorVeLaSeccion: el vendedor la ve y la usa; el cliente y el super administrador no', async () => {
    expect(puedeVer('/clientes', 'ADMINISTRADOR')).toBe(true)
    expect(puedeVer('/clientes', 'VENDEDOR')).toBe(true)
    expect(puedeVer('/clientes', 'CLIENTE')).toBe(false)
    expect(puedeVer('/clientes', 'SUPER_ADMIN')).toBe(false)
    entrar('VENDEDOR')
    montar()
    expect(await screen.findByText('Ana Pérez')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Nuevo cliente' })).toBeInTheDocument()
  })
})

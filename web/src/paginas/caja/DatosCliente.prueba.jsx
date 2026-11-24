import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { actualizarCliente, clientePorIdentificacion, crearCliente } from '../../servicios/api/caja.js'
import { ANA, prepararApi } from './ayuda.js'
import DatosCliente from './DatosCliente.jsx'

vi.mock('../../servicios/api/caja.js')

const cambios = { cliente: vi.fn(), domicilio: vi.fn() }

/** Un contenedor con estado, como la pantalla. */
function Pantalla({ clienteInicial = null }) {
  const [cliente, setCliente] = useState(clienteInicial)
  const [domicilio, setDomicilio] = useState(null)
  return (
    <DatosCliente
      cliente={cliente} domicilio={domicilio}
      alCambiarCliente={(c) => { cambios.cliente(c); setCliente(c) }}
      alCambiarDomicilio={(d) => { cambios.domicilio(d); setDomicilio(d) }}
    />
  )
}

beforeEach(() => {
  vi.resetAllMocks()
  cambios.cliente.mockClear()
  prepararApi()
})

async function identificar(numero = '1020304050') {
  await userEvent.click(screen.getByRole('button', { name: /identificar cliente/i }))
  await userEvent.type(screen.getByLabelText(/^identificación/i), numero)
  await userEvent.click(screen.getByRole('button', { name: /buscar cliente/i }))
}

describe('cliente de la venta', () => {
  it('consumidorFinalPorDefecto: el botón está activo y a la vista', () => {
    render(<Pantalla />)
    expect(screen.getByRole('button', { name: 'Consumidor final' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: /identificar cliente/i })).toBeInTheDocument()
  })

  it('un cliente que existe completa sus datos y se usa', async () => {
    render(<Pantalla />)
    await identificar()
    expect(clientePorIdentificacion).toHaveBeenCalledWith('CC', '1020304050')
    expect(await screen.findByText('Ana Pérez')).toBeInTheDocument()
    expect(screen.getByText('ana@correo.co')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /usar este cliente/i }))
    expect(cambios.cliente).toHaveBeenCalledWith(ANA)
    expect(screen.getByRole('button', { name: 'Consumidor final' })).toHaveAttribute('aria-pressed', 'false')
  })

  it('uno que no existe pide nombre, correo y la autorización de datos, que es obligatoria', async () => {
    clientePorIdentificacion.mockResolvedValue(null)
    render(<Pantalla />)
    await identificar('999')
    expect(await screen.findByLabelText(/^nombre/i)).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText(/^nombre/i), 'Luis Mora')
    await userEvent.type(screen.getByLabelText(/^correo/i), 'luis@correo.co')
    await userEvent.click(screen.getByRole('button', { name: /guardar cliente/i }))
    expect(screen.getByText(/autorización para tratar sus datos/i, { selector: '.campo-mensaje' })).toBeInTheDocument()
    expect(crearCliente).not.toHaveBeenCalled()

    crearCliente.mockResolvedValue({ ...ANA, id: 8, nombre: 'Luis Mora', identificacion: '999' })
    await userEvent.click(screen.getByLabelText(/autoriza el tratamiento de sus datos/i))
    await userEvent.click(screen.getByRole('button', { name: /guardar cliente/i }))
    expect(crearCliente).toHaveBeenCalledWith(expect.objectContaining({
      nombre: 'Luis Mora', tipoDocumento: 'CC', identificacion: '999', correo: 'luis@correo.co', autorizacionDatos: true,
    }))
    expect(await screen.findByText('Luis Mora')).toBeInTheDocument()
    expect(cambios.cliente).toHaveBeenCalledWith(expect.objectContaining({ id: 8 }))
  })

  it('los datos de un cliente que existe se pueden corregir, con la autorización', async () => {
    render(<Pantalla />)
    await identificar()
    await userEvent.click(await screen.findByRole('button', { name: /corregir datos/i }))
    const correo = screen.getByLabelText(/^correo/i)
    await userEvent.clear(correo)
    await userEvent.type(correo, 'nuevo@correo.co')
    actualizarCliente.mockResolvedValue({ ...ANA, correo: 'nuevo@correo.co' })
    await userEvent.click(screen.getByLabelText(/autoriza el tratamiento de sus datos/i))
    await userEvent.click(screen.getByRole('button', { name: /guardar cambios/i }))
    expect(actualizarCliente).toHaveBeenCalledWith(7, expect.objectContaining({ correo: 'nuevo@correo.co', autorizacionDatos: true }))
  })

  it('datosClienteDeLaCajaSigueFuncionando: en la caja el documento va fijo, sin campos para cambiarlo', async () => {
    render(<Pantalla />)
    await identificar()
    await userEvent.click(await screen.findByRole('button', { name: /corregir datos/i }))
    expect(screen.getByText('CC 1020304050')).toBeInTheDocument()
    expect(screen.queryByLabelText('Número de documento')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Tipo de documento')).not.toBeInTheDocument()
    actualizarCliente.mockResolvedValue(ANA)
    await userEvent.click(screen.getByLabelText(/autoriza el tratamiento de sus datos/i))
    await userEvent.click(screen.getByRole('button', { name: /guardar cambios/i }))
    expect(actualizarCliente).toHaveBeenCalledWith(7, expect.objectContaining({
      tipoDocumento: 'CC', identificacion: '1020304050', direccion: 'Calle 1 # 2-3', ciudad: 'Cali',
    }))
  })

  it('un fallo al buscar se anuncia', async () => {
    clientePorIdentificacion.mockRejectedValue(new Error('Se cayó'))
    render(<Pantalla />)
    await identificar()
    expect(await screen.findByRole('alert')).toHaveTextContent('Se cayó')
  })

  it('volver a Consumidor final quita al cliente y el domicilio', async () => {
    render(<Pantalla clienteInicial={ANA} />)
    await userEvent.click(screen.getByRole('button', { name: 'Consumidor final' }))
    expect(cambios.cliente).toHaveBeenCalledWith(null)
  })

  it('domicilioPideSusDatos: teléfono, dirección, ciudad, quién recibe y si paga contra entrega', async () => {
    render(<Pantalla />)
    expect(screen.queryByLabelText(/dirección/i)).not.toBeInTheDocument()
    await userEvent.click(screen.getByLabelText('Domicilio'))
    expect(screen.getByLabelText(/^teléfono/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/^dirección/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/^ciudad/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/quién recibe/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/¿paga contra entrega\?/i)).toBeInTheDocument()
    // Un domicilio nunca es para Consumidor final.
    expect(screen.getByText(/un domicilio necesita un cliente identificado/i)).toBeInTheDocument()
  })

  it('con el cliente identificado el domicilio trae su teléfono, dirección y ciudad', async () => {
    render(<Pantalla clienteInicial={ANA} />)
    await userEvent.click(screen.getByLabelText('Domicilio'))
    expect(screen.getByLabelText(/^teléfono/i)).toHaveValue('3001112233')
    expect(screen.getByLabelText(/^dirección/i)).toHaveValue('Calle 1 # 2-3')
    expect(screen.getByLabelText(/^ciudad/i)).toHaveValue('Cali')
    expect(screen.queryByText(/necesita un cliente identificado/i)).not.toBeInTheDocument()
  })

  it('no guarda datos del cliente en el almacenamiento del navegador ni en la consola', async () => {
    const consola = vi.spyOn(console, 'log')
    const info = vi.spyOn(console, 'info')
    render(<Pantalla />)
    await identificar()
    await screen.findByText('Ana Pérez')
    expect(JSON.stringify({ ...localStorage })).not.toMatch(/Ana|1020304050/)
    expect(JSON.stringify({ ...sessionStorage })).not.toMatch(/Ana|1020304050/)
    expect(consola).not.toHaveBeenCalled()
    expect(info).not.toHaveBeenCalled()
  })
})

import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import Campo from './Campo.jsx'

describe('Campo', () => {
  it('une la etiqueta visible con el control por id', () => {
    render(<Campo etiqueta="Nombre"><input /></Campo>)
    expect(screen.getByLabelText('Nombre')).toBeInTheDocument()
  })

  it('dos campos tienen ids distintos', () => {
    render(<><Campo etiqueta="A"><input /></Campo><Campo etiqueta="B"><input /></Campo></>)
    expect(screen.getByLabelText('A').id).not.toBe(screen.getByLabelText('B').id)
  })

  it('la ayuda describe al control y no cuenta como error', () => {
    render(<Campo etiqueta="Nombre" ayuda="Como en el documento"><input /></Campo>)
    const campo = screen.getByLabelText('Nombre')
    expect(campo).not.toHaveAttribute('aria-invalid')
    expect(campo).toHaveAccessibleDescription('Como en el documento')
    expect(screen.getByText('Como en el documento')).toHaveClass('campo-mensaje', 'ayuda')
  })

  it('el error marca el control, lo describe y gana a la ayuda', () => {
    render(<Campo etiqueta="Nombre" ayuda="Pista" motivo="Falta el nombre"><input /></Campo>)
    const campo = screen.getByLabelText('Nombre')
    expect(campo).toHaveAttribute('aria-invalid', 'true')
    expect(campo).toHaveAccessibleDescription('Falta el nombre')
    expect(screen.queryByText('Pista')).toBeNull()
    expect(screen.getByRole('status')).toHaveClass('error')
  })

  it('el error puede anunciarse como alerta', () => {
    render(<Campo etiqueta="Nombre" motivo="Mal" rol="alert"><input /></Campo>)
    expect(screen.getByRole('alert')).toHaveTextContent('Mal')
  })

  it('la región de estado está siempre; la de alerta solo con error', () => {
    const { rerender } = render(<Campo etiqueta="N" ayuda="x"><input /></Campo>)
    expect(screen.getByRole('status')).toBeInTheDocument()
    rerender(<Campo etiqueta="N" ayuda="x" rol="alert"><input /></Campo>)
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('la advertencia describe al control, no lo marca inválido y se anuncia', () => {
    render(<Campo etiqueta="Precio" advertencia="Queda por debajo del costo"><input /></Campo>)
    const campo = screen.getByLabelText('Precio')
    expect(campo).not.toHaveAttribute('aria-invalid')
    expect(campo).toHaveAccessibleDescription('Queda por debajo del costo')
    expect(screen.getByRole('status')).toHaveClass('campo-mensaje', 'advertencia')
  })

  it('el error gana a la advertencia, y la advertencia a la ayuda', () => {
    const { rerender } = render(<Campo etiqueta="P" ayuda="Ayuda" advertencia="Aviso" motivo="Error"><input /></Campo>)
    expect(screen.getByLabelText('P')).toHaveAccessibleDescription('Error')
    rerender(<Campo etiqueta="P" ayuda="Ayuda" advertencia="Aviso"><input /></Campo>)
    expect(screen.getByLabelText('P')).toHaveAccessibleDescription('Aviso')
  })

  it('sin mensaje la región queda vacía y el control no apunta a ella', () => {
    render(<Campo etiqueta="N"><input /></Campo>)
    expect(screen.getByLabelText('N')).not.toHaveAttribute('aria-describedby')
  })
})

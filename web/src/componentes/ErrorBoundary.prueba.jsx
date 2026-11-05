import { useState } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ErrorBoundary from './ErrorBoundary.jsx'

function Roto() {
  throw new Error('falló el módulo')
}

describe('ErrorBoundary', () => {
  afterEach(() => vi.restoreAllMocks())

  it('muestra el error en vez de dejar la pantalla en blanco y permite reintentar', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const alReintentar = vi.fn()
    render(<ErrorBoundary alReintentar={alReintentar}><Roto /></ErrorBoundary>)
    expect(screen.getByRole('alert')).toHaveTextContent('falló el módulo')
    await userEvent.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(alReintentar).toHaveBeenCalledOnce()
  })

  it('conserva a los hijos sanos cuando cambia la clave de reinicio', () => {
    function Contador() {
      const [montajes] = useState(() => ++vecesMontado)
      return <p>montajes {montajes}</p>
    }
    let vecesMontado = 0
    const { rerender } = render(<ErrorBoundary reinicio="/compras"><Contador /></ErrorBoundary>)
    rerender(<ErrorBoundary reinicio="/compras/ordenes/5"><Contador /></ErrorBoundary>)
    expect(screen.getByText('montajes 1')).toBeInTheDocument()
  })

  it('sin error deja pasar a los hijos', () => {
    render(<ErrorBoundary><p>todo bien</p></ErrorBoundary>)
    expect(screen.getByText('todo bien')).toBeInTheDocument()
  })
})

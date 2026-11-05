import { Component } from 'react'
import EstadoError from './comun/EstadoError.jsx'

/**
 * Atrapa lo que falla al pintar, por ejemplo un módulo perezoso que no se
 * pudo descargar, para que la app no quede en blanco. Reintentar recarga
 * la página: es lo que arregla una descarga fallida.
 *
 * `reinicio` limpia el error cuando cambia, para que navegar a otra sección
 * no siga enseñando el fallo. No se usa `key` porque desmontaría también a
 * los hijos sanos, y una sección perdería su estado en cada subruta.
 */
export default class ErrorBoundary extends Component {
  state = { error: null }

  static getDerivedStateFromError(error) {
    return { error }
  }

  componentDidUpdate(previas) {
    if (this.state.error && previas.reinicio !== this.props.reinicio) {
      this.setState({ error: null })
    }
  }

  render() {
    if (!this.state.error) return this.props.children
    const reintentar = this.props.alReintentar ?? (() => window.location.reload())
    return <EstadoError error={this.state.error} alReintentar={reintentar} />
  }
}

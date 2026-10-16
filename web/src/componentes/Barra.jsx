import { Link } from 'react-router-dom'
import { useNavigate } from 'react-router-dom'
import { api } from '../servicios/api.js'
import { borrarSesion, leerSesion } from '../servicios/sesion.js'

export default function Barra() {
  const navegar = useNavigate()
  const sesion = leerSesion()

  async function salir() {
    try {
      await api.salir()
    } catch {
      // Si la petición falla, la sesión local se cierra igual: dejar al
      // usuario dentro porque la red falló sería lo peor de los dos
      // mundos. El token queda vivo en el servidor hasta que expire.
    }
    borrarSesion()
    navegar('/acceso', { replace: true })
  }

  return (
    <header className="barra">
      <span className="marca">SGIP</span>
      <Link to="/catalogo">Catálogo</Link>
      <Link to="/empresa">Mi empresa</Link>
      <span className="empuje">{sesion?.nombre}</span>
      <button className="boton secundario" onClick={salir}>Salir</button>
    </header>
  )
}

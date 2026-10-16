import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ErrorApi } from '../servicios/api.js'
import { guardarSesion } from '../servicios/sesion.js'

/** Pantalla P-01 · Acceso. */
export default function Acceso() {
  const [correo, setCorreo] = useState('')
  const [clave, setClave] = useState('')
  const [error, setError] = useState(null)
  const [enviando, setEnviando] = useState(false)
  const navegar = useNavigate()

  async function enviar(evento) {
    evento.preventDefault()
    setError(null)
    setEnviando(true)
    try {
      const sesion = await api.entrar(correo, clave)
      guardarSesion(sesion)
      navegar('/catalogo', { replace: true })
    } catch (e) {
      // El backend no distingue entre correo inexistente y clave errada,
      // y el front tampoco debe hacerlo: decir cuál de los dos falló
      // confirma qué correos están registrados.
      setError(e instanceof ErrorApi ? e.message : 'No se pudo conectar con el servidor.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <main className="acceso">
      <form className="tarjeta" onSubmit={enviar}>
        <h1>SGIP</h1>
        <p className="sub">Sistema de gestión de inventario y precios</p>

        {error && <div className="aviso error" role="alert">{error}</div>}

        <label className="campo">
          <span>Correo</span>
          <input
            type="email"
            value={correo}
            onChange={(e) => setCorreo(e.target.value)}
            autoComplete="username"
            required
          />
        </label>

        <label className="campo">
          <span>Contraseña</span>
          <input
            type="password"
            value={clave}
            onChange={(e) => setClave(e.target.value)}
            autoComplete="current-password"
            required
          />
        </label>

        <button className="boton" type="submit" disabled={enviando}>
          {enviando ? 'Entrando…' : 'Entrar'}
        </button>
      </form>
    </main>
  )
}

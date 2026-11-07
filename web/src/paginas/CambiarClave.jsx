import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ErrorApi } from '../servicios/api.js'
import '../estilos/acceso.css'
import {
  borrarSesion, debeCambiarClave, guardarSesion, inicioSegunRol, leerSesion,
} from '../servicios/sesion.js'

const MINIMO = 8

/**
 * Cambio de la contraseña inicial.
 *
 * Es la única pantalla a la que lleva la sesión mientras el cambio esté
 * pendiente: el backend responde 403 a todo lo demás. Por eso tiene que
 * dejar una salida: quien no pueda o no quiera cambiarla ahora cierra la
 * sesión, y no se queda atrapado en una pantalla sin menú.
 */
export default function CambiarClave() {
  useEffect(() => {
    document.title = 'Cambiar contraseña · SGIP'
    // El armazón no pone título en la primera carga: sin esto se quedaría este.
    return () => { document.title = 'SGIP' }
  }, [])
  const [actual, setActual] = useState('')
  const [nueva, setNueva] = useState('')
  const [repetida, setRepetida] = useState('')
  const [error, setError] = useState(null)
  const [enviando, setEnviando] = useState(false)
  const navegar = useNavigate()

  async function enviar(evento) {
    evento.preventDefault()
    setError(null)

    // La comprobación de la repetición es de la pantalla y no viaja: el
    // servidor solo necesita la contraseña nueva una vez.
    if (nueva !== repetida) {
      setError('Las contraseñas nuevas no coinciden. Escríbelas otra vez.')
      return
    }

    setEnviando(true)
    try {
      await api.cambiarClave(actual, nueva)
      // El servidor conserva esta sesión y cierra las demás, así que el
      // mismo token sigue valiendo. Basta con apagar la bandera local.
      const sesion = { ...leerSesion(), debeCambiarClave: false }
      guardarSesion(sesion)
      navegar(inicioSegunRol(sesion), { replace: true })
    } catch (e) {
      if (e instanceof ErrorApi && e.estado === 401) {
        // La sesión caducó mientras se escribía; el cliente ya la borró.
        navegar('/acceso', { replace: true })
        return
      }
      setError(e instanceof ErrorApi ? e.message : 'No se pudo conectar con el servidor.')
    } finally {
      setEnviando(false)
    }
  }

  async function salir() {
    try {
      await api.salir()
    } catch {
      // Igual que en la barra: si la red falla, la sesión local se cierra
      // de todos modos. Dejar a la persona dentro sería peor.
    }
    borrarSesion()
    navegar('/acceso', { replace: true })
  }

  return (
    <main className="acceso">
      <form className="tarjeta" onSubmit={enviar}>
        <h1>Cambia tu contraseña</h1>
        <p className="sub" id="ayuda-clave">
          {debeCambiarClave()
            ? 'Entraste con una contraseña temporal. Elige una propia para poder seguir. '
            : 'Elige una contraseña nueva. '}
          Debe tener al menos {MINIMO} caracteres y ser distinta de la actual.
        </p>

        {error && <div className="aviso error" role="alert">{error}</div>}

        <label className="campo">
          <span>Contraseña actual</span>
          <input
            type="password"
            value={actual}
            onChange={(e) => setActual(e.target.value)}
            autoComplete="current-password"
            required
          />
        </label>

        <label className="campo">
          <span>Contraseña nueva</span>
          <input
            type="password"
            value={nueva}
            onChange={(e) => setNueva(e.target.value)}
            autoComplete="new-password"
            minLength={MINIMO}
            aria-describedby="ayuda-clave"
            required
          />
        </label>

        <label className="campo">
          <span>Repite la contraseña nueva</span>
          <input
            type="password"
            value={repetida}
            onChange={(e) => setRepetida(e.target.value)}
            autoComplete="new-password"
            minLength={MINIMO}
            required
          />
        </label>

        <div className="acciones-formulario">
          <button className="boton" type="submit" disabled={enviando}>
            {enviando ? 'Guardando…' : 'Guardar contraseña'}
          </button>
          <button className="boton secundario" type="button" onClick={salir}>
            Cerrar sesión
          </button>
        </div>
      </form>
    </main>
  )
}

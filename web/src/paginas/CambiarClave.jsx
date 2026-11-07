import { useEffect, useId, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import Aviso from '../componentes/comun/Aviso.jsx'
import Campo from '../componentes/comun/Campo.jsx'
import { api, ErrorApi } from '../servicios/api.js'
import '../estilos/acceso.css'
import {
  borrarSesion, debeCambiarClave, guardarSesion, inicioSegunRol, leerSesion,
} from '../servicios/sesion.js'
import EntradaClave from './acceso/EntradaClave.jsx'

// Los mismos que exige el backend (SolicitudCambioClave y ServicioAutenticacion).
// El diseño muestra además mayúscula, minúscula y número: el servidor no los
// pide, y un indicador que los exigiera sería una promesa falsa.
const MINIMO = 8

function iniciales(nombre) {
  return nombre.trim().split(/\s+/).slice(0, 2).map((p) => p[0]).join('').toUpperCase()
}

function Requisito({ cumplido, children }) {
  return (
    <li className={cumplido ? 'requisito cumplido' : 'requisito'}>
      <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true" focusable="false">
        <circle cx="8" cy="8" r="6.5" fill="none" stroke="currentColor" strokeWidth="1.5" />
        {cumplido && (
          <path d="M5 8.2l2 2 4-4.4" fill="none" stroke="currentColor"
            strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
        )}
      </svg>
      <span>{children}</span>
      <span className="solo-lector">{cumplido ? ': cumplido' : ': pendiente'}</span>
    </li>
  )
}

/**
 * Cambio de la contraseña inicial.
 *
 * Es la única pantalla a la que lleva la sesión mientras el cambio esté
 * pendiente: el backend responde 403 a todo lo demás. Por eso tiene que
 * dejar una salida: quien no pueda o no quiera cambiarla ahora cierra la
 * sesión, y no se queda atrapado en una pantalla sin menú.
 *
 * El diseño ofrece «Volver al inicio de sesión»; aquí el botón se llama
 * «Cerrar sesión» porque eso es lo que hace de verdad.
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
  const [noCoincide, setNoCoincide] = useState(null)
  const [enviando, setEnviando] = useState(false)
  const navegar = useNavigate()
  const idRequisitos = useId()
  const nombre = leerSesion()?.nombre

  async function enviar(evento) {
    evento.preventDefault()
    setError(null)
    setNoCoincide(null)

    // La comprobación de la repetición es de la pantalla y no viaja: el
    // servidor solo necesita la contraseña nueva una vez.
    if (nueva !== repetida) {
      setNoCoincide('Las contraseñas nuevas no coinciden. Escríbelas otra vez.')
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
      // Un 423 (cuenta bloqueada) llega con su propio texto y su duración.
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
    <main className="acceso acceso--clave">
      <p className="acceso-logo">SGIP</p>
      <form className="tarjeta" onSubmit={enviar}>
        <h1>Cambia tu contraseña</h1>
        <p className="sub">
          {debeCambiarClave()
            ? 'Entraste con una contraseña temporal. Elige una propia para poder seguir.'
            : 'Elige una contraseña nueva.'}
        </p>

        {nombre && (
          <div className="acceso-cuenta">
            <span className="acceso-iniciales" aria-hidden="true">{iniciales(nombre)}</span>
            <span className="acceso-nombre">{nombre}</span>
          </div>
        )}

        {error && <Aviso tipo="peligro">{error}</Aviso>}

        <Campo etiqueta="Contraseña actual">
          <EntradaClave
            nombre="contraseña actual"
            value={actual}
            onChange={(e) => setActual(e.target.value)}
            autoComplete="current-password"
            required
          />
        </Campo>

        <Campo etiqueta="Contraseña nueva">
          <EntradaClave
            nombre="contraseña nueva"
            descritoPor={idRequisitos}
            value={nueva}
            onChange={(e) => { setNueva(e.target.value); setNoCoincide(null) }}
            autoComplete="new-password"
            minLength={MINIMO}
            required
          />
        </Campo>
        <ul id={idRequisitos} className="requisitos" role="list"
          aria-label="Requisitos de la contraseña nueva">
          <Requisito cumplido={nueva.length >= MINIMO}>Al menos {MINIMO} caracteres</Requisito>
          <Requisito cumplido={nueva !== '' && actual !== '' && nueva !== actual}>
            Distinta de la actual
          </Requisito>
        </ul>

        <Campo etiqueta="Repite la contraseña nueva" motivo={noCoincide} rol="alert">
          <EntradaClave
            nombre="repetición de la contraseña nueva"
            value={repetida}
            onChange={(e) => { setRepetida(e.target.value); setNoCoincide(null) }}
            autoComplete="new-password"
            minLength={MINIMO}
            required
          />
        </Campo>

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

import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import Aviso from '../componentes/comun/Aviso.jsx'
import Campo from '../componentes/comun/Campo.jsx'
import { api, ErrorApi } from '../servicios/api.js'
import { guardarSesion, inicioSegunRol } from '../servicios/sesion.js'
import EntradaClave from './acceso/EntradaClave.jsx'
import '../estilos/acceso.css'

/**
 * Pantalla P-01 · Acceso.
 *
 * El diseño de referencia trae «¿Olvidaste tu contraseña?», «Crear cuenta»,
 * «Continuar con Google», «Mantener sesión iniciada» y perfiles de
 * demostración. Ninguno existe en el backend (no hay recuperación, registro
 * público ni otro proveedor, y la sesión muere con la pestaña), así que no
 * se muestran.
 */
export default function Acceso() {
  useEffect(() => {
    document.title = 'Acceso · SGIP'
    // El armazón no pone título en la primera carga: sin esto se quedaría el de acceso.
    return () => { document.title = 'SGIP' }
  }, [])
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
      // El administrador entra al panel y los demás al catálogo. La
      // regla vive en `inicioSegunRol` porque hay tres sitios que la
      // deciden y repetirla sería suficiente para que entrar y recargar
      // llevaran a pantallas distintas.
      navegar(inicioSegunRol(sesion), { replace: true })
    } catch (e) {
      // El backend no distingue entre correo inexistente, clave errada y
      // cuenta bloqueada, y el front tampoco debe hacerlo: decir cuál falló
      // confirma qué correos están registrados. Su texto se muestra tal cual.
      setError(e instanceof ErrorApi ? e.message : 'No se pudo conectar con el servidor.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <main className="acceso acceso--ingreso">
      <section className="acceso-formulario">
        <p className="acceso-logo">SGIP</p>
        <form onSubmit={enviar}>
          <h1>Inicia sesión en tu cuenta</h1>
          <p className="sub">Ingresa tus datos para continuar a tu espacio de trabajo.</p>

          {error && <Aviso tipo="peligro">{error}</Aviso>}

          <Campo etiqueta="Correo">
            <input
              type="email"
              value={correo}
              onChange={(e) => setCorreo(e.target.value)}
              autoComplete="username"
              required
            />
          </Campo>

          <Campo etiqueta="Contraseña">
            <EntradaClave
              nombre="contraseña"
              value={clave}
              onChange={(e) => setClave(e.target.value)}
              autoComplete="current-password"
              required
            />
          </Campo>

          <button className="boton" type="submit" disabled={enviando}>
            {enviando ? 'Iniciando sesión…' : 'Iniciar sesión'}
          </button>
        </form>
      </section>

      <aside className="acceso-marca">
        <p className="acceso-logo">SGIP</p>
        <p className="acceso-lema">
          Control total.<br />Decisiones más <em>claras.</em>
        </p>
        <p className="acceso-descripcion">Sistema de gestión de inventario y precios.</p>
      </aside>
    </main>
  )
}

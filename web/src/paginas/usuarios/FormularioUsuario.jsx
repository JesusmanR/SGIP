import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import Aviso from '../../componentes/comun/Aviso.jsx'
import Campo from '../../componentes/comun/Campo.jsx'
import EncabezadoPagina from '../../componentes/comun/EncabezadoPagina.jsx'
import { apiUsuarios } from '../../servicios/api/usuarios.js'
import { ROLES } from './roles.js'

const FORMA_DE_CORREO = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

/** Por qué no se puede enviar lo escrito; cada campo con su mensaje, o `null`. */
function motivos(modo, d) {
  const nombre = d.nombre.trim()
  const correo = d.correo.trim()
  const m = {
    nombre: nombre === '' ? 'Escribe el nombre.'
      : nombre.length > 120 ? 'El nombre no puede pasar de 120 caracteres.' : null,
    rol: ROLES.some((r) => r.valor === d.rol) ? null : 'Elige un rol.',
  }
  if (modo === 'crear') {
    m.correo = correo === '' ? 'Escribe el correo.'
      : !FORMA_DE_CORREO.test(correo) ? 'Escribe un correo válido, como nombre@tienda.com.'
        : correo.length > 120 ? 'El correo no puede pasar de 120 caracteres.' : null
    m.clave = d.clave === '' ? 'Escribe la contraseña inicial.'
      : d.clave.length < 8 ? 'La contraseña debe tener al menos 8 caracteres.'
        : d.clave.length > 100 ? 'La contraseña no puede pasar de 100 caracteres.' : null
    m.repite = d.clave !== '' && d.repite !== d.clave ? 'No coincide con la contraseña.' : null
  }
  return m
}

/**
 * Alta y edición de un usuario (P-20).
 *
 * Al crear se escribe una contraseña inicial: el backend deja a la persona
 * con el cambio pendiente, de modo que sirve una sola vez. Al terminar se
 * vacía de la memoria del formulario antes de salir, y nada la vuelve a
 * mostrar. Al editar solo se cambian nombre y rol: no hay ruta para tocar
 * contraseñas ni el correo.
 */
export default function FormularioUsuario({ modo, usuario }) {
  const esAlta = modo === 'crear'
  const navegar = useNavigate()
  const [datos, setDatos] = useState({
    nombre: usuario?.nombre ?? '', correo: '', rol: usuario?.rol ?? '', clave: '', repite: '',
  })
  const [intentos, setIntentos] = useState(0)
  const [enviando, setEnviando] = useState(false)
  const [error, setError] = useState(null)
  const [errorCorreo, setErrorCorreo] = useState(null)
  const formulario = useRef(null)
  const aviso = useRef(null)
  const enCurso = useRef(false)

  const fallos = motivos(modo, datos)
  const ver = (campo) => (intentos > 0 ? fallos[campo] ?? null : null)
  const motivoCorreo = errorCorreo ?? ver('correo')

  useEffect(() => {
    if (intentos > 0) formulario.current?.querySelector('[aria-invalid="true"]')?.focus()
  }, [intentos, errorCorreo])

  useEffect(() => {
    if (error) aviso.current?.focus()
  }, [error])

  const cambiar = (campo) => (e) => {
    const valor = e.target.value
    setDatos((d) => ({ ...d, [campo]: valor }))
    if (campo === 'correo') setErrorCorreo(null)
  }

  async function enviar(e) {
    e.preventDefault()
    if (enCurso.current) return
    setIntentos((n) => n + 1)
    setError(null)
    if (Object.values(fallos).some(Boolean) || errorCorreo) return

    enCurso.current = true
    setEnviando(true)
    try {
      if (esAlta) {
        const creado = await apiUsuarios.crear({
          nombre: datos.nombre.trim(), correo: datos.correo.trim(), clave: datos.clave, rol: datos.rol,
        })
        // La contraseña no sobrevive a la creación.
        setDatos((d) => ({ ...d, clave: '', repite: '' }))
        navegar(`/usuarios/${creado.usuarioId}`, {
          state: { aviso: 'Usuario creado. Tendrá que cambiar su contraseña la primera vez que entre.' },
        })
      } else {
        await apiUsuarios.editar(usuario.usuarioId, { nombre: datos.nombre.trim(), rol: datos.rol })
        navegar(`/usuarios/${usuario.usuarioId}`, { state: { aviso: 'Cambios guardados.' } })
      }
    } catch (err) {
      enCurso.current = false
      setEnviando(false)
      if (esAlta && err.estado === 409) setErrorCorreo(err.message)
      else setError(err.message || 'No se pudo completar la operación.')
    }
  }

  const volver = esAlta ? '/usuarios' : `/usuarios/${usuario.usuarioId}`

  return (
    <div className="contenido usuarios">
      <p className="volver"><Link to={volver}>{esAlta ? 'Volver a usuarios' : 'Volver a la ficha'}</Link></p>
      <EncabezadoPagina
        titulo={esAlta ? 'Nuevo usuario' : `Editar a ${usuario.nombre}`}
        descripcion={esAlta ? 'Crea la cuenta de una persona de tu negocio.' : undefined}
      />

      <form className="tarjeta formulario-usuario" onSubmit={enviar} noValidate ref={formulario}>
        {error && (
          <div tabIndex={-1} ref={aviso} className="aviso-foco">
            <Aviso tipo="peligro">{error}</Aviso>
          </div>
        )}

        <Campo etiqueta="Nombre" motivo={ver('nombre')}>
          <input type="text" autoComplete="off" value={datos.nombre} onChange={cambiar('nombre')} />
        </Campo>

        {esAlta ? (
          <Campo etiqueta="Correo" motivo={motivoCorreo}>
            <input type="email" autoComplete="off" value={datos.correo} onChange={cambiar('correo')} />
          </Campo>
        ) : (
          <div className="campo">
            <span>Correo</span>
            <p className="dato-fijo">{usuario.correo}</p>
            <small className="campo-mensaje">El correo es con el que entra y no se puede cambiar.</small>
          </div>
        )}

        <Campo etiqueta="Rol" motivo={ver('rol')}>
          <select value={datos.rol} onChange={cambiar('rol')}>
            <option value="">Elige un rol</option>
            {ROLES.map((r) => <option key={r.valor} value={r.valor}>{r.etiqueta}</option>)}
          </select>
        </Campo>

        {esAlta ? (
          <fieldset className="clave-inicial">
            <legend>Primera entrada</legend>
            <p className="nota">
              Es la contraseña con la que la persona entra por primera vez. El sistema le pedirá
              cambiarla en esa primera entrada y deberá cambiarla antes de hacer cualquier otra cosa.
              Entrégasela por un medio privado: esta pantalla no la vuelve a mostrar.
            </p>
            <Campo etiqueta="Contraseña inicial" motivo={ver('clave')}>
              <input type="password" autoComplete="new-password" value={datos.clave} onChange={cambiar('clave')} />
            </Campo>
            <Campo etiqueta="Repite la contraseña" motivo={ver('repite')}>
              <input type="password" autoComplete="new-password" value={datos.repite} onChange={cambiar('repite')} />
            </Campo>
          </fieldset>
        ) : (
          <p className="nota">Editar no cambia contraseñas: solo el nombre y el rol.</p>
        )}

        <div className="acciones">
          <button type="submit" className="boton" disabled={enviando}>
            {esAlta ? (enviando ? 'Creando…' : 'Crear usuario') : (enviando ? 'Guardando…' : 'Guardar cambios')}
          </button>
          <Link to={volver} className="boton secundario enlace-boton">Cancelar</Link>
        </div>
      </form>
    </div>
  )
}

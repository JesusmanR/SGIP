import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import Aviso from '../../componentes/comun/Aviso.jsx'
import EncabezadoPagina from '../../componentes/comun/EncabezadoPagina.jsx'
import { apiPlataformaEmpresas } from '../../servicios/api/plataforma-empresas.js'
import Campo from './Campo.jsx'

const FORMA_DE_CORREO = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const VACIO = {
  nit: '', razonSocial: '', correoContacto: '', telefono: '', ciudad: '',
  administradorNombre: '', administradorCorreo: '', administradorClave: '', repite: '',
}

/**
 * El servidor responde 409 al NIT repetido y también al correo del administrador
 * repetido, con el mismo título y sin ningún campo que diga cuál es: solo el texto
 * del detalle lo distingue (ServicioPlataforma.darDeAlta). Por eso se lee el texto;
 * si algún día cambia, el 409 no reconocido cae en el aviso de arriba, que sigue
 * diciendo lo que pasó, y no en un campo equivocado.
 */
function campoDelConflicto(detalle) {
  if (/^El correo /i.test(detalle)) return 'administradorCorreo'
  if (/identificaci[oó]n tributaria/i.test(detalle)) return 'nit'
  return null
}

const largo = (texto, max, nombre) => (texto.length > max ? `${nombre} no puede pasar de ${max} caracteres.` : null)

/** Por qué no se puede enviar lo escrito, campo por campo, en el orden de la pantalla. */
function motivos(d) {
  const nit = d.nit.trim()
  const razon = d.razonSocial.trim()
  const contacto = d.correoContacto.trim()
  const nombre = d.administradorNombre.trim()
  const correo = d.administradorCorreo.trim()
  return {
    nit: nit === '' ? 'Escribe la identificación tributaria (NIT).' : largo(nit, 20, 'El NIT'),
    razonSocial: razon === '' ? 'Escribe la razón social.' : largo(razon, 160, 'La razón social'),
    correoContacto: contacto !== '' && !FORMA_DE_CORREO.test(contacto)
      ? 'Escribe un correo válido, como contacto@tienda.com.' : largo(contacto, 120, 'El correo'),
    telefono: largo(d.telefono.trim(), 20, 'El teléfono'),
    ciudad: largo(d.ciudad.trim(), 80, 'La ciudad'),
    administradorNombre: nombre === '' ? 'Escribe el nombre del administrador.' : largo(nombre, 120, 'El nombre'),
    administradorCorreo: correo === '' ? 'Escribe el correo del administrador.'
      : !FORMA_DE_CORREO.test(correo) ? 'Escribe un correo válido, como nombre@tienda.com.' : largo(correo, 120, 'El correo'),
    // El DTO exige que no sea solo espacios (@NotBlank): ocho espacios no son una contraseña.
    administradorClave: d.administradorClave.trim() === '' ? 'Escribe la contraseña inicial.'
      : d.administradorClave.length < 8 ? 'La contraseña debe tener al menos 8 caracteres.'
        : largo(d.administradorClave, 100, 'La contraseña'),
    repite: d.administradorClave !== '' && d.repite !== d.administradorClave ? 'No coincide con la contraseña.' : null,
  }
}

/**
 * Pantalla 71 · alta de una empresa con su primer administrador (RF-01).
 * Es una sola operación en el servidor. La empresa nace «en prueba»: el
 * estado no se elige aquí. La contraseña inicial se vacía de la memoria del
 * formulario antes de salir y nada la vuelve a mostrar.
 */
export default function FormularioEmpresa() {
  const navegar = useNavigate()
  const [datos, setDatos] = useState(VACIO)
  const [intentos, setIntentos] = useState(0)
  const [enviando, setEnviando] = useState(false)
  const [error, setError] = useState(null)
  const [conflictos, setConflictos] = useState({})
  const formulario = useRef(null)
  const aviso = useRef(null)
  const enCurso = useRef(false)

  const fallos = motivos(datos)
  // Lo que dijo el servidor va primero: es lo que impide enviar, y se quita al editar su campo.
  const ver = (campo) => conflictos[campo] ?? (intentos > 0 ? fallos[campo] : null)

  // Solo al enviar, no al limpiar un conflicto: si no, corregir un campo le quitaría el foco.
  useEffect(() => {
    if (intentos > 0) formulario.current?.querySelector('[aria-invalid="true"]')?.focus()
  }, [intentos])

  useEffect(() => {
    if (error) aviso.current?.focus()
  }, [error])

  const cambiar = (campo) => (e) => {
    const valor = e.target.value
    setDatos((d) => ({ ...d, [campo]: valor }))
    setConflictos((previos) => {
      if (!(campo in previos)) return previos
      const resto = { ...previos }
      delete resto[campo]
      return resto
    })
  }

  async function enviar(e) {
    e.preventDefault()
    if (enCurso.current) return
    setIntentos((n) => n + 1)
    setError(null)
    if (Object.values(fallos).some(Boolean) || Object.keys(conflictos).length > 0) return

    enCurso.current = true
    setEnviando(true)
    try {
      const creada = await apiPlataformaEmpresas.darDeAlta({
        ...datos,
        nit: datos.nit.trim(),
        razonSocial: datos.razonSocial.trim(),
        administradorNombre: datos.administradorNombre.trim(),
        administradorCorreo: datos.administradorCorreo.trim(),
      })
      setDatos((d) => ({ ...d, administradorClave: '', repite: '' }))
      navegar(`/plataforma/empresas/${creada.empresaId}`, {
        state: {
          aviso: 'Empresa creada. Su administrador entra con la contraseña inicial que escribiste '
            + 'y el sistema le pedirá cambiarla la primera vez. La empresa empieza en prueba.',
        },
      })
    } catch (err) {
      enCurso.current = false
      setEnviando(false)
      const campo = err.estado === 409 ? campoDelConflicto(err.message ?? '') : null
      if (campo) {
        setConflictos((previos) => ({ ...previos, [campo]: err.message }))
        setIntentos((n) => n + 1)
      } else {
        setError(err.message || 'No se pudo completar la operación.')
      }
    }
  }

  const texto = (campo, etiqueta) => (
    <Campo etiqueta={etiqueta} motivo={ver(campo)}>
      <input type="text" autoComplete="off" value={datos[campo]} onChange={cambiar(campo)} />
    </Campo>
  )
  const correo = (campo, etiqueta) => (
    <Campo etiqueta={etiqueta} motivo={ver(campo)}>
      <input type="email" autoComplete="off" value={datos[campo]} onChange={cambiar(campo)} />
    </Campo>
  )
  const clave = (campo, etiqueta) => (
    <Campo etiqueta={etiqueta} motivo={ver(campo)}>
      <input type="password" autoComplete="new-password" value={datos[campo]} onChange={cambiar(campo)} />
    </Campo>
  )

  return (
    <div className="contenido plataforma-empresas">
      <p className="volver"><Link to="/plataforma/empresas">Volver a empresas</Link></p>
      <EncabezadoPagina
        titulo="Nueva empresa"
        descripcion="Registra la tienda y a la persona que la va a administrar. Las dos cosas se crean juntas."
      />

      <form className="tarjeta formulario-empresa" onSubmit={enviar} noValidate ref={formulario}>
        {error && (
          <div tabIndex={-1} ref={aviso} className="aviso-foco">
            <Aviso tipo="peligro">{error}</Aviso>
          </div>
        )}

        <fieldset className="grupo">
          <legend>La empresa</legend>
          {texto('nit', 'Número de identificación tributaria (NIT)')}
          {texto('razonSocial', 'Razón social')}
          {correo('correoContacto', 'Correo de contacto (opcional)')}
          {texto('telefono', 'Teléfono (opcional)')}
          {texto('ciudad', 'Ciudad (opcional)')}
        </fieldset>

        <fieldset className="grupo">
          <legend>Su primer administrador</legend>
          {texto('administradorNombre', 'Nombre del administrador')}
          {correo('administradorCorreo', 'Correo del administrador')}
          <p className="nota">
            Es la contraseña con la que entra la primera vez. El sistema le pedirá cambiarla en esa primera
            entrada. Entrégasela por un medio privado: esta pantalla no la vuelve a mostrar.
          </p>
          {clave('administradorClave', 'Contraseña inicial')}
          {clave('repite', 'Repite la contraseña')}
        </fieldset>

        <div className="acciones">
          <button type="submit" className="boton" disabled={enviando}>
            {enviando ? 'Dando de alta…' : 'Dar de alta'}
          </button>
          <Link to="/plataforma/empresas" className="boton secundario enlace-boton">Cancelar</Link>
        </div>
      </form>
    </div>
  )
}

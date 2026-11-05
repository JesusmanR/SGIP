import { useEffect, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import Aviso from '../../componentes/comun/Aviso.jsx'
import EncabezadoPagina from '../../componentes/comun/EncabezadoPagina.jsx'
import { apiUsuarios } from '../../servicios/api/usuarios.js'
import { fechaCorta } from '../../servicios/formato.js'
import ConUsuario from './ConUsuario.jsx'
import EstadoUsuario from './EstadoUsuario.jsx'
import { etiquetaDeRol } from './roles.js'

/** Lo que cada acción con confirmación le dice a quien la va a hacer. */
const CONFIRMACIONES = {
  desactivar: (u) => ({
    pregunta: `¿Desactivar la cuenta de ${u.nombre}?`,
    detalle: 'No podrá entrar al sistema mientras esté desactivada. No se borra nada: '
      + 'todo lo que registró se conserva y puedes volver a activar la cuenta cuando quieras.',
    boton: 'Sí, desactivar',
    llamar: (id) => apiUsuarios.desactivar(id),
    exito: 'Cuenta desactivada.',
  }),
  desbloquear: (u) => ({
    pregunta: `¿Desbloquear la cuenta de ${u.nombre}?`,
    detalle: 'Podrá volver a intentar entrar. Desbloquear no cambia su contraseña.',
    boton: 'Sí, desbloquear',
    llamar: (id) => apiUsuarios.desbloquear(id),
    exito: 'Cuenta desbloqueada.',
  }),
}

function Ficha({ usuario, recargar, avisoInicial }) {
  const [confirmando, setConfirmando] = useState(null)
  const [enviando, setEnviando] = useState(false)
  const [exito, setExito] = useState(avisoInicial ?? null)
  const [error, setError] = useState(null)
  const caja = useRef(null)
  const enCurso = useRef(false)

  useEffect(() => {
    if (confirmando) caja.current?.focus()
  }, [confirmando])

  async function ejecutar(llamar, mensajeExito) {
    if (enCurso.current) return
    enCurso.current = true
    setEnviando(true)
    setError(null)
    setExito(null)
    try {
      await llamar(usuario.usuarioId)
      setConfirmando(null)
      setExito(mensajeExito)
      recargar()
    } catch (e) {
      setConfirmando(null)
      setError(e.message || 'No se pudo completar la operación.')
    } finally {
      enCurso.current = false
      setEnviando(false)
    }
  }

  const pedida = confirmando ? CONFIRMACIONES[confirmando](usuario) : null

  return (
    <div className="contenido usuarios">
      <p className="volver"><Link to="/usuarios">Volver a usuarios</Link></p>
      <EncabezadoPagina
        titulo={usuario.nombre}
        acciones={<Link to={`/usuarios/${usuario.usuarioId}/editar`} className="boton secundario enlace-boton">Editar</Link>}
      />

      {exito && <Aviso tipo="exito">{exito}</Aviso>}
      {error && <Aviso tipo="peligro">{error}</Aviso>}

      <section className="tarjeta ficha" aria-label="Datos del usuario">
        <dl>
          <div><dt>Correo</dt><dd>{usuario.correo}</dd></div>
          <div><dt>Rol</dt><dd>{etiquetaDeRol(usuario.rol)}</dd></div>
          <div><dt>Estado</dt><dd><EstadoUsuario usuario={usuario} /></dd></div>
          <div><dt>Creado</dt><dd>{fechaCorta(usuario.creadoEn)}</dd></div>
        </dl>
        {usuario.bloqueado && (
          <p className="nota">
            La cuenta se bloqueó por demasiados intentos fallidos de entrada.
            Desbloquear no cambia la contraseña.
          </p>
        )}
        {usuario.cambioClaveRequerido && (
          <p className="nota">
            Entra con la contraseña inicial que le diste y el sistema le pedirá cambiarla.
          </p>
        )}
      </section>

      {pedida ? (
        <div className="confirmacion" role="group" aria-labelledby="confirmacion-pregunta" tabIndex={-1} ref={caja}>
          <p id="confirmacion-pregunta" className="confirmacion-pregunta">{pedida.pregunta}</p>
          <p>{pedida.detalle}</p>
          <div className="acciones">
            <button
              type="button" className="boton" disabled={enviando}
              onClick={() => ejecutar(pedida.llamar, pedida.exito)}
            >
              {pedida.boton}
            </button>
            <button type="button" className="boton secundario" disabled={enviando} onClick={() => setConfirmando(null)}>
              Cancelar
            </button>
          </div>
        </div>
      ) : (
        <div className="acciones">
          {usuario.bloqueado && (
            <button type="button" className="boton" onClick={() => setConfirmando('desbloquear')}>Desbloquear</button>
          )}
          {usuario.activo ? (
            <button type="button" className="boton secundario" onClick={() => setConfirmando('desactivar')}>Desactivar</button>
          ) : (
            <button
              type="button" className="boton" disabled={enviando}
              onClick={() => ejecutar((id) => apiUsuarios.activar(id), 'Cuenta activada.')}
            >
              Activar
            </button>
          )}
        </div>
      )}
    </div>
  )
}

/** Pantalla P-20 · ficha de un usuario con sus acciones. */
export default function DetalleUsuario({ usuarioId }) {
  const aviso = useLocation().state?.aviso
  return (
    <ConUsuario
      usuarioId={usuarioId}
      hijos={(usuario, recargar) => <Ficha usuario={usuario} recargar={recargar} avisoInicial={aviso} />}
    />
  )
}

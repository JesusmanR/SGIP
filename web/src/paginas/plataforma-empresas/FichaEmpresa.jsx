import { useEffect, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import Aviso from '../../componentes/comun/Aviso.jsx'
import EncabezadoPagina from '../../componentes/comun/EncabezadoPagina.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import EstadoVacio from '../../componentes/comun/EstadoVacio.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { apiPlataformaEmpresas } from '../../servicios/api/plataforma-empresas.js'
import { fechaCorta, NUMERO } from '../../servicios/formato.js'
import Campo from './Campo.jsx'
import EstadoEmpresa from './EstadoEmpresa.jsx'

const VOLVER = <Link to="/plataforma/empresas">Volver a empresas</Link>

/** Lo que cada cambio de estado le dice a quien lo va a hacer. */
function confirmacionDe(accion, e) {
  if (accion === 'desactivar') {
    const usuarios = e.usuarios === 1 ? 'Su único usuario' : `Sus ${NUMERO.format(e.usuarios)} usuarios`
    return {
      pregunta: `¿Desactivar a ${e.razonSocial}?`,
      detalle: `${usuarios} podrán entrar y solo consultar: dejan de poder registrar ventas, compras, `
        + 'movimientos o cambios. No se borra nada: todo lo registrado se conserva y puedes volver a '
        + 'activar la empresa cuando quieras.',
      boton: 'Sí, desactivar',
      exito: 'Empresa desactivada.',
    }
  }
  return {
    pregunta: `¿Activar a ${e.razonSocial}?`,
    detalle: 'Sus usuarios vuelven a poder trabajar con normalidad.',
    boton: 'Sí, activar',
    exito: 'Empresa activada.',
  }
}

/**
 * Pantallas 74 y 75, «Accesos»: la única acción de acceso que existe en el
 * servidor es desbloquear. No hay ruta para ver ni cambiar contraseñas, y
 * tampoco para listar los usuarios de una empresa: por eso se pide el número
 * del usuario, que el administrador de la empresa ve en su ficha.
 */
function Desbloqueo({ empresaId, alExito }) {
  const [numero, setNumero] = useState('')
  const [motivo, setMotivo] = useState(null)
  const [confirmando, setConfirmando] = useState(null)
  const [enviando, setEnviando] = useState(false)
  const [error, setError] = useState(null)
  const campo = useRef(null)
  const caja = useRef(null)
  const enCurso = useRef(false)

  useEffect(() => {
    if (confirmando !== null) caja.current?.focus()
  }, [confirmando])

  function pedir(e) {
    e.preventDefault()
    setError(null)
    const limpio = numero.trim()
    if (!/^\d+$/.test(limpio) || Number(limpio) < 1) {
      setMotivo('Escribe el número del usuario, solo con cifras.')
      setTimeout(() => campo.current?.focus(), 0)
      return
    }
    setMotivo(null)
    setConfirmando(Number(limpio))
  }

  async function desbloquear() {
    if (enCurso.current) return
    enCurso.current = true
    setEnviando(true)
    try {
      await apiPlataformaEmpresas.desbloquearUsuario(empresaId, confirmando)
      alExito(`Usuario ${confirmando} desbloqueado.`)
      setNumero('')
    } catch (e) {
      setError(e.message || 'No se pudo completar la operación.')
    } finally {
      enCurso.current = false
      setEnviando(false)
      setConfirmando(null)
    }
  }

  return (
    <section className="tarjeta desbloqueo" aria-label="Desbloquear a un usuario">
      <h2>Desbloquear a un usuario</h2>
      <p className="nota">
        Una cuenta se bloquea tras varios intentos fallidos de entrada. Desbloquearla no cambia la contraseña:
        la persona sigue entrando con la suya. El bloqueo también se quita solo a los 15 minutos.
      </p>
      {error && <Aviso tipo="peligro">{error}</Aviso>}
      {confirmando !== null ? (
        <div className="confirmacion" role="group" aria-labelledby="desbloqueo-pregunta" tabIndex={-1} ref={caja}>
          <p id="desbloqueo-pregunta" className="confirmacion-pregunta">{`¿Desbloquear al usuario ${confirmando}?`}</p>
          <p>
            Podrá volver a intentar entrar. Esto no cambia la contraseña de esa persona. Si ese número no es de esta empresa,
            el sistema no hace nada y te lo avisa.
          </p>
          <div className="acciones">
            <button type="button" className="boton" disabled={enviando} onClick={desbloquear}>Sí, desbloquear</button>
            <button type="button" className="boton secundario" disabled={enviando} onClick={() => setConfirmando(null)}>
              Cancelar
            </button>
          </div>
        </div>
      ) : (
        <form onSubmit={pedir} noValidate className="fila-desbloqueo">
          <Campo
            etiqueta="Número del usuario" motivo={motivo}
            ayuda="Lo ve el administrador de esa empresa en la ficha del usuario."
          >
            <input type="text" inputMode="numeric" autoComplete="off" ref={campo} value={numero} onChange={(e) => setNumero(e.target.value)} />
          </Campo>
          <button type="submit" className="boton secundario">Desbloquear</button>
        </form>
      )}
    </section>
  )
}

function Ficha({ empresa, recargar, avisoInicial }) {
  const [confirmando, setConfirmando] = useState(null)
  const [motivo, setMotivo] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [exito, setExito] = useState(avisoInicial ?? null)
  const [error, setError] = useState(null)
  const caja = useRef(null)
  const enCurso = useRef(false)

  useEffect(() => {
    if (confirmando) caja.current?.focus()
  }, [confirmando])

  async function cambiarEstado() {
    if (enCurso.current) return
    enCurso.current = true
    setEnviando(true)
    setError(null)
    setExito(null)
    const llamar = confirmando === 'activar' ? apiPlataformaEmpresas.activar : apiPlataformaEmpresas.desactivar
    const mensaje = confirmacionDe(confirmando, empresa).exito
    try {
      await llamar(empresa.empresaId, motivo.trim())
      setConfirmando(null)
      setMotivo('')
      setExito(mensaje)
      recargar()
    } catch (e) {
      setConfirmando(null)
      setError(e.message || 'No se pudo completar la operación.')
    } finally {
      enCurso.current = false
      setEnviando(false)
    }
  }

  function abrir(accion) {
    setError(null)
    setExito(null)
    setMotivo('')
    setConfirmando(accion)
  }

  const pedida = confirmando ? confirmacionDe(confirmando, empresa) : null

  return (
    <div className="contenido plataforma-empresas">
      <p className="volver">{VOLVER}</p>
      <EncabezadoPagina titulo={empresa.razonSocial} />

      {exito && <Aviso tipo="exito">{exito}</Aviso>}
      {error && <Aviso tipo="peligro">{error}</Aviso>}

      <section className="tarjeta ficha" aria-label="Datos de la empresa">
        <dl>
          <div><dt>NIT</dt><dd>{empresa.nit}</dd></div>
          <div><dt>Estado</dt><dd><EstadoEmpresa estado={empresa.estado} /></dd></div>
          <div><dt>Ciudad</dt><dd>{empresa.ciudad || '—'}</dd></div>
          <div><dt>Correo</dt><dd>{empresa.correoContacto || '—'}</dd></div>
          <div><dt>Teléfono</dt><dd>{empresa.telefono || '—'}</dd></div>
          <div><dt>Usuarios</dt><dd>{NUMERO.format(empresa.usuarios)}</dd></div>
          <div><dt>Creada</dt><dd>{fechaCorta(empresa.creadaEn)}</dd></div>
        </dl>
      </section>

      {pedida ? (
        <div className="confirmacion" role="group" aria-labelledby="confirmacion-pregunta" tabIndex={-1} ref={caja}>
          <p id="confirmacion-pregunta" className="confirmacion-pregunta">{pedida.pregunta}</p>
          <p>{pedida.detalle}</p>
          <Campo etiqueta="Motivo (opcional)" ayuda="Queda en la bitácora de la plataforma.">
            <input type="text" maxLength={200} autoComplete="off" value={motivo} onChange={(e) => setMotivo(e.target.value)} />
          </Campo>
          <div className="acciones">
            <button type="button" className="boton" disabled={enviando} onClick={cambiarEstado}>{pedida.boton}</button>
            <button type="button" className="boton secundario" disabled={enviando} onClick={() => setConfirmando(null)}>
              Cancelar
            </button>
          </div>
        </div>
      ) : (
        <div className="acciones">
          {empresa.estado !== 'ACTIVA' && (
            <button type="button" className="boton" onClick={() => abrir('activar')}>Activar</button>
          )}
          {empresa.estado !== 'DESACTIVADA' && (
            <button type="button" className="boton secundario" onClick={() => abrir('desactivar')}>Desactivar</button>
          )}
        </div>
      )}

      <Desbloqueo empresaId={empresa.empresaId} alExito={(m) => { setError(null); setExito(m) }} />
    </div>
  )
}

/** Ficha de una empresa: sus datos, activar o desactivar y desbloquear usuarios. */
export default function FichaEmpresa({ empresaId }) {
  const aviso = useLocation().state?.aviso
  const { datos, cargando, error, sinPermiso, recargar } = useDatos(
    () => apiPlataformaEmpresas.ver(empresaId),
    [empresaId],
  )

  if (datos) return <Ficha empresa={datos} recargar={recargar} avisoInicial={aviso} />

  let cuerpo
  if (sinPermiso) cuerpo = <EstadoSinPermiso />
  else if (error?.estado === 404) {
    cuerpo = <EstadoVacio titulo="No encontramos esa empresa" detalle="Puede que ya no exista." accion={VOLVER} />
  } else if (error) cuerpo = <EstadoError error={error} alReintentar={recargar} />
  else cuerpo = <EstadoCarga mensaje={cargando ? 'Cargando empresa…' : undefined} />

  return (
    <div className="contenido plataforma-empresas">
      <EncabezadoPagina titulo="Empresa" />
      {cuerpo}
    </div>
  )
}

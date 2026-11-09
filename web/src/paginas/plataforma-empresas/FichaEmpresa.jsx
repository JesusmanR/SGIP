import { useRef, useState } from 'react'
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
import Campo from '../../componentes/comun/Campo.jsx'
import Confirmacion from '../../componentes/comun/Confirmacion.jsx'
import EstadoEmpresa from './EstadoEmpresa.jsx'
import UsuariosEmpresa from './UsuariosEmpresa.jsx'

const VOLVER = <Link to="/plataforma/empresas">Volver a empresas</Link>

/**
 * Quién podrá entrar y solo consultar. Cuenta a quienes tienen la cuenta activa,
 * que es a quienes les cambia algo; el total de la empresa incluye cuentas
 * desactivadas. Sin la lista (cargando o fallida) no hay cifra que dar, y se
 * dice sin ella antes que con una que no es.
 */
function sujetoDe(activos) {
  if (activos === null) return 'Sus usuarios podrán'
  if (activos === 0) return 'Hoy no tiene usuarios activos; quien se active después podrá'
  if (activos === 1) return 'Su único usuario activo podrá'
  return `Sus ${NUMERO.format(activos)} usuarios activos podrán`
}

/** Lo que cada cambio de estado le dice a quien lo va a hacer. */
function confirmacionDe(accion, e, activos) {
  if (accion === 'desactivar') {
    return {
      pregunta: `¿Desactivar a ${e.razonSocial}?`,
      detalle: `${sujetoDe(activos)} entrar, pero solo consultar: no se podrán registrar ventas, compras, `
        + 'movimientos ni cambios. No se borra nada: todo lo registrado se conserva y puedes volver a '
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

function Ficha({ empresa, usuarios, recargar, avisoInicial }) {
  const [confirmando, setConfirmando] = useState(null)
  const [motivo, setMotivo] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [exito, setExito] = useState(avisoInicial ?? null)
  const [error, setError] = useState(null)
  // A dónde vuelve el foco al cerrarse la confirmación: un botón, o 'estable'.
  const destino = useRef(null)
  const datos = useRef(null)
  const botonActivar = useRef(null)
  const botonDesactivar = useRef(null)
  const enCurso = useRef(false)

  // Los botones se vuelven a crear al cerrarse la confirmación: el foco va al nuevo, o a los datos
  // si el botón que la abrió ya no existe (tras confirmar cambia el estado).
  function volverA() {
    const quien = destino.current ?? confirmando
    destino.current = null
    return { activar: botonActivar, desactivar: botonDesactivar }[quien]?.current ?? datos.current
  }

  const activos = usuarios.datos ? usuarios.datos.filter((u) => u.activo).length : null

  async function cambiarEstado() {
    if (enCurso.current) return
    enCurso.current = true
    setEnviando(true)
    setError(null)
    setExito(null)
    const accion = confirmando
    const llamar = accion === 'activar' ? apiPlataformaEmpresas.activar : apiPlataformaEmpresas.desactivar
    const mensaje = confirmacionDe(accion, empresa, activos).exito
    try {
      await llamar(empresa.empresaId, motivo.trim())
      // El botón que abrió la confirmación cambia con el nuevo estado: el foco va a un lugar estable.
      destino.current = 'estable'
      setConfirmando(null)
      setMotivo('')
      setExito(mensaje)
      recargar()
    } catch (e) {
      destino.current = accion
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

  const pedida = confirmando ? confirmacionDe(confirmando, empresa, activos) : null

  return (
    <div className="contenido plataforma-empresas">
      <p className="volver">{VOLVER}</p>
      <EncabezadoPagina titulo={empresa.razonSocial} />

      {exito && <Aviso tipo="exito">{exito}</Aviso>}
      {error && <Aviso tipo="peligro">{error}</Aviso>}

      <section className="tarjeta ficha" aria-label="Datos de la empresa" tabIndex={-1} ref={datos}>
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
        <Confirmacion
          pregunta={pedida.pregunta}
          detalle={pedida.detalle}
          textoConfirmar={pedida.boton}
          enviando={enviando}
          volverA={volverA}
          alConfirmar={cambiarEstado}
          alCancelar={() => setConfirmando(null)}
        >
          <Campo etiqueta="Motivo (opcional)" ayuda="Queda en la bitácora de la plataforma.">
            <input type="text" maxLength={200} autoComplete="off" value={motivo} onChange={(e) => setMotivo(e.target.value)} />
          </Campo>
        </Confirmacion>
      ) : (
        <div className="acciones">
          {empresa.estado !== 'ACTIVA' && (
            <button type="button" className="boton" ref={botonActivar} onClick={() => abrir('activar')}>Activar</button>
          )}
          {empresa.estado !== 'DESACTIVADA' && (
            <button type="button" className="boton secundario" ref={botonDesactivar} onClick={() => abrir('desactivar')}>
              Desactivar
            </button>
          )}
        </div>
      )}

      <UsuariosEmpresa
        empresaId={empresa.empresaId}
        usuarios={usuarios}
        alExito={(mensaje) => { setError(null); setExito(mensaje) }}
      />
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
  // Se pide a la vez que la empresa y no cuelga de ella: la lista tiene sus propios estados.
  const usuarios = useDatos(() => apiPlataformaEmpresas.listarUsuarios(empresaId), [empresaId])

  if (datos) return <Ficha empresa={datos} usuarios={usuarios} recargar={recargar} avisoInicial={aviso} />

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

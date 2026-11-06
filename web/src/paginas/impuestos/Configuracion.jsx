import { useEffect, useRef, useState } from 'react'
import Aviso from '../../componentes/comun/Aviso.jsx'
import { apiImpuestos } from '../../servicios/api/impuestos.js'
import Campo from './Campo.jsx'
import { fraccionAPorcentajeTexto, porcentajeTextoAFraccion } from './tributos.js'

const DIAS_MAXIMOS = 365

/** Por qué no se puede enviar lo escrito; cada campo con su mensaje, o `null`. */
function motivos(d) {
  const dias = d.dias.trim()
  let motivoDias = null
  if (dias === '') motivoDias = 'Escribe cuántos días vale una cotización.'
  else if (!/^\d+$/.test(dias)) motivoDias = 'Tiene que ser un número entero de días.'
  else if (Number(dias) < 1 || Number(dias) > DIAS_MAXIMOS) motivoDias = 'Tiene que estar entre 1 y 365 días.'
  return {
    iva: d.responsable === null ? 'Elige Sí o No.' : null,
    ica: d.retenciones ? (porcentajeTextoAFraccion(d.ica).error ?? null) : null,
    dias: motivoDias,
  }
}

/**
 * El PUT reemplaza la configuración entera, así que se manda todo lo que vino
 * del servidor aunque la pantalla no lo muestre: quien solo cambia la
 * respuesta de IVA no debe perder, sin saberlo, su exclusión por tope ni su
 * ICA. El ICA solo se reconvierte si la persona lo tocó.
 */
function armarCuerpo(guardada, d, icaOriginal) {
  let tarifaIca = null
  if (d.retenciones) {
    tarifaIca = d.ica === icaOriginal ? (guardada.tarifaIca ?? null) : porcentajeTextoAFraccion(d.ica).fraccion
  }
  return {
    responsableIva: d.responsable === 'si',
    aplicaExclusionUmbral: d.exclusion,
    practicaRetenciones: d.retenciones,
    tarifaIca,
    manejaEnvios: d.envios,
    cotizacionVigenciaDias: Number(d.dias),
  }
}

/**
 * La configuración general de impuestos de la empresa (GET y PUT
 * `/empresa/tributacion`). Parte de una pregunta: ¿cobra IVA? Con «No» se
 * vende sin configurar nada más (ADR-0024). Una empresa que aún no ha
 * guardado su configuración ve la pregunta sin respuesta: dejarla en «No» ya
 * marcado sería presentar como decidido algo que nadie decidió.
 */
export default function Configuracion({ guardada, alGuardar }) {
  const icaOriginal = fraccionAPorcentajeTexto(guardada.tarifaIca)
  const [datos, setDatos] = useState(() => ({
    responsable: guardada.configuracionCompleta ? (guardada.responsableIva ? 'si' : 'no') : null,
    exclusion: guardada.aplicaExclusionUmbral,
    retenciones: guardada.practicaRetenciones,
    ica: icaOriginal,
    envios: guardada.manejaEnvios,
    dias: String(guardada.cotizacionVigenciaDias),
  }))
  const [intentos, setIntentos] = useState(0)
  const [enviando, setEnviando] = useState(false)
  const [error, setError] = useState(null)
  const [exito, setExito] = useState(null)
  const formulario = useRef(null)
  const aviso = useRef(null)
  const enCurso = useRef(false)

  const fallos = motivos(datos)
  const ver = (campo) => (intentos > 0 ? fallos[campo] : null)

  useEffect(() => {
    if (intentos > 0) formulario.current?.querySelector('[aria-invalid="true"]')?.focus()
  }, [intentos])
  useEffect(() => {
    if (error) aviso.current?.focus()
  }, [error])

  const poner = (campo, valor) => {
    setExito(null)
    setDatos((d) => ({ ...d, [campo]: valor }))
  }
  const texto = (campo) => (e) => poner(campo, e.target.value)
  const marca = (campo) => (e) => poner(campo, e.target.checked)

  async function enviar(e) {
    e.preventDefault()
    if (enCurso.current) return
    setIntentos((n) => n + 1)
    setError(null)
    setExito(null)
    if (Object.values(fallos).some(Boolean)) return

    enCurso.current = true
    setEnviando(true)
    try {
      await apiImpuestos.guardar(armarCuerpo(guardada, datos, icaOriginal))
      setExito('Configuración guardada.')
      alGuardar()
    } catch (err) {
      setError(err.message || 'No se pudo guardar la configuración.')
    } finally {
      enCurso.current = false
      setEnviando(false)
    }
  }

  const motivoIva = ver('iva')

  return (
    <form className="tarjeta configuracion" onSubmit={enviar} noValidate ref={formulario}>
      {error && (
        <div tabIndex={-1} ref={aviso} className="aviso-foco">
          <Aviso tipo="peligro">{error}</Aviso>
        </div>
      )}
      {exito && <Aviso tipo="exito">{exito}</Aviso>}

      <fieldset className="opciones">
        <legend>¿Tu empresa es responsable de IVA?</legend>
        <label className="opcion">
          <input
            type="radio" name="responsable" value="si" checked={datos.responsable === 'si'}
            aria-invalid={motivoIva ? 'true' : undefined} aria-describedby="mensaje-iva"
            onChange={() => poner('responsable', 'si')}
          />
          <span className="opcion-texto">
            <strong>Sí, cobro IVA</strong>
            <small>Después elige qué impuesto lleva cada producto, en las reglas de más abajo.</small>
          </span>
        </label>
        <label className="opcion">
          <input
            type="radio" name="responsable" value="no" checked={datos.responsable === 'no'}
            onChange={() => poner('responsable', 'no')}
          />
          <span className="opcion-texto">
            <strong>No, no cobro IVA</strong>
            <small>Muchos comercios pequeños no están obligados a cobrarlo.</small>
          </span>
        </label>
        <small id="mensaje-iva" role={motivoIva ? 'status' : undefined} className={`campo-mensaje ${motivoIva ? 'error' : ''}`}>
          {motivoIva || ''}
        </small>
      </fieldset>

      {datos.responsable === null && (
        <p className="nota">Mientras no respondas, tus productos se venden al precio, sin IVA.</p>
      )}
      {datos.responsable === 'no' && (
        <p className="nota">
          Puedes vender sin configurar nada más: tus productos se venden al precio, sin IVA.
          Si algún día empiezas a cobrarlo, vuelve aquí y cambia la respuesta.
        </p>
      )}
      {datos.responsable === 'si' && (
        <label className="casilla">
          <input type="checkbox" checked={datos.exclusion} onChange={marca('exclusion')} />
          Aplico la exclusión de IVA por tope (computadores y celulares baratos)
        </label>
      )}

      <fieldset className="otros-datos">
        <legend>Otros datos de tu negocio</legend>
        <label className="casilla">
          <input type="checkbox" checked={datos.retenciones} onChange={marca('retenciones')} />
          Practico retenciones
        </label>
        {datos.retenciones && (
          <Campo etiqueta="Tarifa de ICA (%)" motivo={ver('ica')} ayuda="Déjala vacía si no pagas ICA. La fija tu municipio.">
            <input type="text" inputMode="decimal" autoComplete="off" value={datos.ica} onChange={texto('ica')} />
          </Campo>
        )}
        <label className="casilla">
          <input type="checkbox" checked={datos.envios} onChange={marca('envios')} />
          Manejo envíos
        </label>
        <Campo etiqueta="Días que vale una cotización" motivo={ver('dias')} ayuda="De 1 a 365 días.">
          <input type="text" inputMode="numeric" autoComplete="off" value={datos.dias} onChange={texto('dias')} />
        </Campo>
      </fieldset>

      <div className="acciones">
        <button type="submit" className="boton" disabled={enviando}>
          {enviando ? 'Guardando…' : 'Guardar configuración'}
        </button>
      </div>
    </form>
  )
}

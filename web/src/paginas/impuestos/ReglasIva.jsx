import { useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import Aviso from '../../componentes/comun/Aviso.jsx'
import Confirmacion from '../../componentes/comun/Confirmacion.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import TablaOTarjetas from '../../componentes/comun/TablaOTarjetas.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { apiImpuestos } from '../../servicios/api/impuestos.js'
import { estadoDeVigencia, fechaDia, hoyISO, textoDeVigencia } from './fechas.js'
import { descripcionDeImpuesto, textoDeAlcance } from './tributos.js'

/** Lo que cada acción le dice a quien la va a hacer; todo en palabras de tienda. */
function confirmacion(accion, regla, hoy) {
  const donde = textoDeAlcance(regla)
  if (accion === 'retirar') {
    return {
      pregunta: '¿Retirar esta regla?',
      detalle: `La regla de ${donde} todavía no empieza. Se borra: como todavía no empezó, no explicó ninguna venta.`,
      boton: 'Sí, retirar',
      hasta: undefined,
      exito: 'Regla retirada.',
    }
  }
  const empiezaHoy = regla.vigenteDesde >= hoy
  return {
    pregunta: '¿Cerrar esta regla?',
    detalle: `${empiezaHoy
      ? `La regla de ${donde} empezó hoy: se aplicará hasta el final de hoy y desde mañana ya no.`
      : `La regla de ${donde} dejará de aplicarse desde hoy.`} No se borra: las ventas ya hechas se siguen explicando con ella. Los productos que alcanzaba quedarán sin regla de IVA hasta que crees otra.`,
    boton: 'Sí, cerrar',
    hasta: empiezaHoy ? hoy : undefined,
    exito: 'Regla cerrada. Se conserva en el historial.',
  }
}

function EstadoRegla({ regla, hoy, responsable }) {
  const estado = estadoDeVigencia(regla, hoy)
  const texto = estado === 'rige' ? 'Rige hoy'
    : estado === 'proxima' ? `Empieza el ${fechaDia(regla.vigenteDesde)}` : 'Cerrada'
  return (
    <span className="estado-con-nota">
      <span className={`estado-regla ${estado}`}>{texto}</span>
      {estado === 'rige' && !responsable && (
        <small>No se cobra mientras la empresa no sea responsable de IVA.</small>
      )}
    </span>
  )
}

function Acciones({ regla, hoy, alElegir }) {
  const estado = estadoDeVigencia(regla, hoy)
  const donde = textoDeAlcance(regla)
  if (estado === 'cerrada') return <span className="sin-acciones">—</span>
  const accion = estado === 'proxima' ? 'retirar' : 'cerrar'
  return (
    <button
      type="button" className="boton secundario menudo"
      aria-label={`${accion === 'retirar' ? 'Retirar' : 'Cerrar'} la regla de ${donde}`}
      onClick={() => alElegir(accion, regla)}
    >
      {accion === 'retirar' ? 'Retirar' : 'Cerrar'}
    </button>
  )
}

/**
 * Las reglas de IVA (GET, crear y cerrar `/empresa/tributacion/reglas`).
 *
 * Se crean y se cierran, no se editan: una venta se explica con la regla que
 * regía el día que se hizo. Una que todavía no empieza se retira (se borra,
 * porque nunca explicó una venta); una que ya rige solo se cierra.
 *
 * `responsable` es lo guardado, no lo que se está marcando en el formulario.
 * Una empresa que no cobra IVA y no tiene reglas no ve esta sección: no le
 * hace falta.
 */
export default function ReglasIva({ responsable, avisoInicial }) {
  const { datos, error, sinPermiso, recargar } = useDatos(() => apiImpuestos.reglas(), [])
  const [verCerradas, setVerCerradas] = useState(false)
  const [pidiendo, setPidiendo] = useState(null)
  const [enviando, setEnviando] = useState(false)
  const [exito, setExito] = useState(avisoInicial ?? null)
  const [fallo, setFallo] = useState(null)
  const seccion = useRef(null)
  const enCurso = useRef(false)
  const hoy = hoyISO()

  async function ejecutar(pedida) {
    if (enCurso.current) return
    enCurso.current = true
    setEnviando(true)
    setFallo(null)
    setExito(null)
    try {
      await apiImpuestos.cerrarRegla(pidiendo.regla.reglaId, pedida.hasta)
      setPidiendo(null)
      setExito(pedida.exito)
      recargar()
    } catch (e) {
      setPidiendo(null)
      setFallo(e.message || 'No se pudo completar la operación.')
    } finally {
      enCurso.current = false
      setEnviando(false)
    }
  }

  if (sinPermiso) return <EstadoSinPermiso />
  if (error) return <EstadoError error={error} alReintentar={recargar} />
  if (!datos) return <EstadoCarga mensaje="Cargando reglas de IVA…" />
  if (!responsable && datos.length === 0 && !exito) return null

  const rigen = datos.filter((r) => r.vigente)
  const hayCerradas = datos.some((r) => estadoDeVigencia(r, hoy) === 'cerrada')
  const visibles = datos.filter((r) => verCerradas || estadoDeVigencia(r, hoy) !== 'cerrada')
  const pedida = pidiendo ? confirmacion(pidiendo.accion, pidiendo.regla, hoy) : null

  const columnas = [
    { clave: 'alcance', titulo: 'Se aplica a', principal: true, render: (r) => textoDeAlcance(r) },
    {
      clave: 'impuesto', titulo: 'Impuesto',
      render: (r) => (
        <span className="impuesto-regla">
          <span>{r.impuesto}</span>
          <small>{descripcionDeImpuesto(r.tratamiento, r.tarifa)}</small>
        </span>
      ),
    },
    { clave: 'vigencia', titulo: 'Vigencia', render: (r) => textoDeVigencia(r) },
    { clave: 'estado', titulo: 'Estado', render: (r) => <EstadoRegla regla={r} hoy={hoy} responsable={responsable} /> },
    { clave: 'acciones', titulo: 'Acciones', render: (r) => <Acciones regla={r} hoy={hoy} alElegir={(accion, regla) => setPidiendo({ accion, regla })} /> },
  ]

  return (
    <section className="tarjeta reglas-iva" aria-labelledby="titulo-reglas-iva" tabIndex={-1} ref={seccion}>
      <div className="seccion-cabecera">
        <div>
          <h2 id="titulo-reglas-iva">Reglas de IVA</h2>
          <p className="nota">
            Dicen qué impuesto lleva cada producto: todos, los de una categoría o uno solo. Si varias
            alcanzan al mismo producto, gana la más específica.
          </p>
        </div>
        <Link to="/impuestos/reglas/nueva" className="boton enlace-boton">Nueva regla</Link>
      </div>

      {responsable && rigen.length === 0 && (
        <Aviso tipo="aviso">
          Falta configurar: dijiste que cobras IVA y todavía no hay una regla que rija. Mientras tanto, tus
          productos se venden al precio, sin IVA, y salen marcados con un aviso. Esto no impide vender.
        </Aviso>
      )}
      {responsable && rigen.length > 0 && !rigen.some((r) => r.especificidad === 0) && (
        <p className="nota">
          Los productos que ninguna regla alcanza se venden sin IVA y salen marcados con un aviso.
        </p>
      )}
      {!responsable && (
        <p className="nota">
          Tus reglas se conservan, pero no se aplican mientras tu respuesta sea «No»: si vuelves a cobrar IVA,
          rigen otra vez.
        </p>
      )}

      {exito && <Aviso tipo="exito">{exito}</Aviso>}
      {fallo && <Aviso tipo="peligro">{fallo}</Aviso>}

      {pedida && (
        <Confirmacion
          pregunta={pedida.pregunta}
          detalle={pedida.detalle}
          textoConfirmar={pedida.boton}
          enviando={enviando}
          focoEstable={seccion}
          alConfirmar={() => ejecutar(pedida)}
          alCancelar={() => setPidiendo(null)}
        />
      )}

      {hayCerradas && (
        <label className="casilla">
          <input type="checkbox" checked={verCerradas} onChange={(e) => setVerCerradas(e.target.checked)} />
          Ver también las reglas cerradas
        </label>
      )}
      {datos.length === 0 ? (
        <p className="nota">Todavía no hay reglas de IVA.</p>
      ) : (
        visibles.length > 0 && (
          <TablaOTarjetas etiqueta="Reglas de IVA" columnas={columnas} filas={visibles} claveFila={(r) => r.reglaId} />
        )
      )}
      <p className="nota">
        Una regla no se edita: para cambiar una que ya rige, ciérrala y crea la nueva.
      </p>
    </section>
  )
}

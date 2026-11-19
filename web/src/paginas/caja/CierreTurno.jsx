import { useState } from 'react'
import { Link } from 'react-router-dom'
import Aviso from '../../componentes/comun/Aviso.jsx'
import Campo from '../../componentes/comun/Campo.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { cerrarTurno, resumenDelTurno } from '../../servicios/api/caja.js'
import { PESOS } from '../../servicios/formato.js'
import '../../estilos/caja.css'
import { nombreDeMedio, textoDeDiferencia } from './formatoCaja.js'
import SeccionCaja from './SeccionCaja.jsx'

const ENTERO = /^\d{1,12}$/

function Fila({ rotulo, valor }) {
  return <div><dt>{rotulo}</dt><dd className="cifra">{PESOS.format(valor)}</dd></div>
}

/** Los totales del turno: el cajón y lo cobrado por cada medio de pago. */
function ResumenDelTurno({ turno }) {
  const medios = Object.entries(turno.porMedio ?? {})
  return (
    <SeccionCaja numero="01" titulo="Resumen del turno" descripcion="Lo que el sistema espera encontrar al cerrar.">
      <dl className="caja-desglose">
        <Fila rotulo="Base de efectivo" valor={turno.baseEfectivo} />
        <Fila rotulo="Efectivo esperado" valor={turno.efectivoEsperado} />
      </dl>
      <h3 className="caja-subtitulo-lista">Cobrado por medio de pago</h3>
      {medios.length === 0
        ? <p className="nota">En este turno no se cobró nada.</p>
        : (
          <dl className="caja-desglose">
            {medios.map(([tipo, monto]) => <Fila key={tipo} rotulo={nombreDeMedio(tipo)} valor={monto} />)}
          </dl>
        )}
    </SeccionCaja>
  )
}

function Cerrado({ turno }) {
  return (
    <section className="caja-seccion caja-cerrado" aria-labelledby="turno-cerrado">
      <h2 id="turno-cerrado" className="caja-seccion-titulo">Turno cerrado</h2>
      <dl className="caja-desglose">
        <Fila rotulo="Efectivo esperado" valor={turno.efectivoEsperado} />
        <Fila rotulo="Efectivo contado" valor={turno.efectivoContado} />
      </dl>
      <p className="caja-diferencia" role="status">{textoDeDiferencia(turno.diferencia, { cuadra: 'El efectivo cuadra' })}</p>
      {turno.observacion && <p className="nota">Observación: {turno.observacion}</p>}
      <Link to="/caja" className="boton enlace-boton">Volver a la caja</Link>
    </section>
  )
}

/**
 * Cerrar el turno (RF-45): el cajero cuenta el efectivo del cajón y el sistema le dice cuánto
 * sobra o falta frente a lo que esperaba. Si hay diferencia, explicarla es obligatorio.
 */
export default function CierreTurno() {
  const turno = useDatos(() => resumenDelTurno())
  const [contado, setContado] = useState('')
  const [observacion, setObservacion] = useState('')
  const [motivoContado, setMotivoContado] = useState(null)
  const [motivoObservacion, setMotivoObservacion] = useState(null)
  const [fallo, setFallo] = useState(null)
  const [enviando, setEnviando] = useState(false)
  const [cerrado, setCerrado] = useState(null)

  const resumen = turno.datos
  const valido = ENTERO.test(contado.trim())
  const diferencia = resumen && valido ? Number(contado.trim()) - Number(resumen.efectivoEsperado) : null

  async function enviar(evento) {
    evento.preventDefault()
    setFallo(null)
    const texto = contado.trim()
    if (texto === '') return setMotivoContado('Escribe el efectivo que contaste; si no hay, escribe 0.')
    if (!ENTERO.test(texto)) return setMotivoContado('Escribe el efectivo contado en pesos enteros, sin puntos ni comas.')
    setMotivoContado(null)
    if (diferencia !== 0 && observacion.trim() === '') {
      return setMotivoObservacion('Explica la diferencia: por qué sobra o falta efectivo.')
    }
    setMotivoObservacion(null)
    setEnviando(true)
    try {
      setCerrado(await cerrarTurno({ efectivoContado: Number(texto), observacion: observacion.trim() || null }))
    } catch (error) {
      setFallo(error.message || 'No se pudo cerrar el turno.')
      setEnviando(false)
    }
  }

  let cuerpo
  if (cerrado) cuerpo = <Cerrado turno={cerrado} />
  else if (turno.cargando) cuerpo = <EstadoCarga />
  else if (turno.sinPermiso) cuerpo = <EstadoSinPermiso />
  // Un 404 o un resumen vacío (204) quieren decir lo mismo: no hay turno abierto.
  else if (turno.error?.estado === 404 || (!turno.error && !resumen)) {
    cuerpo = (
      <>
        <Aviso tipo="aviso">No hay un turno abierto. Ábrelo desde la caja para poder venderle.</Aviso>
        <Link to="/caja" className="boton enlace-boton">Volver a la caja</Link>
      </>
    )
  } else if (turno.error) cuerpo = <EstadoError error={turno.error} alReintentar={turno.recargar} />
  else {
    cuerpo = (
      <div className="caja-secciones">
        <ResumenDelTurno turno={resumen} />
        <SeccionCaja numero="02" titulo="Cuenta el efectivo" descripcion="Cuenta lo que hay en el cajón, con la base incluida.">
          {fallo && <Aviso tipo="peligro">{fallo}</Aviso>}
          <form onSubmit={enviar} noValidate>
            <Campo etiqueta="Efectivo contado" motivo={motivoContado ?? undefined}>
              <input type="text" inputMode="numeric" autoComplete="off" value={contado} onChange={(e) => setContado(e.target.value)} />
            </Campo>
            <p className="caja-diferencia" role="status" aria-label="Diferencia">
              {diferencia === null ? '' : textoDeDiferencia(diferencia, { cuadra: 'El efectivo cuadra' })}
            </p>
            <Campo
              etiqueta="Observación" motivo={motivoObservacion ?? undefined}
              ayuda={diferencia ? 'Obligatoria cuando hay diferencia.' : 'Opcional si el efectivo cuadra.'}
            >
              <textarea rows={3} maxLength={500} value={observacion} onChange={(e) => setObservacion(e.target.value)} />
            </Campo>
            <div className="caja-acciones">
              <button type="submit" className="boton" disabled={enviando}>{enviando ? 'Cerrando…' : 'Cerrar turno'}</button>
              <Link to="/caja" className="boton secundario enlace-boton">Cancelar</Link>
            </div>
          </form>
        </SeccionCaja>
      </div>
    )
  }

  return (
    <div className="contenido seccion-caja caja-angosta" aria-busy={turno.cargando || undefined}>
      <header className="caja-cabecera">
        <p className="caja-eyebrow">Caja</p>
        <h1>Cerrar turno</h1>
        <p className="caja-subtitulo">
          {resumen?.cajero ? `Turno de ${resumen.cajero}` : 'Cuenta el efectivo y cierra el turno.'}
        </p>
      </header>
      {cuerpo}
    </div>
  )
}

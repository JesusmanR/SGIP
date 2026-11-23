import { useState } from 'react'
import { Link } from 'react-router-dom'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import EstadoVacio from '../../componentes/comun/EstadoVacio.jsx'
import TablaOTarjetas from '../../componentes/comun/TablaOTarjetas.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { listarTurnos } from '../../servicios/api/caja.js'
import { hoyISO, sumarDias } from '../../servicios/fechas.js'
import { PESOS } from '../../servicios/formato.js'
import { esAdministrador } from '../../servicios/sesion.js'
import '../../estilos/caja.css'
import { fechaHora, textoDeDiferencia } from './formatoCaja.js'

/** La diferencia de un cierre como insignia con texto: el color nunca va solo (ADR-0009). */
function Diferencia({ turno }) {
  if (turno.cierre == null) return <span className="insignia">Abierto</span>
  const n = Number(turno.diferencia)
  const clase = n === 0 ? 'activo' : n > 0 ? 'critico' : 'agotado'
  return <span className={`insignia ${clase}`}>{textoDeDiferencia(n)}</span>
}

/**
 * Los cierres de caja. El administrador ve los de todos los cajeros (`/caja/turnos`); el vendedor
 * llega por `/caja/mis-cierres` y solo recibe los suyos: quien filtra es el servidor, no esta pantalla.
 */
export default function Turnos() {
  const admin = esAdministrador()
  const titulo = admin ? 'Turnos de caja' : 'Mis cierres'
  const [desde, setDesde] = useState(() => sumarDias(hoyISO(), -30))
  const [hasta, setHasta] = useState(() => hoyISO())
  const turnos = useDatos(
    () => (desde && hasta ? listarTurnos({ desde, hasta }) : Promise.resolve([])),
    [desde, hasta],
  )

  const columnas = [
    ...(admin ? [{ clave: 'cajero', titulo: 'Cajero', principal: true, render: (t) => t.cajero }] : []),
    {
      clave: 'apertura', titulo: 'Apertura', principal: !admin, render: (t) => fechaHora(t.apertura),
    },
    { clave: 'cierre', titulo: 'Cierre', render: (t) => (t.cierre ? fechaHora(t.cierre) : '—') },
    { clave: 'base', titulo: 'Base', numerica: true, render: (t) => PESOS.format(t.baseEfectivo) },
    { clave: 'esperado', titulo: 'Esperado', numerica: true, render: (t) => PESOS.format(t.efectivoEsperado) },
    {
      clave: 'contado', titulo: 'Contado', numerica: true,
      render: (t) => (t.efectivoContado == null ? '—' : PESOS.format(t.efectivoContado)),
    },
    { clave: 'diferencia', titulo: 'Diferencia', render: (t) => <Diferencia turno={t} /> },
    { clave: 'observacion', titulo: 'Observación', render: (t) => t.observacion || '—' },
  ]

  let cuerpo
  if (turnos.cargando) cuerpo = <EstadoCarga />
  else if (turnos.sinPermiso) cuerpo = <EstadoSinPermiso />
  else if (turnos.error) cuerpo = <EstadoError error={turnos.error} alReintentar={turnos.recargar} />
  else if ((turnos.datos ?? []).length === 0) {
    cuerpo = <EstadoVacio titulo="No hay turnos en estas fechas." detalle="Prueba con un rango más amplio." />
  } else {
    cuerpo = <TablaOTarjetas etiqueta={titulo} filas={turnos.datos} claveFila={(t) => t.id} columnas={columnas} />
  }

  return (
    <div className="contenido seccion-caja">
      <header className="caja-cabecera caja-cabecera-fila">
        <div>
          <p className="caja-eyebrow">Caja</p>
          <h1>{titulo}</h1>
          <p className="caja-subtitulo">
            {admin ? 'Los cierres de todos los cajeros, con lo que sobró o faltó.' : 'Tus turnos y cómo cerró cada uno.'}
          </p>
        </div>
        <div className="caja-cabecera-acciones">
          <Link to="/caja" className="boton secundario enlace-boton">Volver a la caja</Link>
        </div>
      </header>

      <div className="caja-filtros">
        <label className="campo">
          <span>Desde</span>
          <input type="date" value={desde} max={hasta || undefined} onChange={(e) => setDesde(e.target.value)} />
        </label>
        <label className="campo">
          <span>Hasta</span>
          <input type="date" value={hasta} min={desde || undefined} onChange={(e) => setHasta(e.target.value)} />
        </label>
      </div>

      {cuerpo}
    </div>
  )
}

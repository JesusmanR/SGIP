import { useEffect, useState } from 'react'
import Aviso from '../../componentes/comun/Aviso.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { cambiarActivoMedio, listarMedios } from '../../servicios/api/caja.js'
import '../../estilos/caja.css'

/**
 * Los medios de pago que ofrece la caja. El administrador activa o desactiva; siempre debe quedar al
 * menos uno activo. «Saldo a favor» es un medio de sistema: aparece, pero no se enciende ni se apaga a mano.
 */
export default function MediosPago() {
  const medios = useDatos(() => listarMedios())
  const [filas, setFilas] = useState([])
  const [fallo, setFallo] = useState(null)
  const [cambiando, setCambiando] = useState(null)

  useEffect(() => { if (medios.datos) setFilas(medios.datos) }, [medios.datos])

  const activos = filas.filter((m) => !m.deSistema && m.activo).length

  async function cambiar(medio) {
    setFallo(null)
    const activar = !medio.activo
    if (!activar && activos <= 1) {
      setFallo(`Debe quedar al menos un medio de pago activo. Activa otro antes de desactivar «${medio.nombre}».`)
      return
    }
    setCambiando(medio.id)
    try {
      const nuevo = await cambiarActivoMedio(medio.id, activar)
      setFilas((actuales) => actuales.map((m) => (m.id === medio.id ? { ...m, ...nuevo } : m)))
    } catch (error) {
      setFallo(error.message || 'No se pudo cambiar el medio de pago.')
    } finally {
      setCambiando(null)
    }
  }

  let cuerpo
  if (medios.sinPermiso) cuerpo = <EstadoSinPermiso />
  else if (medios.cargando) cuerpo = <EstadoCarga />
  else if (medios.error) cuerpo = <EstadoError error={medios.error} alReintentar={medios.recargar} />
  else {
    cuerpo = (
      <section className="caja-seccion" aria-label="Medios de pago de la empresa">
        <ul className="caja-medios">
          {filas.map((m) => (
            <li key={m.id}>
              <p className="caja-medio-nombre">{m.nombre}</p>
              {m.deSistema ? (
                <p className="caja-medio-sistema">Lo gestiona el sistema</p>
              ) : (
                <label className="caja-interruptor">
                  <input
                    type="checkbox" role="switch" aria-label={m.nombre} checked={Boolean(m.activo)}
                    disabled={cambiando === m.id} onChange={() => cambiar(m)}
                  />
                  <span>{m.activo ? 'Activo' : 'Inactivo'}</span>
                </label>
              )}
            </li>
          ))}
        </ul>
      </section>
    )
  }

  return (
    <div className="contenido seccion-caja caja-angosta">
      <header className="caja-cabecera">
        <p className="caja-eyebrow">Configuración</p>
        <h1>Medios de pago</h1>
        <p className="caja-subtitulo">Elige con qué se puede pagar en la caja. Debe quedar al menos uno activo.</p>
      </header>
      {fallo && <Aviso tipo="peligro">{fallo}</Aviso>}
      {cuerpo}
    </div>
  )
}

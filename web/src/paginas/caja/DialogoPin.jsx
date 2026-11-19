import { useEffect, useRef, useState } from 'react'
import Aviso from '../../componentes/comun/Aviso.jsx'
import Campo from '../../componentes/comun/Campo.jsx'
import { administradores } from '../../servicios/api/caja.js'
import '../../estilos/caja.css'
import Dialogo from './Dialogo.jsx'

/**
 * La autorización de un administrador, con un solo PIN para todo lo que la venta
 * necesita. El PIN vive solo en el estado de este diálogo: se borra al cerrarlo o
 * tras un intento, y no va al almacenamiento ni a la consola ni a un mensaje.
 *
 * Sirve para cualquier cosa que un administrador autoriza (una venta, una devolución):
 * `descripcion` y `textoAccion` cambian el texto; por omisión es el de la venta.
 *
 * `alConfirmar` recibe `{ administradorId, pin }` y devuelve el texto del error, o
 * null si todo salió bien (entonces la pantalla cierra el diálogo).
 */
export default function DialogoPin({
  motivos, alConfirmar, alCerrar,
  descripcion = 'Un administrador debe autorizar esta venta con su PIN. Se usa una sola vez.',
  textoAccion = 'Autorizar y cobrar', textoEnviando = 'Cobrando…',
}) {
  const [lista, setLista] = useState(null)
  const [fallo, setFallo] = useState(null)
  const [administradorId, setAdministradorId] = useState('')
  const [pin, setPin] = useState('')
  const [error, setError] = useState(null)
  const [enviando, setEnviando] = useState(false)
  const campoPin = useRef(null)

  useEffect(() => {
    let vigente = true
    administradores()
      .then((datos) => {
        if (!vigente) return
        setLista(datos)
        if (datos.length === 1) setAdministradorId(String(datos[0].id))
      })
      .catch((e) => { if (vigente) setFallo(e.message || 'No se pudo traer la lista de administradores.') })
    return () => { vigente = false }
  }, [])

  // Con un solo administrador ya elegido, lo que falta es el PIN: el foco va ahí.
  useEffect(() => {
    if (lista?.length === 1) campoPin.current?.focus()
  }, [lista])

  const puede = administradorId !== '' && pin.length >= 4 && !enviando

  async function enviar(evento) {
    evento.preventDefault()
    if (!puede) return
    setEnviando(true)
    setError(null)
    const mensaje = await alConfirmar({ administradorId: Number(administradorId), pin })
    // Ignorado: ya había un envío en vuelo, que es quien cierra o avisa; aquí se sigue esperando.
    if (mensaje?.ignorado) return
    setEnviando(false)
    if (mensaje) {
      setError(mensaje)
      setPin('')
    }
  }

  return (
    <Dialogo titulo="Autorización del administrador" alCerrar={() => { if (!enviando) alCerrar() }}>
      <p className="nota">{descripcion}</p>
      <ul className="caja-autorizar" aria-label="Lo que se va a autorizar">
        {motivos.map((m) => <li key={m}>{m}</li>)}
      </ul>
      {fallo && <Aviso tipo="peligro">{fallo}</Aviso>}
      {error && <Aviso tipo="peligro">{error}</Aviso>}
      <form onSubmit={enviar} noValidate>
        <Campo etiqueta="Administrador">
          <select value={administradorId} onChange={(e) => setAdministradorId(e.target.value)}>
            <option value="">Elige un administrador</option>
            {(lista ?? []).map((a) => <option key={a.id} value={a.id}>{a.nombre}</option>)}
          </select>
        </Campo>
        <Campo etiqueta="PIN" ayuda="De 4 a 6 números.">
          <input
            type="password" inputMode="numeric" autoComplete="off" maxLength={6} value={pin} ref={campoPin}
            onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))}
          />
        </Campo>
        <div className="caja-acciones">
          <button type="submit" className="boton" disabled={!puede}>
            {enviando ? textoEnviando : textoAccion}
          </button>
          <button type="button" className="boton secundario" disabled={enviando} onClick={alCerrar}>Cancelar</button>
        </div>
      </form>
    </Dialogo>
  )
}

import { useCallback, useEffect, useState } from 'react'
import { apiPrueba } from '../servicios/api/prueba.js'
import '../estilos/contador-prueba.css'

const DIA = 86_400
const CADA_CINCO_MINUTOS = 5 * 60 * 1000
const CADA_SEGUNDO = 1000
// En el último segundo el servidor todavía puede contestar PRUEBA con 0: se
// espera un poco antes de volver a preguntar, para no pedir en bucle.
const MARGEN_AL_LLEGAR_A_CERO = 2000

const ROLES = new Set(['ADMINISTRADOR', 'VENDEDOR'])
const ACTIVAR = 'Para activar tu cuenta, comunícate con el equipo de SGIP.'
const MODO_CONSULTA = 'Tu empresa está en modo consulta: puedes ver toda tu información, pero no registrar cambios.'

/** El tiempo restante en palabras: días mientras falte más de uno, después horas y minutos. */
export function textoDePrueba(segundos) {
  const dias = Math.floor(segundos / DIA)
  if (dias >= 2) return `Quedan ${dias} días de prueba`
  if (dias === 1) return 'Queda 1 día de prueba'
  const horas = Math.floor(segundos / 3600)
  const minutos = Math.floor((segundos % 3600) / 60)
  return horas > 0 ? `Quedan ${horas} h ${minutos} min de prueba` : `Quedan ${minutos} min de prueba`
}

/**
 * Cuánto le queda a la prueba gratuita (RF-73, ADR-0031).
 *
 * El servidor dice los segundos que quedan; entre una petición y otra se
 * descuentan con `performance.now()`, que mide el tiempo desde que se abrió la
 * página y no cambia si alguien adelanta o atrasa la hora del equipo.
 * `Date.now()` sí cambiaría, y por eso no se usa.
 *
 * Nunca estorba: con la empresa activa, con otro rol o si la petición falla,
 * no pinta nada. La escritura la decide el servidor, no esta pantalla.
 */
export default function ContadorPrueba({ rol }) {
  const pregunta = ROLES.has(rol)
  const [datos, setDatos] = useState(null)
  const [, setPulso] = useState(0)

  const cargar = useCallback(async () => {
    try {
      const r = await apiPrueba.estado()
      setDatos(r ? { ...r, recibido: performance.now() } : null)
    } catch {
      setDatos(null)
    }
  }, [])

  useEffect(() => {
    if (!pregunta) return undefined
    cargar()
    const periodica = setInterval(cargar, CADA_CINCO_MINUTOS)
    // Solo vuelve a pintar; la cuenta sale de performance.now() en cada pintada.
    const pulso = setInterval(() => setPulso((n) => n + 1), CADA_SEGUNDO)
    return () => {
      clearInterval(periodica)
      clearInterval(pulso)
    }
  }, [pregunta, cargar])

  const enPrueba = datos?.estado === 'PRUEBA' && typeof datos.segundosRestantes === 'number'
  const restante = enPrueba
    ? Math.max(0, datos.segundosRestantes - Math.floor((performance.now() - datos.recibido) / 1000))
    : null
  const agotada = enPrueba && restante === 0

  useEffect(() => {
    if (!agotada) return undefined
    const espera = setTimeout(cargar, MARGEN_AL_LLEGAR_A_CERO)
    return () => clearTimeout(espera)
  }, [agotada, datos, cargar])

  if (!pregunta || !datos) return null
  const administrador = rol === 'ADMINISTRADOR'

  if (enPrueba) {
    const ultimoDia = restante < DIA
    return (
      <div className={`contador-prueba${ultimoDia ? ' contador-prueba--ultimo-dia' : ''}`} role="status">
        <span className="contador-prueba-tiempo">{textoDePrueba(restante)}</span>
        {administrador && <span className="contador-prueba-ayuda">{ACTIVAR}</span>}
      </div>
    )
  }

  if (datos.estado === 'PRUEBA_VENCIDA' || datos.estado === 'DESACTIVADA') {
    return (
      <div className="contador-prueba contador-prueba--consulta" role="status">
        <span className="contador-prueba-tiempo">{MODO_CONSULTA}</span>
        {administrador && datos.estado === 'PRUEBA_VENCIDA' && (
          <span className="contador-prueba-ayuda">{ACTIVAR}</span>
        )}
      </div>
    )
  }

  return null
}

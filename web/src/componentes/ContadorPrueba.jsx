import { useCallback, useEffect, useRef, useState } from 'react'
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
 *
 * Solo vuelve a preguntar mientras la empresa está en PRUEBA: con ACTIVA o
 * DESACTIVADA no hay nada que contar. Y esas preguntas no alargan la sesión
 * (lo decide el servidor), de modo que una pestaña olvidada sigue caducando.
 *
 * El contador visible no es una región viva: el último día cambia cada
 * minuto y un lector de pantalla lo leería cada vez. Lo que se anuncia, en una
 * región aparte y oculta a la vista, es el cambio de estado (por ejemplo, que
 * la prueba venció), una sola vez.
 */
export default function ContadorPrueba({ rol }) {
  const pregunta = ROLES.has(rol)
  const [datos, setDatos] = useState(null)
  const [, setPulso] = useState(0)
  const [anunciado, setAnunciado] = useState('')
  // Si se sigue preguntando. Lo decide la última respuesta buena: un fallo
  // pasajero en PRUEBA no apaga el sondeo, solo esconde el contador.
  const [sondear, setSondear] = useState(false)
  const estadoAnterior = useRef(null)

  const cargar = useCallback(async () => {
    try {
      const r = await apiPrueba.estado()
      setDatos(r ? { ...r, recibido: performance.now() } : null)
      setSondear(r?.estado === 'PRUEBA')
    } catch {
      setDatos(null)
    }
  }, [])

  useEffect(() => {
    if (pregunta) cargar()
  }, [pregunta, cargar])

  const estado = datos?.estado ?? null

  useEffect(() => {
    if (!sondear) return undefined
    const periodica = setInterval(cargar, CADA_CINCO_MINUTOS)
    return () => clearInterval(periodica)
  }, [sondear, cargar])

  useEffect(() => {
    if (estado !== 'PRUEBA') return undefined
    // Solo vuelve a pintar; la cuenta sale de performance.now() en cada pintada.
    const pulso = setInterval(() => setPulso((n) => n + 1), CADA_SEGUNDO)
    return () => clearInterval(pulso)
  }, [estado])

  // Se anuncia el paso de un estado a otro, no la primera carga ni el minuto.
  useEffect(() => {
    const anterior = estadoAnterior.current
    estadoAnterior.current = estado
    if (anterior === null || estado === null || anterior === estado) return
    if (estado === 'PRUEBA_VENCIDA' || estado === 'DESACTIVADA') setAnunciado(MODO_CONSULTA)
  }, [estado])

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
  const region = <p className="oculto-visualmente" role="status">{anunciado}</p>

  if (enPrueba) {
    const ultimoDia = restante < DIA
    return (
      <>
        <div className={`contador-prueba${ultimoDia ? ' contador-prueba--ultimo-dia' : ''}`}>
          <span className="contador-prueba-tiempo">{textoDePrueba(restante)}</span>
          {administrador && <span className="contador-prueba-ayuda">{ACTIVAR}</span>}
        </div>
        {region}
      </>
    )
  }

  if (datos.estado === 'PRUEBA_VENCIDA' || datos.estado === 'DESACTIVADA') {
    return (
      <>
        <div className="contador-prueba contador-prueba--consulta">
          <span className="contador-prueba-tiempo">{MODO_CONSULTA}</span>
          {administrador && datos.estado === 'PRUEBA_VENCIDA' && (
            <span className="contador-prueba-ayuda">{ACTIVAR}</span>
          )}
        </div>
        {region}
      </>
    )
  }

  return null
}

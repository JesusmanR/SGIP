import { useCallback, useRef, useState } from 'react'
import { ErrorApi } from '../../servicios/api.js'
import { CLAVE_EN_USO, registrarVenta } from '../../servicios/api/caja.js'
import { nuevaClaveDeVenta } from './venta.js'

export const MENSAJE_AMBIGUO = 'No sabemos si se registró. Reintenta: no se cobrará dos veces.'
export const MENSAJE_CLAVE_EN_USO = 'Esta venta ya se registró con otros datos. Revisa las ventas del turno.'

/** Un error que no deja claro si el servidor llegó a registrar la venta: sin respuesta, 5xx o tiempo agotado. */
function esAmbiguo(error) {
  if (!(error instanceof ErrorApi)) return true
  return error.estado >= 500 || error.estado === 408
}

/**
 * El envío de la venta en curso y su clave de idempotencia.
 *
 * - La clave es una por venta: se conserva en cada reintento y solo se renueva cuando el servidor
 *   confirma la venta o la venta se abandona.
 * - Si el resultado es ambiguo (sin respuesta, 5xx, 408) la venta queda en estado «ambiguo»: el
 *   cuerpo exacto que se envió se guarda en memoria y «enviar» lo reenvía tal cual con la misma
 *   clave (el servidor responde 201 si no la tenía o 200 con la venta si ya la había registrado).
 *   La pantalla congela el carrito mientras tanto.
 * - La autorización (con el PIN) viaja en el envío y no se guarda en ese cuerpo: un reintento la
 *   pide de nuevo. El cuerpo vive solo en memoria; recargar la pestaña lo pierde (limitación conocida).
 * - Un error definitivo (400, 403, 409 de existencia…) quiere decir que no se registró: la venta
 *   queda libre y conserva su clave. El 409 de clave en uso (otro cuerpo con la misma clave) no se
 *   puede reintentar: solo queda empezar otra venta.
 *
 * `enviar(construir, autorizacion)` devuelve `{ venta }` o `{ error }`.
 */
export default function useVentaEnCurso() {
  const [clave, setClave] = useState(nuevaClaveDeVenta)
  const [estado, setEstado] = useState('libre') // libre | ambiguo | claveEnUso
  const [enviando, setEnviando] = useState(false)
  const claveActual = useRef(clave)
  const pendiente = useRef(null)
  const ocupado = useRef(false)

  const renovar = useCallback(() => {
    const nueva = nuevaClaveDeVenta()
    claveActual.current = nueva
    pendiente.current = null
    setClave(nueva)
    setEstado('libre')
  }, [])

  const enviar = useCallback(async (construir, autorizacion) => {
    if (ocupado.current) return { error: null }
    ocupado.current = true
    setEnviando(true)
    // Se guarda antes de enviar: si la respuesta no llega, este es el cuerpo que se reenvía.
    const cuerpo = pendiente.current ?? construir(claveActual.current)
    pendiente.current = cuerpo
    try {
      const venta = await registrarVenta(autorizacion ? { ...cuerpo, autorizacion } : cuerpo)
      renovar()
      return { venta }
    } catch (error) {
      if (error instanceof ErrorApi && error.estado === 409 && error.tipo === CLAVE_EN_USO) {
        pendiente.current = null
        setEstado('claveEnUso')
        return { error: MENSAJE_CLAVE_EN_USO }
      }
      if (!esAmbiguo(error)) {
        pendiente.current = null
        setEstado('libre')
        return { error: error.message }
      }
      setEstado('ambiguo')
      return { error: MENSAJE_AMBIGUO }
    } finally {
      ocupado.current = false
      setEnviando(false)
    }
  }, [renovar])

  return { clave, estado, enviando, enviar, abandonar: renovar }
}

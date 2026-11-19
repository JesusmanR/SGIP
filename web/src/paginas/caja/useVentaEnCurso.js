import { useCallback, useRef, useState } from 'react'
import { ErrorApi } from '../../servicios/api.js'
import { CLAVE_EN_USO, registrarVenta } from '../../servicios/api/caja.js'
import { borrarVentaPendiente, guardarVentaPendiente, leerVentaPendiente } from '../../servicios/ventaPendiente.js'
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
 *   pide de nuevo.
 * - Recargar la pestaña no pierde una venta ambigua (M12-R1): la clave, el cuerpo exacto y los
 *   motivos de autorización se guardan en sessionStorage y, al volver, la venta arranca
 *   «ambigua» y `restaurada`. Se borran al confirmarse, ante un error definitivo, una clave en uso
 *   o al abandonar. Un PIN rechazado en una venta restaurada no borra nada: el cajero ya no tiene
 *   el carrito para armarla de nuevo, así que lo único que tiene es ese cuerpo.
 * - Un error definitivo (400, 403, 409 de existencia…) quiere decir que no se registró: la venta
 *   queda libre y conserva su clave. El 409 de clave en uso (otro cuerpo con la misma clave) no se
 *   puede reintentar: solo queda empezar otra venta.
 *
 * `enviar(construir, autorizacion, motivos)` devuelve `{ venta }`, `{ error }` o `{ ignorado: true }` (había otro envío en vuelo).
 */
export default function useVentaEnCurso() {
  // Lo guardado antes de recargar, leído una sola vez al nacer el hook.
  const [guardada] = useState(leerVentaPendiente)
  const [clave, setClave] = useState(() => guardada?.clave ?? nuevaClaveDeVenta())
  const [estado, setEstado] = useState(guardada ? 'ambiguo' : 'libre') // libre | ambiguo | claveEnUso
  const [enviando, setEnviando] = useState(false)
  const [restaurada, setRestaurada] = useState(Boolean(guardada))
  const [motivos, setMotivos] = useState(guardada?.motivos ?? [])
  const claveActual = useRef(clave)
  const pendiente = useRef(guardada?.cuerpo ?? null)
  const restauradaAhora = useRef(Boolean(guardada))
  const ocupado = useRef(false)

  /** Suelta el cuerpo guardado, en memoria y en el almacenamiento. */
  const soltar = useCallback(() => {
    pendiente.current = null
    restauradaAhora.current = false
    borrarVentaPendiente()
    setRestaurada(false)
    setMotivos([])
  }, [])

  const renovar = useCallback(() => {
    const nueva = nuevaClaveDeVenta()
    claveActual.current = nueva
    soltar()
    setClave(nueva)
    setEstado('libre')
  }, [soltar])

  const enviar = useCallback(async (construir, autorizacion, motivosDelEnvio = []) => {
    // Un segundo envío con otro en vuelo no es un éxito ni un error: se ignora con un valor propio.
    if (ocupado.current) return { ignorado: true }
    ocupado.current = true
    setEnviando(true)
    try {
      // Se guarda antes de enviar: si la respuesta no llega, este es el cuerpo que se reenvía.
      let cuerpo
      try {
        cuerpo = pendiente.current ?? construir(claveActual.current)
      } catch {
        // No es un resultado ambiguo: nada salió hacia el servidor.
        return { error: 'No se pudo preparar la venta. Revisa los datos e inténtalo otra vez.' }
      }
      pendiente.current = cuerpo
      const venta = await registrarVenta(autorizacion ? { ...cuerpo, autorizacion } : cuerpo)
      renovar()
      return { venta }
    } catch (error) {
      if (error instanceof ErrorApi && error.estado === 409 && error.tipo === CLAVE_EN_USO) {
        soltar()
        setEstado('claveEnUso')
        return { error: MENSAJE_CLAVE_EN_USO }
      }
      if (!esAmbiguo(error)) {
        // Una venta restaurada no tiene carrito del que volver a armarla: si solo falló el PIN, el
        // cuerpo guardado es lo único que queda y se conserva para intentarlo otra vez.
        if (restauradaAhora.current && autorizacion && error.estado === 403) return { error: error.message }
        soltar()
        setEstado('libre')
        return { error: error.message }
      }
      // Ambiguo: lo exacto que se envió se guarda por si la pestaña se recarga. El PIN no va aquí.
      guardarVentaPendiente({ clave: claveActual.current, cuerpo: pendiente.current, motivos: motivosDelEnvio })
      setMotivos(motivosDelEnvio)
      setEstado('ambiguo')
      return { error: MENSAJE_AMBIGUO }
    } finally {
      ocupado.current = false
      setEnviando(false)
    }
  }, [renovar, soltar])

  return { clave, estado, enviando, enviar, abandonar: renovar, restaurada, motivos }
}

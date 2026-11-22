import { useEffect, useState } from 'react'

/** Si el navegador tiene conexión: la caja no cobra ni devuelve nada a medias sin ella. */
export default function useEnLinea() {
  const [enLinea, setEnLinea] = useState(() => navigator.onLine !== false)
  useEffect(() => {
    const alPerder = () => setEnLinea(false)
    const alVolver = () => setEnLinea(true)
    window.addEventListener('offline', alPerder)
    window.addEventListener('online', alVolver)
    return () => {
      window.removeEventListener('offline', alPerder)
      window.removeEventListener('online', alVolver)
    }
  }, [])
  return enLinea
}

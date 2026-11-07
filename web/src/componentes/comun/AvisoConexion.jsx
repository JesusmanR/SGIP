import { useEffect, useState } from 'react'
import Aviso from './Aviso.jsx'

const SE_QUITA_A_LOS = 5000

/**
 * Dice con palabras que el navegador perdió la conexión y, cuando vuelve,
 * que volvió; ese segundo aviso se quita solo.
 */
export default function AvisoConexion() {
  const [estado, setEstado] = useState(() => (navigator.onLine === false ? 'sin' : 'normal'))

  useEffect(() => {
    const alPerder = () => setEstado('sin')
    const alVolver = () => setEstado('volvio')
    window.addEventListener('offline', alPerder)
    window.addEventListener('online', alVolver)
    return () => {
      window.removeEventListener('offline', alPerder)
      window.removeEventListener('online', alVolver)
    }
  }, [])

  useEffect(() => {
    if (estado !== 'volvio') return undefined
    const espera = setTimeout(() => setEstado('normal'), SE_QUITA_A_LOS)
    return () => clearTimeout(espera)
  }, [estado])

  if (estado === 'sin') {
    return <Aviso tipo="aviso">Sin conexión. No podrás guardar cambios hasta que vuelva.</Aviso>
  }
  if (estado === 'volvio') {
    return <Aviso tipo="exito">Conexión restablecida. Ya puedes continuar.</Aviso>
  }
  return null
}

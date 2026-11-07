import { useEffect, useState } from 'react'

const SE_QUITA_A_LOS = 5000

const TEXTOS = {
  sin: { clase: 'aviso-estado--aviso', rotulo: 'Atención: ', texto: 'Sin conexión. No podrás guardar cambios hasta que vuelva.' },
  volvio: { clase: 'aviso-estado--exito', rotulo: 'Listo: ', texto: 'Conexión restablecida. Ya puedes continuar.' },
}

/**
 * Dice con palabras que el navegador perdió la conexión y, si la perdió,
 * que volvió; ese segundo aviso se quita solo.
 *
 * La región `role="status"` está siempre montada y vacía cuando no hay
 * nada que decir: un lector de pantalla anuncia los cambios dentro de una
 * región que ya existía, no una región que aparece con su texto.
 */
export default function AvisoConexion() {
  const [estado, setEstado] = useState(() => (navigator.onLine === false ? 'sin' : 'normal'))

  useEffect(() => {
    const alPerder = () => setEstado('sin')
    // Volver solo se avisa si antes se perdió.
    const alVolver = () => setEstado((previo) => (previo === 'sin' ? 'volvio' : previo))
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

  const aviso = TEXTOS[estado]
  return (
    <div className="aviso-conexion" role="status">
      {aviso && (
        <div className={`aviso-estado ${aviso.clase}`}>
          <strong>{aviso.rotulo}</strong>{aviso.texto}
        </div>
      )}
    </div>
  )
}

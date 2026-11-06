import { useEffect, useRef, useState } from 'react'
import { useDatos } from '../../hooks/useDatos.js'
import { apiPrecios } from '../../servicios/api/precios.js'
import { textoDeAlcance } from './reglas.js'

/**
 * Pide confirmar el cierre de una regla que rige, y dice qué pasa.
 *
 * Cerrar no borra: la regla queda en el historial y las ventas hechas se
 * siguen explicando con ella. El número de productos es el del backend
 * (`/alcance`), que no cuenta los que tienen precio propio; si no llega, la
 * confirmación sigue siendo válida sin él.
 *
 * Una regla que empieza hoy no se puede cerrar «ayer»: se cierra con la fecha
 * de hoy y rige hasta el final del día.
 */
export default function Cierre({ regla, hoy, alConfirmar, alCancelar, enviando }) {
  const caja = useRef(null)
  const [pulsado, setPulsado] = useState(false)
  const { datos: alcance } = useDatos(
    () => apiPrecios.alcance(regla.categoriaId, regla.proveedorId),
    [regla.reglaId],
  )
  useEffect(() => { caja.current?.focus() }, [])

  const empiezaHoy = regla.vigenteDesde >= hoy
  const productos = alcance?.productos

  return (
    <div className="confirmacion" role="group" aria-labelledby="cierre-pregunta" tabIndex={-1} ref={caja}>
      <p id="cierre-pregunta" className="confirmacion-pregunta">
        {`¿Cerrar la regla de ${textoDeAlcance(regla)}?`}
      </p>
      <p>
        {empiezaHoy
          ? 'Empezó hoy: seguirá aplicando hasta el final de hoy y desde mañana ya no.'
          : 'Deja de aplicar desde hoy.'}
        {' '}No se borra: queda en el historial.
      </p>
      {typeof productos === 'number' && (
        <p>
          {`Hoy alcanza ${productos} ${productos === 1 ? 'producto' : 'productos'} sin precio propio.`}
          {' '}Si otra regla general los alcanza, se usará esa; si no, se quedan sin precio de venta
          hasta que haya otra regla o escribas el precio en el producto.
        </p>
      )}
      <div className="acciones">
        <button
          type="button" className="boton" disabled={enviando || pulsado}
          onClick={() => { setPulsado(true); alConfirmar(empiezaHoy ? hoy : undefined) }}
        >
          Sí, cerrar
        </button>
        <button type="button" className="boton secundario" disabled={enviando} onClick={alCancelar}>
          Cancelar
        </button>
      </div>
    </div>
  )
}

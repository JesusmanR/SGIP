import { useId } from 'react'
import { conSigno, unidades } from './movimientos.js'
import { NUMERO } from '../../servicios/formato.js'

/**
 * La comprobación del RF-49: la existencia de la tabla de productos frente
 * a la suma de sus movimientos. Las dos cifras vienen del servidor; aquí no
 * se recalcula nada desde el kárdex, que solo trae los últimos movimientos.
 *
 * Si no cuadran, salta a la vista y dice qué pasa. No promete que un ajuste
 * lo arregle: el ajuste mueve la existencia y la suma por igual, de modo que
 * la diferencia queda como estaba.
 */
export default function CuadreProducto({ cuadre }) {
  const id = useId()
  const { existencia, sumaDeMovimientos: suma, cuadra } = cuadre
  const diferencia = existencia - suma

  return (
    <section
      className={`cuadre ${cuadra ? 'cuadre-si' : 'cuadre-no'}`}
      aria-labelledby={id}
      role={cuadra ? 'region' : 'alert'}
    >
      <div className="cuadre-cabecera">
        <h2 id={id}>Cuadre</h2>
        <span className={`estado-cuadre ${cuadra ? 'si' : 'no'}`}>{cuadra ? 'Cuadra' : 'No cuadra'}</span>
      </div>
      {cuadra ? (
        <p>La existencia coincide con la suma de los movimientos: el historial explica todo lo que hay.</p>
      ) : (
        <>
          <p>
            La existencia dice {NUMERO.format(existencia)} y los movimientos suman {NUMERO.format(suma)}.
            Hay {unidades(Math.abs(diferencia))} {diferencia > 0 ? 'más' : 'menos'} en la
            existencia de las que explica el historial.
          </p>
          <p>
            Un ajuste no lo arregla, porque mueve la existencia y los movimientos por igual.
            Avisa a quien da soporte al sistema para que lo revise.
          </p>
        </>
      )}
      <dl className="cuadre-cifras">
        <dt>Existencia registrada</dt>
        <dd>{NUMERO.format(existencia)}</dd>
        <dt>Suma de los movimientos</dt>
        <dd>{NUMERO.format(suma)}</dd>
        {!cuadra && (
          <>
            <dt>Sin explicar</dt>
            <dd>{conSigno(diferencia)}</dd>
          </>
        )}
      </dl>
    </section>
  )
}

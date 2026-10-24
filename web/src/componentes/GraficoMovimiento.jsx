import { NUMERO, diaCorto } from '../servicios/formato.js'

/**
 * Ingresos y egresos de los últimos treinta días, en barras.
 *
 * Dibujado a mano en SVG y sin biblioteca de gráficos. Un gráfico no
 * justifica una dependencia: las que hay pesan más que todo el front
 * junto, traen su propio sistema de color —que chocaría con el de
 * `tokens.css`— y producen marcado que no se puede hacer accesible sin
 * pelearse con él.
 *
 * ## Lo que hace accesible a un gráfico
 *
 * Un `<svg>` con barras no dice nada a un lector de pantalla, y el
 * RNF-13 no admite que un elemento comunique solo por forma o color.
 * Aquí va con tres cosas:
 *
 *   · `role="img"` y un `aria-label` con el resumen, de modo que quien
 *     no lo ve recibe la conclusión y no una lista de treinta números;
 *   · una tabla con los mismos datos, oculta visualmente pero presente
 *     en el árbol de accesibilidad, para quien quiera el detalle;
 *   · altura en los dos sentidos además del color, de modo que en
 *     escala de grises las dos series siguen distinguiéndose por su
 *     posición respecto al eje.
 *
 * Esa última es la que importa de verdad: `--exito` y `--peligro` son
 * casi el mismo gris —72,2 frente a 78,8 sobre 255, según la nota de
 * `tokens.css`— de modo que dos series distinguidas solo por esos dos
 * colores serían la misma serie para quien no los distinga. Los
 * ingresos van hacia arriba del eje y los egresos hacia abajo.
 */

const ANCHO = 720
const ALTO = 180
const EJE = ALTO / 2
const HUECO = 2

export default function GraficoMovimiento({ dias }) {
  if (!dias || dias.length === 0) {
    return (
      <p className="vacio-tarjeta">
        Sin movimientos de inventario en los últimos treinta días.
      </p>
    )
  }

  const totalIngresos = dias.reduce((s, d) => s + d.ingresos, 0)
  const totalEgresos = dias.reduce((s, d) => s + d.egresos, 0)

  // La escala se toma del mayor de las dos series y no de cada una por
  // separado: con una escala por serie, un día de dos ingresos y
  // doscientos egresos dibujaría las dos barras del mismo tamaño y el
  // gráfico diría lo contrario de lo que pasó.
  const techo = Math.max(1, ...dias.map((d) => Math.max(d.ingresos, d.egresos)))

  // Treinta columnas de ancho fijo, no una por dato. Con quince días de
  // movimiento y ancho repartido entre quince, cada barra saldría del
  // doble de gruesa y el gráfico parecería un mes lleno.
  const columna = ANCHO / 30
  const ancho = Math.max(3, columna - HUECO)

  const resumen = `Movimiento de inventario de los últimos treinta días: `
    + `${NUMERO.format(totalIngresos)} unidades ingresadas y `
    + `${NUMERO.format(totalEgresos)} despachadas, `
    + `repartidas en ${dias.length} ${dias.length === 1 ? 'día' : 'días'} con movimiento.`

  // La posición se calcula desde la fecha y no desde el índice: el
  // backend solo manda los días con movimiento, de modo que colocar por
  // índice apretaría quince días sueltos en la mitad izquierda y el
  // gráfico mentiría sobre cuándo pasó cada cosa.
  const hoy = new Date()
  hoy.setHours(0, 0, 0, 0)
  const posicion = (iso) => {
    const d = new Date(`${iso}T00:00:00`)
    const atras = Math.round((hoy - d) / 86400000)
    return (29 - Math.min(29, Math.max(0, atras))) * columna
  }

  return (
    <div className="grafico">
      <svg viewBox={`0 0 ${ANCHO} ${ALTO}`} role="img" aria-label={resumen}
           className="grafico-lienzo" preserveAspectRatio="none">
        <line x1="0" y1={EJE} x2={ANCHO} y2={EJE} className="grafico-eje" />
        {dias.map((d) => {
          const x = posicion(d.fecha)
          const arriba = (d.ingresos / techo) * (EJE - 4)
          const abajo = (d.egresos / techo) * (EJE - 4)
          return (
            <g key={d.fecha}>
              {d.ingresos > 0 && (
                <rect className="barra-ingreso" x={x} y={EJE - arriba}
                      width={ancho} height={arriba} />
              )}
              {d.egresos > 0 && (
                <rect className="barra-egreso" x={x} y={EJE}
                      width={ancho} height={abajo} />
              )}
            </g>
          )
        })}
      </svg>

      <p className="grafico-leyenda">
        <span className="llave llave-ingreso" /> Ingresos
        {' · '}
        <span className="llave llave-egreso" /> Egresos
        {' · '}
        <span className="grafico-techo">máximo diario {NUMERO.format(techo)}</span>
      </p>

      {/* El detalle para quien no ve el dibujo. Oculta a la vista, no al
          lector de pantalla: `display: none` la quitaría de los dos.

          La clase va en un <div> envolvente y NO en la <table>. Una
          tabla no respeta `overflow: hidden` —se dimensiona por su
          contenido— de modo que con la clase puesta encima medía 989px
          y empujaba la página 9px más allá del borde en una ventana de
          1024. Medido con Playwright; a ojo no se veía, porque la tabla
          es invisible. */}
      <div className="oculto-visualmente">
      <table>
        <caption>{resumen}</caption>
        <thead>
          <tr><th scope="col">Día</th><th scope="col">Ingresos</th><th scope="col">Egresos</th></tr>
        </thead>
        <tbody>
          {dias.map((d) => (
            <tr key={d.fecha}>
              <th scope="row">{diaCorto(d.fecha)}</th>
              <td>{NUMERO.format(d.ingresos)}</td>
              <td>{NUMERO.format(d.egresos)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      </div>
    </div>
  )
}

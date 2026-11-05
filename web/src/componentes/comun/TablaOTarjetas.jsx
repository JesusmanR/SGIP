import '../../estilos/comun.css'

/**
 * Tabla real en escritorio; a ancho de celular cada fila pasa a tarjeta.
 *
 * El cambio es solo CSS: cada celda lleva su rótulo en data-etiqueta y
 * la hoja de estilos lo pinta con ::before únicamente en la vista de
 * tarjetas, donde la cabecera de la tabla se oculta. Así el lector de
 * pantalla no oye el rótulo dos veces.
 *
 * columnas: { clave, titulo, render?, principal? }. La columna principal
 * es la cabecera de su fila (el nombre del dato).
 */
export default function TablaOTarjetas({ columnas, filas, claveFila, etiqueta }) {
  return (
    <table className="tabla-tarjetas" aria-label={etiqueta}>
      <thead>
        <tr>
          {columnas.map((c) => <th key={c.clave} scope="col">{c.titulo}</th>)}
        </tr>
      </thead>
      <tbody>
        {filas.map((fila) => (
          <tr key={claveFila(fila)}>
            {columnas.map((c) => {
              const contenido = c.render ? c.render(fila) : fila[c.clave]
              return c.principal
                ? <th key={c.clave} scope="row" data-etiqueta={c.titulo}>{contenido}</th>
                : <td key={c.clave} data-etiqueta={c.titulo}>{contenido}</td>
            })}
          </tr>
        ))}
      </tbody>
    </table>
  )
}

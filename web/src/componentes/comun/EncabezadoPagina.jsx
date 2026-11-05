import '../../estilos/comun.css'

/** Cabecera de página: el único h1 de la pantalla, con descripción y acciones opcionales. */
export default function EncabezadoPagina({ titulo, descripcion, acciones }) {
  return (
    <header className="encabezado-pagina">
      <div className="encabezado-texto">
        <h1>{titulo}</h1>
        {descripcion && <p>{descripcion}</p>}
      </div>
      {acciones && <div className="encabezado-acciones">{acciones}</div>}
    </header>
  )
}

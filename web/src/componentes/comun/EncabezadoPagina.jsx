import { useInRouterContext, useLocation } from 'react-router-dom'
import { seccionDe } from '../../secciones.js'
import '../../estilos/comun.css'

/** Etiqueta por defecto: el grupo de la sección donde está la pantalla. */
function EtiquetaDeGrupo() {
  const grupo = seccionDe(useLocation().pathname)?.grupo
  return grupo ? <p className="etiqueta-pagina">{grupo}</p> : null
}

/**
 * Cabecera de página: etiqueta pequeña, el único h1 de la pantalla, subtítulo
 * y acciones opcionales. Sin `etiqueta`, usa el grupo de la sección (si hay
 * ruta). `descripcion` es el nombre anterior de `subtitulo`.
 */
export default function EncabezadoPagina({ etiqueta, titulo, subtitulo, descripcion, acciones }) {
  const enRuta = useInRouterContext()
  const texto = subtitulo ?? descripcion
  return (
    <header className="encabezado-pagina">
      <div className="encabezado-texto">
        {etiqueta ? <p className="etiqueta-pagina">{etiqueta}</p> : enRuta && <EtiquetaDeGrupo />}
        <h1>{titulo}</h1>
        {texto && <p className="subtitulo-pagina">{texto}</p>}
      </div>
      {acciones && <div className="encabezado-acciones">{acciones}</div>}
    </header>
  )
}

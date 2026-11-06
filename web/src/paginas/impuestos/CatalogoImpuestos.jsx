import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import TablaOTarjetas from '../../componentes/comun/TablaOTarjetas.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { apiImpuestos } from '../../servicios/api/impuestos.js'
import { NUMERO } from '../../servicios/formato.js'
import { etiquetaDeTratamiento, tarifaComoPorcentaje } from './tributos.js'

const COLUMNAS = [
  { clave: 'nombre', titulo: 'Impuesto', principal: true },
  { clave: 'tratamiento', titulo: 'Cómo se trata', render: (i) => etiquetaDeTratamiento(i.tratamiento) },
  { clave: 'tarifa', titulo: 'Tarifa', numerica: true, render: (i) => tarifaComoPorcentaje(i.tarifa) },
  {
    clave: 'detalle', titulo: 'Detalle',
    render: (i) => (i.umbralUvt == null
      ? '—'
      : `Excluido hasta ${NUMERO.format(Number(i.umbralUvt))} UVT; después paga la tarifa.`),
  },
]

/**
 * Los tipos de impuesto que publica la plataforma (GET
 * `/empresa/tributacion/impuestos`), de solo lectura: la empresa elige cuál
 * aplica a cada producto, no cuánto vale.
 */
export default function CatalogoImpuestos() {
  const { datos, error, sinPermiso, recargar } = useDatos(() => apiImpuestos.catalogo(), [])

  let cuerpo
  if (sinPermiso) cuerpo = <EstadoSinPermiso />
  else if (error) cuerpo = <EstadoError error={error} alReintentar={recargar} />
  else if (!datos) cuerpo = <EstadoCarga mensaje="Cargando impuestos…" />
  else if (datos.length === 0) cuerpo = <p className="nota">La plataforma todavía no publicó ningún impuesto.</p>
  else {
    cuerpo = (
      <TablaOTarjetas etiqueta="Impuestos disponibles" columnas={COLUMNAS} filas={datos} claveFila={(i) => i.tipoImpuestoId} />
    )
  }

  return (
    <section className="tarjeta catalogo" aria-labelledby="titulo-catalogo">
      <h2 id="titulo-catalogo">Impuestos disponibles</h2>
      <p className="nota">
        Los publica la plataforma y no se cambian aquí: tú eliges cuál lleva cada producto, no cuánto vale.
      </p>
      {cuerpo}
    </section>
  )
}

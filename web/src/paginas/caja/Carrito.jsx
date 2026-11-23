import TablaOTarjetas from '../../componentes/comun/TablaOTarjetas.jsx'
import Campo from '../../componentes/comun/Campo.jsx'
import { hoyISO, sumarDias } from '../../servicios/fechas.js'
import { PESOS } from '../../servicios/formato.js'
import '../../estilos/caja.css'
import { esEncargo, faltante, nombreDe, precioDe, tieneEncargo } from './venta.js'

const TIPOS = [['PORCENTAJE', '%'], ['PESOS', '$']]

function SelectorDeDescuento({ id, etiqueta, etiquetaTipo, tipo, valor, alCambiarTipo, alCambiarValor }) {
  return (
    <span className="caja-descuento">
      <select aria-label={etiquetaTipo} value={tipo} onChange={(e) => alCambiarTipo(e.target.value)} className="caja-entrada caja-descuento-tipo">
        {TIPOS.map(([v, texto]) => <option key={v} value={v}>{texto}</option>)}
      </select>
      <input
        type="text" inputMode="decimal" autoComplete="off" aria-label={etiqueta} id={id} value={valor}
        onChange={(e) => alCambiarValor(e.target.value)} className="caja-entrada caja-descuento-valor"
      />
    </span>
  )
}

function Producto({ linea, alCambiar }) {
  const nombre = nombreDe(linea)
  const falta = faltante(linea)
  const hoy = hoyISO()
  return (
    <span className="caja-producto">
      <strong>{nombre}</strong>
      <span className="sub-linea">
        {`${linea.producto.codigo}${linea.producto.presentacion ? ` · ${linea.producto.presentacion}` : ''}`}
      </span>
      {linea.encargo && <span className="insignia umbral">Encargo</span>}
      {falta > 0 && !linea.encargo && (
        <>
          <span className="caja-falta">Hay {Math.max(0, linea.producto.existencia)} en existencia; faltan {falta}.</span>
          <button type="button" className="boton secundario menudo" onClick={() => alCambiar(linea.clave, { encargo: true })}>
            Vender como encargo
          </button>
        </>
      )}
      {linea.encargo && (
        <button type="button" className="enlace" onClick={() => alCambiar(linea.clave, { encargo: false })}>
          Ya no es encargo
        </button>
      )}
      {esEncargo(linea) && (
        <label className="caja-fecha">
          <span>Entrega estimada</span>
          <input
            type="date" aria-label={`Entrega estimada de ${nombre}`} value={linea.entregaEstimada}
            min={hoy} max={sumarDias(hoy, 365)} className="caja-entrada"
            onChange={(e) => alCambiar(linea.clave, { entregaEstimada: e.target.value })}
          />
        </label>
      )}
    </span>
  )
}

/**
 * Las líneas de la venta. El precio es el del servidor y no se edita; lo que el
 * cajero cambia es la cantidad, el descuento (que pide autorización) y el encargo.
 * Los importes son la vista previa: el definitivo lo calcula el servidor al cobrar.
 */
export default function Carrito({
  lineas, vista, alCambiar, alQuitar, descuentoTotal, alCambiarDescuentoTotal, motivoEncargo, alCambiarMotivo,
}) {
  if (lineas.length === 0) {
    return (
      <div className="caja-carrito">
        <p className="caja-vacio">Escanea o busca un producto para empezar la venta.</p>
      </div>
    )
  }

  const filas = lineas.map((l) => ({ ...l, calculo: vista.lineas.find((x) => x.clave === l.clave) }))
  const columnas = [
    { clave: 'producto', titulo: 'Producto', principal: true, render: (l) => <Producto linea={l} alCambiar={alCambiar} /> },
    {
      clave: 'cantidad', titulo: 'Cantidad',
      render: (l) => (
        <input
          type="text" inputMode="numeric" autoComplete="off" aria-label={`Cantidad de ${nombreDe(l)}`}
          value={l.cantidad} className="caja-entrada caja-cantidad"
          onChange={(e) => alCambiar(l.clave, { cantidad: e.target.value })}
        />
      ),
    },
    { clave: 'precio', titulo: 'Precio (sin IVA)', numerica: true, render: (l) => PESOS.format(precioDe(l)) },
    {
      clave: 'descuento', titulo: 'Descuento',
      render: (l) => (
        <SelectorDeDescuento
          etiqueta={`Descuento de ${nombreDe(l)}`} etiquetaTipo={`Tipo de descuento de ${nombreDe(l)}`} tipo={l.descuentoTipo} valor={l.descuentoValor}
          alCambiarTipo={(descuentoTipo) => alCambiar(l.clave, { descuentoTipo })}
          alCambiarValor={(descuentoValor) => alCambiar(l.clave, { descuentoValor })}
        />
      ),
    },
    { clave: 'total', titulo: 'Total con IVA', numerica: true, render: (l) => (l.calculo ? PESOS.format(l.calculo.total) : '—') },
    {
      clave: 'quitar', titulo: 'Quitar',
      render: (l) => (
        <button type="button" className="boton secundario menudo" aria-label={`Quitar ${nombreDe(l)}`} onClick={() => alQuitar(l.clave)}>
          Quitar
        </button>
      ),
    },
  ]

  return (
    <div className="caja-carrito">
      <TablaOTarjetas etiqueta="Productos de la venta" columnas={columnas} filas={filas} claveFila={(l) => l.clave} />
      <div className="caja-pie-carrito">
        {tieneEncargo(lineas) && (
          <Campo etiqueta="Motivo del encargo">
            <input
              type="text" maxLength={200} autoComplete="off" value={motivoEncargo}
              className="caja-entrada" onChange={(e) => alCambiarMotivo(e.target.value)}
            />
          </Campo>
        )}
        <div className="caja-descuento-total">
          <label htmlFor="caja-descuento-total">Descuento de toda la compra</label>
          <SelectorDeDescuento
            id="caja-descuento-total" etiqueta="Descuento de toda la compra" etiquetaTipo="Tipo de descuento de toda la compra"
            tipo={descuentoTotal.tipo} valor={descuentoTotal.valor}
            alCambiarTipo={(tipo) => alCambiarDescuentoTotal({ ...descuentoTotal, tipo })}
            alCambiarValor={(valor) => alCambiarDescuentoTotal({ ...descuentoTotal, valor })}
          />
        </div>
      </div>
    </div>
  )
}

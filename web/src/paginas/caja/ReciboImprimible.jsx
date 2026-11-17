import { fechaDia } from '../../servicios/fechas.js'
import { PESOS } from '../../servicios/formato.js'
import '../../estilos/caja.css'

const fila = (rotulo, valor, clase = '') => (
  <p className={`recibo-fila ${clase}`.trim()}>{rotulo}<span className="cifra">{PESOS.format(valor)}</span></p>
)

/**
 * El recibo de la venta, sobrio y fácil de cambiar: el diseño definitivo lo hará el
 * usuario más adelante. Todos los importes son los que devolvió el servidor al
 * registrar la venta; la pantalla no recalcula nada aquí. Sale por la impresora de
 * tirilla de 80 mm (ver `@media print` en caja.css) y no sustituye a la factura
 * electrónica.
 */
export default function ReciboImprimible({ recibo }) {
  const { empresa, domicilio } = recibo
  const fecha = recibo.fecha ?? ''
  return (
    <article className="recibo" aria-label="Recibo de venta">
      <header className="recibo-cabecera">
        <p className="recibo-empresa">{empresa.razonSocial}</p>
        {empresa.nit && <p>NIT {empresa.nit}</p>}
      </header>

      <div className="recibo-datos">
        <p>Venta {recibo.consecutivo}</p>
        <p>{fechaDia(fecha.slice(0, 10))} {fecha.slice(11, 16)}</p>
        <p>Cajero: {recibo.cajero}</p>
        <p>Cliente: {recibo.cliente ? `${recibo.cliente.nombre}` : 'Consumidor final'}</p>
      </div>

      <ul className="recibo-lineas">
        {recibo.lineas.map((l, i) => (
          <li key={i}>
            <p className="recibo-linea-nombre">
              <span>{l.nombre}</span>
              <span className="cifra">{PESOS.format(l.total)}</span>
            </p>
            <p className="recibo-linea-detalle">
              {l.cantidad} × {PESOS.format(l.precioUnitario)}
              {l.descuento > 0 ? ` · descuento ${PESOS.format(l.descuento)}` : ''}
              {l.iva > 0 ? ` · IVA ${PESOS.format(l.iva)}` : ''}
            </p>
            {l.nuevo && <p className="recibo-marca">Producto nuevo</p>}
            {l.encargo && (
              <p className="recibo-marca">Encargo — entrega estimada: {fechaDia(l.entregaEstimada)}</p>
            )}
          </li>
        ))}
      </ul>

      <div className="recibo-totales">
        {fila('Subtotal', recibo.subtotal)}
        {recibo.descuento > 0 && fila('Descuento', recibo.descuento, 'recibo-descuento')}
        {fila('IVA', recibo.iva)}
        {fila('Total', recibo.total, 'recibo-total')}
      </div>

      {recibo.porCobrar ? (
        <p className="recibo-marca">Por cobrar en la entrega</p>
      ) : (
        <div className="recibo-pagos">
          {recibo.pagos.map((p, i) => (
            <div key={i}>
              {fila(p.medio, p.monto)}
              {p.recibido && p.recibido !== p.monto ? fila('Recibido', p.recibido) : null}
              {p.referencia && <p className="recibo-linea-detalle">Ref. {p.referencia}</p>}
            </div>
          ))}
          {recibo.cambio > 0 && fila('Cambio', recibo.cambio)}
        </div>
      )}

      {domicilio && (
        <p className="recibo-marca">
          Domicilio: {domicilio.direccion}, {domicilio.ciudad} · {domicilio.telefono} · Recibe: {domicilio.recibe}
        </p>
      )}
      <p className="recibo-pie">Este recibo no es una factura electrónica.</p>
    </article>
  )
}

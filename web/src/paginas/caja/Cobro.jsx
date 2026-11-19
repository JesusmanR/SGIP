import { useEffect, useState } from 'react'
import Campo from '../../componentes/comun/Campo.jsx'
import { PESOS } from '../../servicios/formato.js'
import '../../estilos/caja.css'
import { puedeCobrar } from './venta.js'

const ENTERO = /^\d{1,12}$/
let consecutivo = 0

const sumar = (pagos, filtro = () => true) => pagos.filter(filtro).reduce((a, p) => a + p.monto, 0)

/**
 * El total y el cobro. Los importes son la vista previa; al registrar la venta el
 * servidor calcula los definitivos y el recibo usa esos.
 *
 * «Cobrar» solo se habilita cuando lo pagado es igual al total (en contra entrega no
 * hay pagos: se registra para cobrar al entregar). Sin conexión no se cobra nada.
 */
export default function Cobro({
  vista, medios, saldo, pagos, alCambiarPagos, bloqueo, contraEntrega, sinConexion, enviando, congelado = false, esperando = false, alCobrar,
}) {
  const saldoUsado = sumar(pagos, (p) => p.tipo === 'SALDO_A_FAVOR')
  const saldoDisponible = Math.max(0, saldo - saldoUsado)
  const opciones = medios.filter((m) => m.tipo !== 'SALDO_A_FAVOR' && m.activo !== false)
  const medioDeSaldo = medios.find((m) => m.tipo === 'SALDO_A_FAVOR')
  if (saldoDisponible > 0) opciones.push({ id: medioDeSaldo?.id ?? null, nombre: 'Saldo a favor', tipo: 'SALDO_A_FAVOR' })

  const total = vista.total
  const pagado = sumar(pagos)
  const pendiente = Math.max(0, total - pagado)
  const cambio = pagos.reduce((a, p) => a + (p.tipo === 'EFECTIVO' && p.recibido ? Math.max(0, p.recibido - p.monto) : 0), 0)

  const [medioId, setMedioId] = useState(null)
  const [monto, setMonto] = useState(String(pendiente))
  const [recibido, setRecibido] = useState('')
  const [referencia, setReferencia] = useState('')
  const [error, setError] = useState(null)

  // Sugiere lo que falta por pagar: el caso normal es un solo pago por el total.
  useEffect(() => { setMonto(String(pendiente)) }, [pendiente])

  const medio = opciones.find((m) => String(m.id) === String(medioId)) ?? opciones[0]

  function agregar() {
    setError(null)
    if (!medio) return setError('No hay medios de pago activos. Pídele al administrador que los configure.')
    const valor = monto.trim()
    if (!ENTERO.test(valor) || Number(valor) <= 0) return setError('Escribe un monto en pesos enteros mayor que cero.')
    const importe = Number(valor)
    if (importe > pendiente) return setError('El monto no puede pasar de lo que falta por pagar.')
    const pago = { clave: `pago-${++consecutivo}`, medioId: medio.id, tipo: medio.tipo, nombre: medio.nombre, monto: importe }
    if (medio.tipo === 'EFECTIVO') {
      const entregado = recibido.trim() === '' ? importe : Number(recibido.trim())
      if (!ENTERO.test(String(entregado))) return setError('Escribe lo recibido en pesos enteros.')
      if (entregado < importe) return setError('Lo recibido no alcanza para cubrir el monto.')
      pago.recibido = entregado
    } else if (medio.tipo === 'TARJETA' || medio.tipo === 'TRANSFERENCIA') {
      if (!referencia.trim()) {
        return setError(medio.tipo === 'TARJETA' ? 'Escribe el número de aprobación.' : 'Escribe la referencia.')
      }
      pago.referencia = referencia.trim()
    } else if (medio.tipo === 'SALDO_A_FAVOR' && importe > saldoDisponible) {
      return setError(`El saldo a favor disponible es ${PESOS.format(saldoDisponible)}.`)
    }
    alCambiarPagos([...pagos, pago])
    setRecibido('')
    setReferencia('')
  }

  // Con la venta congelada (resultado ambiguo) solo se reintenta: «Cobrar» queda quieto.
  const habilitado = puedeCobrar({ bloqueo, vista, pagos, contraEntrega, sinConexion, enviando: enviando || congelado || esperando })
  const mensaje = bloqueo ?? (sinConexion ? 'Sin conexión: no se puede cobrar.' : '')
  const textoBoton = contraEntrega ? 'Registrar para cobrar en la entrega' : enviando ? 'Cobrando…' : 'Cobrar'

  return (
    <section className="caja-cobro" aria-label="Cobro">
      <div className="caja-total">
        <div className="caja-total-cifra">
          <p className="caja-total-rotulo">Total a cobrar</p>
          <p className="caja-total-valor cifra" data-testid="total-a-cobrar">{PESOS.format(total)}</p>
        </div>
        <button type="button" className="boton caja-cobrar" disabled={!habilitado} onClick={alCobrar}>{textoBoton}</button>
        {/* La región existe siempre, vacía si no hay nada que decir: así el lector anuncia el cambio. */}
        <p className="caja-total-mensaje" role="status">{mensaje}</p>
      </div>

      <dl className="caja-desglose">
        <div><dt>Subtotal</dt><dd className="cifra">{PESOS.format(vista.subtotal)}</dd></div>
        <div><dt>Descuento</dt><dd className="cifra">{PESOS.format(vista.descuento)}</dd></div>
        <div><dt>IVA</dt><dd className="cifra">{PESOS.format(vista.iva)}</dd></div>
      </dl>

      {contraEntrega ? (
        <p className="nota">El cliente pagará al recibir el pedido. La mercancía sale de la tienda al registrarlo.</p>
      ) : (
        <fieldset className="caja-congelable" disabled={congelado}>
          <form
            className="caja-pago" aria-label="Agregar pago" noValidate
            onSubmit={(e) => { e.preventDefault(); agregar() }}
          >
            <Campo etiqueta="Medio de pago">
              <select value={medio ? String(medio.id) : ''} onChange={(e) => { setMedioId(e.target.value); setError(null) }}>
                {opciones.map((m) => <option key={`${m.tipo}-${m.id}`} value={String(m.id)}>{m.nombre}</option>)}
              </select>
            </Campo>
            <Campo etiqueta="Monto">
              <input type="text" inputMode="numeric" autoComplete="off" value={monto} onChange={(e) => setMonto(e.target.value)} />
            </Campo>
            {medio?.tipo === 'EFECTIVO' && (
              <Campo etiqueta="Recibido" ayuda="Lo que entrega el cliente, para calcular el cambio.">
                <input type="text" inputMode="numeric" autoComplete="off" value={recibido} onChange={(e) => setRecibido(e.target.value)} />
              </Campo>
            )}
            {medio?.tipo === 'TARJETA' && (
              <Campo etiqueta="N.º de aprobación" ayuda="Del comprobante del datáfono.">
                <input type="text" autoComplete="off" maxLength={60} value={referencia} onChange={(e) => setReferencia(e.target.value)} />
              </Campo>
            )}
            {medio?.tipo === 'TRANSFERENCIA' && (
              <Campo etiqueta="Referencia" ayuda="Del comprobante de la transferencia.">
                <input type="text" autoComplete="off" maxLength={60} value={referencia} onChange={(e) => setReferencia(e.target.value)} />
              </Campo>
            )}
            {error && <p role="alert" className="caja-error-texto">{error}</p>}
            <button type="submit" className="boton secundario" disabled={pendiente === 0}>Agregar pago</button>
          </form>

          {pagos.length > 0 && (
            <ul className="caja-pagos" aria-label="Pagos de la venta">
              {pagos.map((p) => (
                <li key={p.clave}>
                  <span>
                    {p.nombre} <strong className="cifra">{PESOS.format(p.monto)}</strong>
                    {p.recibido && p.recibido !== p.monto ? ` (recibido ${PESOS.format(p.recibido)})` : ''}
                    {p.referencia ? ` · ${p.referencia}` : ''}
                  </span>
                  <button
                    type="button" className="boton secundario menudo" aria-label={`Quitar pago de ${p.nombre}`}
                    onClick={() => alCambiarPagos(pagos.filter((x) => x.clave !== p.clave))}
                  >
                    Quitar
                  </button>
                </li>
              ))}
            </ul>
          )}

          {total > 0 && (
            <p className="caja-falta-pagar">
              Falta por pagar<strong className="cifra">{PESOS.format(pendiente)}</strong>
            </p>
          )}
          <div role="status">
            {cambio > 0 && (
              <p className="caja-cambio">
                Cambio<strong className="cifra">{PESOS.format(cambio)}</strong>
              </p>
            )}
          </div>
        </fieldset>
      )}
    </section>
  )
}

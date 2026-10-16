import { useEffect, useState } from 'react'
import Barra from '../componentes/Barra.jsx'
import { api } from '../servicios/api.js'

const PESOS = new Intl.NumberFormat('es-CO', {
  style: 'currency', currency: 'COP', maximumFractionDigits: 0,
})

/** Pantalla P-03 · Catálogo de productos. */
export default function Catalogo() {
  const [productos, setProductos] = useState([])
  const [busqueda, setBusqueda] = useState('')
  const [error, setError] = useState(null)
  const [cargando, setCargando] = useState(true)

  useEffect(() => {
    let vigente = true
    // Se espera a que el usuario deje de escribir: una consulta por
    // pulsación castiga a la base de datos sin que nadie lo note en
    // desarrollo, y sí en una tienda con el inventario cargado.
    const temporizador = setTimeout(() => {
      setCargando(true)
      api.catalogo(busqueda)
        .then((filas) => { if (vigente) { setProductos(filas); setError(null) } })
        .catch((e) => { if (vigente) setError(e.message) })
        .finally(() => { if (vigente) setCargando(false) })
    }, 300)
    return () => { vigente = false; clearTimeout(temporizador) }
  }, [busqueda])

  return (
    <>
      <Barra />
      <main className="contenido">
        <h2>Catálogo</h2>

        <label className="campo">
          <span>Buscar por nombre, código o SKU</span>
          <input value={busqueda} onChange={(e) => setBusqueda(e.target.value)} />
        </label>

        {error && <div className="aviso error" role="alert">{error}</div>}

        <div className="tarjeta">
          <table>
            <thead>
              <tr>
                <th>Producto</th>
                <th>Categoría</th>
                <th className="numero">Costo</th>
                <th className="numero">Contado</th>
                <th className="numero">Crédito</th>
                <th className="numero">Exist.</th>
                <th>Estado</th>
              </tr>
            </thead>
            <tbody>
              {productos.map((p) => (
                <tr key={p.productoId}>
                  <td>
                    {p.nombre}{p.presentacion ? ` · ${p.presentacion}` : ''}
                    {/* El aviso va en la fila del producto: enterarse de que
                        la tributación no está configurada al facturar es
                        enterarse tarde. */}
                    {p.tributacionSinConfigurar === 1 && (
                      <> <span className="insignia critico">Sin impuesto configurado</span></>
                    )}
                    {/* El código va bajo el nombre, como en el diseño de la
                        P-03. El SKU solo aparece cuando difiere: por omisión
                        se copia del código y repetirlo sería ruido. */}
                    <span className="sub-linea">
                      {p.codigo}{p.sku && p.sku !== p.codigo ? ` · SKU ${p.sku}` : ''}
                    </span>
                  </td>
                  <td>{p.categoria}</td>
                  <td className="numero">{PESOS.format(p.costo)}</td>
                  <td className="numero">
                    {p.precioContado == null ? '—' : PESOS.format(p.precioContado)}
                  </td>
                  <td className="numero">
                    {p.precioCredito == null ? '—' : PESOS.format(p.precioCredito)}
                  </td>
                  <td className="numero">{p.existencia}</td>
                  <td>
                    <span className={`insignia ${(p.estadoStock || '').toLowerCase()}`}>
                      {p.estadoStock}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          {!cargando && productos.length === 0 && (
            <p style={{ color: 'var(--tinta-3)' }}>
              {busqueda ? 'Ningún producto coincide con la búsqueda.' : 'El catálogo está vacío.'}
            </p>
          )}
        </div>
      </main>
    </>
  )
}

import { useEffect, useState } from 'react'
import Barra from '../componentes/Barra.jsx'
import Galeria from '../componentes/Galeria.jsx'
import SubirImagen from '../componentes/SubirImagen.jsx'
import { api } from '../servicios/api.js'
import { subirImagenes } from '../servicios/subidas.js'
import { leerSesion } from '../servicios/sesion.js'

const PESOS = new Intl.NumberFormat('es-CO', {
  style: 'currency', currency: 'COP', maximumFractionDigits: 0,
})

/** Pantalla P-03 · Catálogo de productos. */
export default function Catalogo() {
  const [productos, setProductos] = useState([])
  const [busqueda, setBusqueda] = useState('')
  const [error, setError] = useState(null)
  const [cargando, setCargando] = useState(true)
  const esAdministrador = leerSesion()?.rol === 'ADMINISTRADOR'
  const [galeria, setGaleria] = useState(null)

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
      {galeria && (
        <Galeria
          productoId={galeria.id}
          nombre={galeria.nombre}
          puedeEditar={esAdministrador}
          onCerrar={() => setGaleria(null)}
          onCambio={(frescas) => {
            // La fila se entera de cuál es ahora la principal, no solo de
            // que algo cambió: es el identificador el que forma la
            // dirección de la miniatura.
            const principal = frescas.find((i) => i.principal)
            setProductos((filas) => filas.map((f) =>
              f.productoId === galeria.id
                ? { ...f, tieneImagen: frescas.length > 0 ? 1 : 0,
                  imagenPrincipalId: principal?.id ?? null }
                : f))
          }}
        />
      )}
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
                <th className="col-imagen"><span className="oculto">Imagen</span></th>
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
                  <td className="col-imagen">
                    <SubirImagen
                      // La dirección lleva el identificador de la imagen,
                      // no el del producto. Así cambiar de principal
                      // cambia la dirección, y la caché no puede servir
                      // la anterior aunque quiera.
                      src={p.imagenPrincipalId
                        ? `/api/v1/imagenes/${p.imagenPrincipalId}/miniatura`
                        : null}
                      alt={`Imagen de ${p.nombre}`}
                      puedeEditar={esAdministrador}
                      onFallo={setError}
                      multiple
                      ayuda="Subir imágenes. Puedes elegir varias; la primera será la principal."
                      onSubir={async (archivos) => {
                        setError(null)
                        const { creadas, problemas } = await subirImagenes(p.productoId, archivos)

                        // La primera de la tanda pasa a ser la principal:
                        // el recuadro que tocaste muestra la primera que
                        // elegiste, que es lo que cabe esperar. Para
                        // cambiarla después está la galería.
                        const primera = creadas[0]
                        if (primera && !primera.principal) {
                          await api.hacerPrincipal(primera.id)
                        }
                        if (primera) {
                          setProductos((filas) => filas.map((f) =>
                            f.productoId === p.productoId
                              ? { ...f, tieneImagen: 1, imagenPrincipalId: primera.id }
                              : f))
                        }
                        // Lo que no entró se dice archivo por archivo. Sin
                        // esto el rechazo del servidor no se veía y parecía
                        // que todo se había aceptado.
                        if (problemas.length > 0) setError(problemas.join(' · '))
                      }}
                    />
                  </td>
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
                      Cód. {p.codigo}
                      {p.sku && p.sku !== p.codigo ? ` · SKU ${p.sku}` : ''}
                      {/* Antes este enlace solo aparecía si el producto ya
                          tenía una imagen, y eso dejaba sin salida al caso
                          más común: un producto sin ninguna, al que se le
                          quieren subir varias de golpe. El recuadro de la
                          izquierda admite una sola. */}
                      {' · '}
                      <button
                        type="button"
                        className="enlace"
                        onClick={() => setGaleria({ id: p.productoId, nombre: p.nombre })}
                      >
                        {p.tieneImagen === 1 ? 'ver imágenes' : 'añadir imágenes'}
                      </button>
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

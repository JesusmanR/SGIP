import { useEffect, useState } from 'react'
import Barra from '../componentes/Barra.jsx'
import Categorias from '../componentes/Categorias.jsx'
import FormularioProducto from '../componentes/FormularioProducto.jsx'
import Galeria from '../componentes/Galeria.jsx'
import SubirImagen from '../componentes/SubirImagen.jsx'
import { api } from '../servicios/api.js'
import { subirImagenes } from '../servicios/subidas.js'
import { leerSesion } from '../servicios/sesion.js'

import { PESOS } from '../servicios/formato.js'

/**
 * Si el total con impuesto dice algo que el precio no diga ya.
 *
 * Un producto excluido tiene total igual a precio, y pintar «con IVA
 * 2.520.000» debajo de «2.520.000» no informa: ocupa sitio y obliga a
 * comparar dos cifras para descubrir que son la misma.
 */
function conImpuesto(precio, total) {
  if (precio == null || total == null) return false
  return Math.round(Number(total)) !== Math.round(Number(precio))
}

/**
 * Lo que hay que decir de un precio, además de su cifra.
 *
 * La vista expone cinco columnas alrededor de cada precio y ninguna se
 * veía en pantalla. Van como renglones pequeños bajo la cifra, y cada
 * uno aparece solo en su caso, de modo que la fila corriente sigue
 * midiendo un renglón:
 *
 *   · el total con impuesto, cuando difiere del precio;
 *   · que el precio está cerca del umbral del artículo 424, porque una
 *     subida de costo lo volvería gravado y eso cambia la cifra que ve
 *     el cliente;
 *   · con qué base se calculó, pero solo si fue SOBRE_PRECIO. El RF-32
 *     pide que la interfaz diga cuál de las dos aplicó, y decirlo en
 *     todas las filas repetiría la misma palabra doscientas veces: lo
 *     que se señala es la minoritaria, y la leyenda bajo la tabla dice
 *     qué significa su ausencia. Por eso la leyenda no es decorativa:
 *     sin ella la anotación no se puede interpretar.
 */
function anotaciones(precio, total, cerca, base) {
  const partes = []
  if (conImpuesto(precio, total)) partes.push(`con IVA ${PESOS.format(total)}`)
  if (cerca === 1) partes.push('cerca del umbral')
  if (base === 'SOBRE_PRECIO') partes.push('sobre el precio')
  return partes
}

/** Pantalla P-03 · Catálogo de productos. */

export default function Catalogo() {
  const [productos, setProductos] = useState([])
  const [busqueda, setBusqueda] = useState('')
  // Los tres filtros del RF-16. Vacío significa «sin filtrar», y el
  // cliente del API los descarta antes de armar la consulta.
  const [categoria, setCategoria] = useState('')
  const [proveedor, setProveedor] = useState('')
  const [estado, setEstado] = useState('')
  const [categorias, setCategorias] = useState([])
  const [proveedores, setProveedores] = useState([])
  const [error, setError] = useState(null)
  const [cargando, setCargando] = useState(true)
  const esAdministrador = leerSesion()?.rol === 'ADMINISTRADOR'
  const [galeria, setGaleria] = useState(null)
  const [editando, setEditando] = useState(null)
  const [panelCategorias, setPanelCategorias] = useState(false)

  // Las listas de los selectores se piden una sola vez: no cambian
  // mientras se teclea en el buscador.
  useEffect(() => {
    let vigente = true
    Promise.all([api.categorias(), api.proveedores()])
      .then(([cs, ps]) => { if (vigente) { setCategorias(cs); setProveedores(ps) } })
      .catch(() => {
        // Que falle el relleno de los selectores no debe tapar el
        // catálogo: se queda sin opciones y el resto funciona.
      })
    return () => { vigente = false }
  }, [])

  useEffect(() => {
    let vigente = true
    // Se espera a que el usuario deje de escribir: una consulta por
    // pulsación castiga a la base de datos sin que nadie lo note en
    // desarrollo, y sí en una tienda con el inventario cargado.
    const temporizador = setTimeout(() => {
      setCargando(true)
      api.catalogo({ busqueda, categoria, proveedor, estado })
        .then((filas) => { if (vigente) { setProductos(filas); setError(null) } })
        .catch((e) => { if (vigente) setError(e.message) })
        .finally(() => { if (vigente) setCargando(false) })
    }, 300)
    return () => { vigente = false; clearTimeout(temporizador) }
  }, [busqueda, categoria, proveedor, estado])

  const hayFiltro = Boolean(busqueda || categoria || proveedor || estado)

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
      {editando && (
        <FormularioProducto
          producto={editando}
          categorias={categorias}
          proveedores={proveedores}
          onCerrar={() => setEditando(null)}
          onGuardado={(fresco) => {
            // Se reemplaza la fila con lo que devolvió el servidor, no
            // con lo que se envió: cambiar el costo cambia el precio de
            // venta, y ese lo calcula la vista.
            setProductos((filas) => filas.map((f) =>
              f.productoId === fresco.productoId ? fresco : f))
            setEditando(null)
          }}
        />
      )}
      {panelCategorias && (
        <Categorias
          onCerrar={() => setPanelCategorias(false)}
          onCambio={(frescas) => {
            setCategorias(frescas)
            // Si la categoría por la que se estaba filtrando dejó de
            // existir, el filtro se suelta: mantenerlo dejaría la tabla
            // vacía sin explicar por qué.
            setCategoria((actual) =>
              actual && !frescas.some((c) => String(c.id) === String(actual)) ? '' : actual)
          }}
        />
      )}
      <main className="contenido">
        <div className="titulo-fila">
          <h2>Catálogo</h2>
          {esAdministrador && (
            <button className="boton secundario" onClick={() => setPanelCategorias(true)}>
              Categorías
            </button>
          )}
        </div>

        <div className="filtros">
          <label className="campo">
            <span>Buscar por nombre, código o SKU</span>
            <input value={busqueda} onChange={(e) => setBusqueda(e.target.value)} />
          </label>

          <label className="campo">
            <span>Categoría</span>
            <select value={categoria} onChange={(e) => setCategoria(e.target.value)}>
              <option value="">Todas</option>
              {categorias.map((c) => <option key={c.id} value={c.id}>{c.nombre}</option>)}
            </select>
          </label>

          <label className="campo">
            <span>Proveedor</span>
            <select value={proveedor} onChange={(e) => setProveedor(e.target.value)}>
              <option value="">Todos</option>
              {proveedores.map((p) => (
                <option key={p.id} value={p.id}>{p.razonSocial ?? p.nombre}</option>
              ))}
            </select>
          </label>

          <label className="campo">
            <span>Existencia</span>
            <select value={estado} onChange={(e) => setEstado(e.target.value)}>
              <option value="">Cualquiera</option>
              <option value="ACTIVO">Activo</option>
              <option value="CRITICO">Crítico</option>
              <option value="AGOTADO">Agotado</option>
            </select>
          </label>

          {hayFiltro && (
            <button
              className="enlace"
              onClick={() => { setBusqueda(''); setCategoria(''); setProveedor(''); setEstado('') }}
            >
              limpiar filtros
            </button>
          )}
        </div>

        {error && <div className="aviso error" role="alert">{error}</div>}

        <div className="tarjeta">
          {/* La tabla tiene nueve columnas y en un teléfono no cabe. Sin
              este contenedor no se recortaba: se derramaba fuera de la
              tarjeta y arrastraba la página entera hacia la derecha, de
              modo que el precio quedaba sobre el fondo y no sobre la
              tabla. Con él, la tabla se desplaza sola y la página no. */}
          <div className="tabla-ancha">
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
                {esAdministrador && <th><span className="oculto">Acciones</span></th>}
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
                  {/* Bajo cada precio va su total con impuesto, y solo
                      cuando difiere: si el producto está excluido, el
                      total es el precio y repetirlo sería ruido. El
                      RF-33 pide los dos precios; la V20 añadió el total
                      a crédito, que faltaba. */}
                  <td className="numero">
                    {p.precioContado == null ? '—' : PESOS.format(p.precioContado)}
                    {anotaciones(p.precioContado, p.totalContado,
                      p.cercaUmbral, p.baseContado).map((t) => (
                        <span className="sub-linea" key={t}>{t}</span>
                      ))}
                  </td>
                  <td className="numero">
                    {p.precioCredito == null ? '—' : PESOS.format(p.precioCredito)}
                    {anotaciones(p.precioCredito, p.totalCredito,
                      p.cercaUmbralCredito, p.baseCredito).map((t) => (
                        <span className="sub-linea" key={t}>{t}</span>
                      ))}
                    {/* El caso que el margen provoca por sí solo:
                        excluido de contado y gravado a crédito, porque
                        el umbral se mide contra el precio de venta. Un
                        vendedor que ofrezca el crédito mirando solo el
                        precio se queda corto por el importe del
                        impuesto. */}
                    {p.cruzaUmbralEnCredito === 1 && (
                      <span className="insignia umbral" title={
                        'Excluido de IVA al contado y gravado a crédito: el margen '
                        + 'a crédito cruza el umbral del Art. 424.'}>
                        cruza el umbral
                      </span>
                    )}
                  </td>
                  <td className="numero">{p.existencia}</td>
                  <td>
                    <span className={`insignia ${(p.estadoStock || '').toLowerCase()}`}>
                      {p.estadoStock}
                    </span>
                  </td>
                  {esAdministrador && (
                    <td className="acciones">
                      <button className="enlace" onClick={() => setEditando(p)}>editar</button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
          </div>

          {/* Sin esta línea, la anotación «margen sobre el precio» no se
              puede interpretar: quien no la vea en una fila no sabe si
              es que allí se aplicó la otra base o que nadie la anotó. */}
          {!cargando && productos.length > 0 && (
            <p className="leyenda">
              Los precios sin anotación se calculan como recargo sobre el costo.
              Los marcados «sobre el precio» aplican el margen sobre el precio de
              venta, de modo que un 20 % da 1.250 y no 1.200 sobre un costo de
              1.000.
            </p>
          )}

          {!cargando && productos.length === 0 && (
            <p style={{ color: 'var(--tinta-3)' }}>
              {hayFiltro
                ? 'Ningún producto coincide con los filtros.'
                : 'El catálogo está vacío.'}
            </p>
          )}
        </div>
      </main>
    </>
  )
}

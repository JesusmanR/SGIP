import { useEffect, useState } from 'react'
import Categorias from '../componentes/Categorias.jsx'
import EncabezadoPagina from '../componentes/comun/EncabezadoPagina.jsx'
import EstadoCarga from '../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../componentes/comun/EstadoError.jsx'
import EstadoVacio from '../componentes/comun/EstadoVacio.jsx'
import TablaOTarjetas from '../componentes/comun/TablaOTarjetas.jsx'
import FormularioProducto from '../componentes/FormularioProducto.jsx'
import Galeria from '../componentes/Galeria.jsx'
import SubirImagen from '../componentes/SubirImagen.jsx'
import { api } from '../servicios/api.js'
import { subirImagenes } from '../servicios/subidas.js'
import { esAdministrador } from '../servicios/sesion.js'

import { PESOS, costoEnPesos, porcentaje } from '../servicios/formato.js'
import '../estilos/catalogo.css'

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
 *     sin ella la anotación no se puede interpretar;
 *   · que el precio está escrito a mano y no sale de un porcentaje. Sin
 *     esto, la leyenda diría de él que se calcula sobre el costo. La vista
 *     lo deja sin base, y un producto sin regla no tiene ni base ni precio.
 */
function anotaciones(precio, total, cerca, base) {
  const partes = []
  if (conImpuesto(precio, total)) partes.push(`con IVA ${PESOS.format(total)}`)
  if (cerca === 1) partes.push('cerca del umbral')
  if (base === 'SOBRE_PRECIO') partes.push('sobre el precio')
  if (precio != null && base == null) partes.push('precio escrito')
  return partes
}

/**
 * El estado de existencia con palabras. El servidor manda un código y el
 * código no es lo que se le dice a quien vende; la insignia lleva siempre
 * texto, porque los colores de estado se confunden en escala de grises
 * (ADR-0009).
 */
const ESTADOS = { ACTIVO: 'Disponible', CRITICO: 'Stock bajo', AGOTADO: 'Agotado' }

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
  // `error` es el de la lista y la reemplaza; `aviso` es el de una acción
  // (subir una imagen) y se dice sobre la lista, que sigue ahí.
  const [error, setError] = useState(null)
  const [aviso, setAviso] = useState(null)
  const [cargando, setCargando] = useState(true)
  const [intento, setIntento] = useState(0)
  const esAdmin = esAdministrador()
  const [galeria, setGaleria] = useState(null)
  const [editando, setEditando] = useState(null)
  const [creando, setCreando] = useState(false)
  const [panelCategorias, setPanelCategorias] = useState(false)

  // Las listas de los selectores se piden una sola vez: no cambian
  // mientras se teclea en el buscador.
  //
  // Van en peticiones separadas: los proveedores solo los puede pedir el
  // administrador, y si compartieran promesa el 403 de quien vende se
  // llevaría por delante también las categorías.
  useEffect(() => {
    let vigente = true
    // Que falle el relleno de un selector no debe tapar el catálogo: se
    // queda sin opciones y el resto funciona.
    api.categorias()
      .then((cs) => { if (vigente) setCategorias(cs) })
      .catch(() => {})
    if (esAdmin) {
      api.proveedores()
        .then((ps) => { if (vigente) setProveedores(ps) })
        .catch(() => {})
    }
    return () => { vigente = false }
  }, [esAdmin])

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
  }, [busqueda, categoria, proveedor, estado, intento])

  const hayFiltro = Boolean(busqueda || categoria || proveedor || estado)
  const limpiarFiltros = () => {
    setBusqueda(''); setCategoria(''); setProveedor(''); setEstado('')
  }
  const reintentar = () => { setError(null); setCargando(true); setIntento((n) => n + 1) }

  /** Sube las imágenes elegidas en el recuadro de una fila y deja la primera como principal. */
  async function subirDeLaFila(p, archivos) {
    setAviso(null)
    const { creadas, problemas } = await subirImagenes(p.productoId, archivos)

    // La primera de la tanda pasa a ser la principal: el recuadro que tocaste
    // muestra la primera que elegiste, que es lo que cabe esperar. Para
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
    // Lo que no entró se dice archivo por archivo. Sin esto el rechazo del
    // servidor no se veía y parecía que todo se había aceptado.
    if (problemas.length > 0) setAviso(problemas.join(' · '))
  }

  /**
   * Las columnas. Costo, margen y acciones son del administrador: a quien vende
   * el servidor ni siquiera le manda el costo (`ProductoParaVenta`), y aquí
   * tampoco se pinta nada que lo insinúe.
   *
   * Cada celda va envuelta en un solo bloque: en celular la fila pasa a tarjeta
   * y el rótulo de la celda se pone en una columna aparte del contenido.
   */
  const columnas = [
    {
      clave: 'imagen',
      titulo: 'Imagen',
      render: (p) => (
        <div className="celda">
          <SubirImagen
            // La dirección lleva el identificador de la imagen, no el del
            // producto. Así cambiar de principal cambia la dirección, y la
            // caché no puede servir la anterior aunque quiera.
            src={p.imagenPrincipalId ? `/api/v1/imagenes/${p.imagenPrincipalId}/miniatura` : null}
            alt={`Imagen de ${p.nombre}`}
            puedeEditar={esAdmin}
            onFallo={setAviso}
            multiple
            ayuda={`Subir imágenes de ${p.nombre}. Puedes elegir varias; la primera será la principal.`}
            onSubir={(archivos) => subirDeLaFila(p, archivos)}
          />
        </div>
      ),
    },
    {
      clave: 'producto',
      titulo: 'Producto',
      principal: true,
      render: (p) => (
        <div className="celda celda-producto">
          <span className="producto-nombre">
            {p.nombre}{p.presentacion ? ` · ${p.presentacion}` : ''}
          </span>
          {/* El aviso va en la fila del producto: enterarse de que falta la
              regla de impuesto al facturar es enterarse tarde. Solo lo recibe
              la empresa que se declaró responsable de IVA. Informa y no
              bloquea: sin regla el producto se vende al precio (ADR-0024).
              Es del administrador, que es quien puede crear la regla. */}
          {esAdmin && p.tributacionSinConfigurar === 1 && (
            <span><span className="insignia critico">Impuestos sin configurar</span></span>
          )}
          {/* El código va bajo el nombre. El SKU solo aparece cuando difiere:
              por omisión se copia del código y repetirlo sería ruido. */}
          <span className="sub-linea">
            Cód. {p.codigo}
            {p.sku && p.sku !== p.codigo ? ` · SKU ${p.sku}` : ''}
            {/* Quien vende no puede añadir imágenes: sin ninguna, no se le
                ofrece un botón que no lleva a nada que pueda hacer. */}
            {(esAdmin || p.tieneImagen === 1) && (
              <>
                {' · '}
                <button
                  type="button"
                  className="enlace"
                  aria-label={`${p.tieneImagen === 1 ? 'Ver' : 'Añadir'} imágenes de ${p.nombre}`}
                  onClick={() => setGaleria({ id: p.productoId, nombre: p.nombre })}
                >
                  {p.tieneImagen === 1 ? 'ver imágenes' : 'añadir imágenes'}
                </button>
              </>
            )}
          </span>
        </div>
      ),
    },
    { clave: 'categoria', titulo: 'Categoría', render: (p) => <div className="celda">{p.categoria}</div> },
    ...(esAdmin ? [
      {
        clave: 'costo',
        titulo: 'Costo',
        numerica: true,
        render: (p) => <div className="celda">{costoEnPesos(p.costo)}</div>,
      },
      {
        // Sin precio no hay margen de cero: hay un precio que falta, y eso es
        // lo que se dice.
        clave: 'margen',
        titulo: 'Ganancia',
        numerica: true,
        render: (p) => (
          <div className="celda">
            {p.precioContado == null
              ? 'Falta el precio de venta'
              : porcentaje(p.margenContado)}
            {p.margenCredito != null
              && Number(p.margenCredito) !== Number(p.margenContado) && (
              <span className="sub-linea">
                a crédito {porcentaje(p.margenCredito)}
              </span>
            )}
          </div>
        ),
      },
    ] : []),
    {
      // Bajo cada precio va su total con impuesto, y solo cuando difiere: si el
      // producto está excluido, el total es el precio y repetirlo sería ruido.
      clave: 'contado',
      titulo: 'Contado',
      numerica: true,
      render: (p) => (
        <div className="celda">
          {p.precioContado == null ? '—' : PESOS.format(p.precioContado)}
          {anotaciones(p.precioContado, p.totalContado, p.cercaUmbral, p.baseContado).map((t) => (
            <span className="sub-linea" key={t}>{t}</span>
          ))}
        </div>
      ),
    },
    {
      clave: 'credito',
      titulo: 'Crédito',
      numerica: true,
      render: (p) => (
        <div className="celda">
          {p.precioCredito == null ? '—' : PESOS.format(p.precioCredito)}
          {anotaciones(p.precioCredito, p.totalCredito, p.cercaUmbralCredito, p.baseCredito).map((t) => (
            <span className="sub-linea" key={t}>{t}</span>
          ))}
          {/* El caso que el margen provoca por sí solo: excluido de contado y
              gravado a crédito, porque el umbral se mide contra el precio de
              venta. Quien ofrezca el crédito mirando solo el precio se queda
              corto por el importe del impuesto. */}
          {p.cruzaUmbralEnCredito === 1 && (
            <span
              className="insignia umbral"
              title={'Excluido de IVA al contado y gravado a crédito: el precio '
                + 'a crédito cruza el umbral del Art. 424.'}
            >
              cruza el umbral
            </span>
          )}
        </div>
      ),
    },
    {
      clave: 'existencia',
      titulo: 'Existencias',
      numerica: true,
      render: (p) => (
        <div className="celda">
          {p.existencia}
          {p.existenciaMinima != null && (
            <span className="sub-linea">mín. {p.existenciaMinima}</span>
          )}
        </div>
      ),
    },
    {
      clave: 'estado',
      titulo: 'Estado',
      render: (p) => (
        <div className="celda">
          <span className={`insignia ${(p.estadoStock || '').toLowerCase()}`}>
            {ESTADOS[p.estadoStock] ?? p.estadoStock}
          </span>
        </div>
      ),
    },
    ...(esAdmin ? [{
      clave: 'acciones',
      titulo: 'Acciones',
      render: (p) => (
        <div className="celda">
          <button
            type="button"
            className="enlace"
            aria-label={`Editar ${p.nombre}`}
            onClick={() => setEditando(p)}
          >
            editar
          </button>
        </div>
      ),
    }] : []),
  ]

  return (
    <>
      {galeria && (
        <Galeria
          productoId={galeria.id}
          nombre={galeria.nombre}
          puedeEditar={esAdmin}
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
          onActualizado={(actual) => {
            // Se trajeron los datos vigentes tras un conflicto: la fila los
            // muestra aunque luego se cancele, y la próxima edición parte de
            // la versión nueva en lugar de dar otro conflicto.
            setProductos((filas) => filas.map((f) =>
              f.productoId === actual.productoId ? actual : f))
          }}
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
      {creando && (
        <FormularioProducto
          modo="crear"
          categorias={categorias}
          proveedores={proveedores}
          onCerrar={() => setCreando(false)}
          onGuardado={(nuevo) => {
            // Arriba y sin volver a consultar, aunque los filtros lo dejarían
            // fuera: quien acaba de crearlo tiene que ver que quedó, y con
            // qué precio. La próxima consulta lo pone en su sitio.
            setProductos((filas) => [nuevo, ...filas])
            setCreando(false)
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
      <div className="contenido seccion-catalogo">
        <EncabezadoPagina
          titulo="Catálogo"
          descripcion="Consulta productos, precios y existencias."
          acciones={esAdmin && (
            <>
              <button type="button" className="boton secundario" onClick={() => setPanelCategorias(true)}>
                Categorías
              </button>
              <button type="button" className="boton" onClick={() => setCreando(true)}>
                Nuevo producto
              </button>
            </>
          )}
        />

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

          {/* El proveedor, como el costo, es del administrador: la API
              rechaza el filtro a quien vende, así que ni se ofrece. */}
          {esAdmin && (
            <label className="campo">
              <span>Proveedor</span>
              <select value={proveedor} onChange={(e) => setProveedor(e.target.value)}>
                <option value="">Todos</option>
                {proveedores.map((p) => (
                  <option key={p.id} value={p.id}>{p.razonSocial ?? p.nombre}</option>
                ))}
              </select>
            </label>
          )}

          <label className="campo">
            <span>Existencia</span>
            <select value={estado} onChange={(e) => setEstado(e.target.value)}>
              <option value="">Cualquiera</option>
              {Object.entries(ESTADOS).map(([valor, texto]) => (
                <option key={valor} value={valor}>{texto}</option>
              ))}
            </select>
          </label>

          {hayFiltro && (
            <button type="button" className="enlace" onClick={limpiarFiltros}>
              limpiar filtros
            </button>
          )}
        </div>

        {aviso && <div className="aviso error" role="alert">{aviso}</div>}

        {error && <EstadoError error={error} alReintentar={reintentar} />}

        {!error && cargando && productos.length === 0 && (
          <EstadoCarga mensaje="Cargando el catálogo…" />
        )}

        {!error && !cargando && productos.length === 0 && (
          hayFiltro
            ? (
              <EstadoVacio
                titulo="Ningún producto coincide con los filtros."
                detalle="Prueba con otra búsqueda o quita los filtros."
                accion={(
                  <button type="button" className="boton secundario" onClick={limpiarFiltros}>
                    Limpiar filtros
                  </button>
                )}
              />
            )
            : <EstadoVacio titulo="El catálogo está vacío." />
        )}

        {!error && productos.length > 0 && (
          <>
            <TablaOTarjetas
              etiqueta="Productos del catálogo"
              filas={productos}
              claveFila={(p) => p.productoId}
              columnas={columnas}
            />

            {/* Sin esta línea, la anotación «sobre el precio» no se puede
                interpretar: quien no la vea en una fila no sabe si es que
                allí se aplicó la otra base o que nadie la anotó. */}
            {!cargando && (
              <p className="leyenda">
                {esAdmin ? (
                  <>
                    Los precios sin anotación se calculan como recargo sobre el costo.
                    Los marcados «sobre el precio» aplican la ganancia sobre el precio de
                    venta, de modo que un 20 % da 1.250 y no 1.200 sobre un costo de
                    1.000. Los marcados «precio escrito» se venden a esa cifra aunque
                    cambie el costo.
                  </>
                ) : (
                  // Quien vende no ve el costo ni cómo se calculó: se le dice
                  // qué significa cada anotación sin nombrar lo que no ve.
                  <>
                    «Sobre el precio» indica que la ganancia se aplicó sobre el precio de
                    venta. «Precio escrito» indica que el producto se vende a esa cifra
                    fija. «Cerca del umbral» avisa de que el impuesto podría cambiar.
                  </>
                )}
              </p>
            )}

            {/* La insignia sola no dice qué pasa con el producto. Esto lo
                dice, y solo cuando hay alguno marcado. */}
            {!cargando && esAdmin
              && productos.some((p) => p.tributacionSinConfigurar === 1) && (
              <p className="leyenda">
                «Impuestos sin configurar» marca un producto sin regla de IVA en una
                empresa responsable de IVA. Se vende al precio, sin impuesto, hasta que
                se cree la regla.
              </p>
            )}
          </>
        )}
      </div>
    </>
  )
}

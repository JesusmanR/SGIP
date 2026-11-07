import { useEffect, useId, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import Aviso from '../../componentes/comun/Aviso.jsx'
import EncabezadoPagina from '../../componentes/comun/EncabezadoPagina.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import EstadoVacio from '../../componentes/comun/EstadoVacio.jsx'
import TablaOTarjetas from '../../componentes/comun/TablaOTarjetas.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { PRODUCTOS_POR_BUSQUEDA, buscarProductos, crearOrden } from '../../servicios/api/compras.js'
import { listarProveedores } from '../../servicios/api/proveedores.js'
import Campo from '../../componentes/comun/Campo.jsx'
import { pesos } from './EstadoOrden.jsx'

const CANTIDAD = /^\d+$/
// Los decimales van con coma: un punto se confundiría con el separador de miles.
const COSTO = /^\d{1,12}(,\d{1,2})?$/

const MOTIVO_CANTIDAD = 'Escribe una cantidad entera de 1 o más.'
const MOTIVO_COSTO = 'Escribe un costo mayor que cero, sin puntos de miles y con hasta 2 decimales con coma (por ejemplo 1500,50).'

const costoComoNumero = (texto) => Number(texto.trim().replace(',', '.'))

/** Los motivos de una línea; vacío si está bien. */
function motivosDeLinea(linea) {
  const m = {}
  const cantidad = linea.cantidad.trim()
  if (!CANTIDAD.test(cantidad) || Number(cantidad) < 1 || Number(cantidad) > 2147483647) m.cantidad = MOTIVO_CANTIDAD
  const costo = linea.costo.trim()
  if (!COSTO.test(costo) || costoComoNumero(costo) < 0.01) m.costo = MOTIVO_COSTO
  return m
}

/**
 * Suma de vista previa, en centavos enteros para que 0,1 + 0,2 no se vea
 * como 0,30000000000000004. Es solo para revisar al escribir: el total
 * oficial lo calcula el servidor al guardar.
 */
function sumaDeVistaPrevia(lineas) {
  let centavos = 0
  for (const l of lineas) {
    if (Object.keys(motivosDeLinea(l)).length === 0) {
      centavos += Number(l.cantidad.trim()) * Math.round(costoComoNumero(l.costo) * 100)
    }
  }
  return centavos / 100
}

function subtotalDeVistaPrevia(linea) {
  if (Object.keys(motivosDeLinea(linea)).length > 0) return '—'
  return pesos(Number(linea.cantidad.trim()) * Math.round(costoComoNumero(linea.costo) * 100) / 100)
}

/** Búsqueda de productos en el servidor: solo al pulsar o dar Enter, y se guarda lo mínimo. */
function BuscadorDeProductos({ agregados, alAgregar, motivo, referencia }) {
  const [texto, setTexto] = useState('')
  const [resultados, setResultados] = useState(null)
  const [buscando, setBuscando] = useState(false)
  const [fallo, setFallo] = useState(null)

  async function buscar() {
    const consulta = texto.trim()
    if (!consulta || buscando) return
    setBuscando(true)
    setFallo(null)
    try {
      const encontrados = await buscarProductos(consulta)
      setResultados(encontrados.map((p) => ({
        productoId: p.productoId, codigo: p.codigo, nombre: p.nombre, costo: p.costo,
      })))
    } catch (error) {
      setResultados(null)
      setFallo(error.message || 'No se pudo buscar. Inténtalo otra vez.')
    } finally {
      setBuscando(false)
    }
  }

  return (
    <div className="busqueda-producto">
      <Campo etiqueta="Buscar producto por nombre o código" motivo={motivo}>
        <input
          ref={referencia}
          type="search"
          value={texto}
          onChange={(e) => setTexto(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); buscar() } }}
        />
      </Campo>
      <button type="button" className="boton secundario" onClick={buscar} disabled={buscando}>
        {buscando ? 'Buscando…' : 'Buscar'}
      </button>
      {fallo && <p className="peligro-texto" role="alert">{fallo}</p>}
      {resultados && resultados.length === 0 && <p className="leyenda">No encontramos productos con ese texto.</p>}
      {resultados && resultados.length >= PRODUCTOS_POR_BUSQUEDA && (
        <p className="leyenda">Puede haber más productos con ese texto. Afina la búsqueda para verlos.</p>
      )}
      {resultados && resultados.length > 0 && (
        <ul className="resultados-producto">
          {resultados.map((p) => {
            const yaEsta = agregados.has(p.productoId)
            return (
              <li key={p.productoId}>
                <span>
                  <strong>{p.nombre}</strong>
                  <span className="sub-linea">
                    {p.codigo}{p.costo != null && ` · Costo actual ${pesos(p.costo)}`}
                  </span>
                </span>
                {yaEsta && <span className="leyenda">Ya está en la orden</span>}
                <button
                  type="button" className="boton secundario" aria-label={`Agregar ${p.nombre}`}
                  disabled={yaEsta} onClick={() => alAgregar(p)}
                >
                  Agregar
                </button>
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}

function Formulario({ proveedores }) {
  const navegar = useNavigate()
  const formulario = useRef(null)
  const buscador = useRef(null)
  const aviso = useRef(null)
  const [proveedorId, setProveedorId] = useState('')
  const [consecutivo, setConsecutivo] = useState('')
  const [observacion, setObservacion] = useState('')
  const [lineas, setLineas] = useState([])
  const [errores, setErrores] = useState({})
  const [errorServidor, setErrorServidor] = useState(null)
  const [enviando, setEnviando] = useState(false)
  const [intentos, setIntentos] = useState(0)

  useEffect(() => {
    if (intentos > 0) formulario.current?.querySelector('[aria-invalid="true"]')?.focus()
  }, [intentos])
  useEffect(() => { if (errorServidor) aviso.current?.focus() }, [errorServidor])

  const agregados = new Set(lineas.map((l) => l.productoId))

  const agregar = (p) => {
    setLineas((previas) => [...previas, { ...p, cantidad: '', costo: '' }])
    setErrores((previos) => ({ ...previos, lineas: undefined }))
  }
  const quitar = (productoId) => setLineas((previas) => previas.filter((l) => l.productoId !== productoId))
  const cambiarLinea = (productoId, clave) => (evento) => {
    setLineas((previas) => previas.map((l) => (l.productoId === productoId ? { ...l, [clave]: evento.target.value } : l)))
    setErrores((previos) => ({ ...previos, porLinea: { ...previos.porLinea, [productoId]: undefined } }))
  }

  async function enviar(evento) {
    evento.preventDefault()
    if (enviando) return
    const encontrados = {}
    if (!proveedorId) encontrados.proveedor = 'Elige un proveedor.'
    const numero = consecutivo.trim()
    if (!numero) encontrados.consecutivo = 'El número de la orden es obligatorio.'
    else if (numero.length > 20) encontrados.consecutivo = 'El número de la orden no puede pasar de 20 caracteres.'
    if (observacion.trim().length > 200) encontrados.observacion = 'La observación no puede pasar de 200 caracteres.'
    if (lineas.length === 0) encontrados.lineas = 'Agrega al menos un producto a la orden.'
    const porLinea = {}
    for (const l of lineas) {
      const m = motivosDeLinea(l)
      if (Object.keys(m).length > 0) porLinea[l.productoId] = m
    }
    if (Object.keys(porLinea).length > 0) encontrados.porLinea = porLinea

    setErrorServidor(null)
    setErrores(encontrados)
    if (Object.keys(encontrados).length > 0) { setIntentos((n) => n + 1); return }

    setEnviando(true)
    try {
      const creada = await crearOrden({
        proveedorId: Number(proveedorId),
        consecutivo: numero,
        observacion: observacion.trim() === '' ? null : observacion.trim(),
        items: lineas.map((l) => ({
          productoId: l.productoId,
          cantidad: Number(l.cantidad.trim()),
          costoUnitario: costoComoNumero(l.costo),
        })),
      })
      navegar(`/compras/${creada.ordenId}`, { state: { creada: true } })
    } catch (error) {
      setEnviando(false)
      if (error.estado === 409 && /consecutivo/i.test(error.message)) {
        setErrores({ consecutivo: error.message })
        setIntentos((n) => n + 1)
      } else {
        setErrorServidor(error.message || 'No se pudo guardar la orden. Inténtalo otra vez.')
      }
    }
  }

  // La búsqueda y las líneas viven en el mismo `form`, pero el Enter del
  // buscador no lo envía: lo atrapa el propio buscador.
  const columnas = [
    {
      clave: 'nombre', titulo: 'Producto', principal: true,
      render: (l) => <>{l.nombre}<span className="sub-linea">{l.codigo}</span></>,
    },
    {
      clave: 'cantidad', titulo: 'Cantidad',
      render: (l) => (
        <CeldaDeLinea
          etiqueta={`Cantidad de ${l.nombre}`} valor={l.cantidad} motivo={errores.porLinea?.[l.productoId]?.cantidad}
          alCambiar={cambiarLinea(l.productoId, 'cantidad')}
        />
      ),
    },
    {
      clave: 'costo', titulo: 'Costo unitario',
      render: (l) => (
        <CeldaDeLinea
          etiqueta={`Costo unitario de ${l.nombre}`} valor={l.costo} motivo={errores.porLinea?.[l.productoId]?.costo}
          alCambiar={cambiarLinea(l.productoId, 'costo')}
        />
      ),
    },
    { clave: 'subtotal', titulo: 'Subtotal (vista previa)', numerica: true, render: subtotalDeVistaPrevia },
    {
      clave: 'quitar', titulo: 'Acción',
      render: (l) => (
        <button type="button" className="boton secundario menudo" aria-label={`Quitar ${l.nombre}`} onClick={() => quitar(l.productoId)}>
          Quitar
        </button>
      ),
    },
  ]

  return (
    <form ref={formulario} className="tarjeta" onSubmit={enviar} noValidate>
      {errorServidor && <div ref={aviso} tabIndex={-1}><Aviso tipo="peligro">{errorServidor}</Aviso></div>}
      <div className="rejilla">
        <Campo etiqueta="Proveedor" motivo={errores.proveedor}>
          <select value={proveedorId} onChange={(e) => { setProveedorId(e.target.value); setErrores((p) => ({ ...p, proveedor: undefined })) }}>
            <option value="">Elige un proveedor</option>
            {proveedores.map((p) => <option key={p.id} value={p.id}>{p.razonSocial}</option>)}
          </select>
        </Campo>
        <Campo
          etiqueta="Número de la orden" motivo={errores.consecutivo}
          ayuda="Es el número de tu orden o el que te dio el proveedor. No se genera solo."
        >
          <input type="text" value={consecutivo} onChange={(e) => { setConsecutivo(e.target.value); setErrores((p) => ({ ...p, consecutivo: undefined })) }} />
        </Campo>
      </div>
      <Campo etiqueta="Observación (opcional)" motivo={errores.observacion}>
        <input type="text" value={observacion} onChange={(e) => { setObservacion(e.target.value); setErrores((p) => ({ ...p, observacion: undefined })) }} />
      </Campo>

      <h2 className="subtitulo-seccion">Productos de la orden</h2>
      <BuscadorDeProductos agregados={agregados} alAgregar={agregar} motivo={errores.lineas} referencia={buscador} />
      {lineas.length > 0 && (
        <>
          <TablaOTarjetas etiqueta="Productos de la orden" filas={lineas} claveFila={(l) => l.productoId} columnas={columnas} />
          <p className="total-vista">
            <span>Total de la vista previa</span> <strong>{pesos(sumaDeVistaPrevia(lineas))}</strong>
          </p>
          <p className="leyenda">Es una suma para que revises. El total oficial lo calcula el sistema al guardar.</p>
        </>
      )}
      <p className="leyenda">Guardar la orden no cambia el inventario: eso ocurre al registrar la recepción.</p>

      <div className="acciones-pie">
        <Link className="boton secundario" to="/compras">Cancelar</Link>
        <button type="submit" className="boton" disabled={enviando}>
          {enviando ? 'Guardando…' : 'Guardar orden'}
        </button>
      </div>
    </form>
  )
}

/** Un campo dentro de una fila: su motivo va debajo y enlazado al campo. */
function CeldaDeLinea({ etiqueta, valor, motivo, alCambiar }) {
  // useId y no el nombre del producto: dos productos pueden llamarse igual.
  const idMotivo = `${useId()}-motivo`
  return (
    <>
      <input
        type="text" inputMode="decimal" aria-label={etiqueta} value={valor} onChange={alCambiar}
        className="entrada-celda"
        aria-invalid={motivo ? 'true' : undefined} aria-describedby={motivo ? idMotivo : undefined}
      />
      {motivo && <small id={idMotivo} className="campo-mensaje error">{motivo}</small>}
    </>
  )
}

export default function NuevaOrden() {
  const { datos, cargando, error, sinPermiso, recargar } = useDatos(() => listarProveedores(), [])

  let cuerpo
  if (sinPermiso) cuerpo = <EstadoSinPermiso />
  else if (error) cuerpo = <EstadoError error={error} alReintentar={recargar} />
  else if (cargando && !datos) cuerpo = <EstadoCarga mensaje="Cargando proveedores…" />
  else if (datos.length === 0) {
    cuerpo = (
      <EstadoVacio
        titulo="Primero crea un proveedor"
        detalle="Una orden de compra se le hace a un proveedor."
        accion={<Link className="boton" to="/proveedores/nuevo">Crear un proveedor</Link>}
      />
    )
  } else cuerpo = <Formulario proveedores={datos} />

  return (
    <div className="contenido seccion-compras">
      <EncabezadoPagina titulo="Nueva orden de compra" descripcion="Pide mercancía a un proveedor. Aún no entra al inventario." />
      {cuerpo}
    </div>
  )
}

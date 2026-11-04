import { useId, useState } from 'react'
import { api } from '../servicios/api.js'
import { porcentajeDesdePrecio, precioDesdePorcentaje } from '../servicios/precio.js'

const PORCENTAJE_MAXIMO = 9899.99

const SOLO_NUMERO = /^-?\d*\.?\d*$/

/** Por qué no se puede guardar el porcentaje escrito; `null` si se puede. */
function motivoDelPorcentaje(texto, precioQueDa) {
  if (texto === '') return 'Escribe el porcentaje o el precio.'
  if (!SOLO_NUMERO.test(texto)) return 'Escribe solo cifras.'
  const n = Number(texto)
  if (n <= 0) {
    return 'El porcentaje debe ser mayor que cero. Para vender sin ganancia o con pérdida, escribe el precio.'
  }
  if (!/^\d*(\.\d{0,2}0*)?$/.test(texto)) return 'Usa máximo dos decimales.'
  if (n > PORCENTAJE_MAXIMO) return 'El porcentaje no puede pasar de 9.899,99 %.'
  if (precioQueDa === '0') return 'Con ese porcentaje el precio da 0 pesos. Escribe el precio.'
  return null
}

/** Por qué no se puede guardar el precio escrito; `null` si se puede. */
function motivoDelPrecio(texto) {
  if (texto === '') return 'Escribe el porcentaje o el precio.'
  if (!SOLO_NUMERO.test(texto)) return 'Escribe solo cifras.'
  if (Number(texto) <= 0) return 'El precio debe ser mayor que cero.'
  if (!/^\d+(\.0*)?$/.test(texto)) return 'El precio va en pesos enteros, sin centavos.'
  if (texto.split('.')[0].replace(/^0+/, '').length > 14) return 'El precio admite hasta 14 cifras.'
  return null
}

const comoTexto = (valor) => (valor === null || valor === undefined ? '' : String(valor))

/**
 * Una fila de precio tal como se abre.
 *
 * El porcentaje es el declarado en la regla y no el margen real, porque el
 * real sale del precio ya redondeado: con costo 7 al 20 % el precio es 8 y
 * el margen 14,29 %, y abrir con 14,29 cambiaría el precio sin que nadie lo
 * pidiera. `origen` dice cuál de los dos se conserva si cambia el costo.
 * `tocado` dice si el usuario escribió en esta fila, que es lo único que se
 * envía: mandar lo que solo se abrió sacaría al producto de la regla de su
 * categoría al corregirle el nombre.
 */
function filaInicial({ porcentaje, margen, precio, precioFijo }) {
  return {
    porcentaje: comoTexto(porcentaje ?? margen),
    precio: comoTexto(precioFijo ?? precio),
    origen: precioFijo != null ? 'precio' : porcentaje != null ? 'porcentaje' : null,
    tocado: false,
  }
}

function recalcular(fila, costo) {
  if (fila.origen === 'porcentaje') {
    return { ...fila, precio: precioDesdePorcentaje(costo, fila.porcentaje) }
  }
  if (fila.origen === 'precio') {
    return { ...fila, porcentaje: porcentajeDesdePrecio(costo, fila.precio) }
  }
  return fila
}

function motivoDe(fila) {
  if (!fila.tocado) return null
  return fila.origen === 'porcentaje'
    ? motivoDelPorcentaje(fila.porcentaje, fila.precio)
    : motivoDelPrecio(fila.precio)
}

/** Lo que el usuario escribió en la fila, y solo eso. */
function valorEscrito(fila) {
  return fila.origen === 'porcentaje'
    ? { porcentaje: Number(fila.porcentaje) }
    : { precio: Number(fila.precio) }
}

/**
 * Lo que hay que decirle al servidor sobre el precio.
 *
 * El caso delicado es el crédito. Si se escribe el contado y no se dice nada
 * del crédito, el servidor lo iguala: es lo que pide la casilla sin marcar.
 * Con la casilla marcada y el crédito sin tocar, hay que pedirle que no lo
 * mueva, porque lo que se ve en pantalla es lo que debe quedar. Y desmarcarla
 * es una orden en sí misma, aunque el contado no haya cambiado.
 */
function preciosAEnviar(contado, credito, otroCredito, teniaCreditoPropio) {
  const envio = {}
  if (contado.tocado) envio.contado = valorEscrito(contado)
  if (otroCredito && credito.tocado) envio.credito = valorEscrito(credito)
  else if (otroCredito && contado.tocado) envio.creditoIgualAlContado = false
  else if (!otroCredito && teniaCreditoPropio && !contado.tocado) envio.creditoIgualAlContado = true
  return envio
}

/**
 * Un campo con su mensaje al lado. No es un `label` envolvente, como los
 * demás del formulario, porque entonces el mensaje formaría parte del nombre
 * del campo y un lector de pantalla lo leería como su etiqueta.
 */
function CampoDePrecio({ etiqueta, valor, onCambio, motivo, advertencia }) {
  const id = useId()
  const idMensaje = `${id}-mensaje`
  const hayMensaje = Boolean(motivo || advertencia)
  return (
    <div className="campo">
      <label htmlFor={id}><span>{etiqueta}</span></label>
      {/* step="any": con 0.01 el navegador tapa con su globo el mensaje que
          explica el problema, y un precio bajo el costo es válido. */}
      <input
        id={id}
        type="number"
        step="any"
        value={valor}
        onChange={(e) => onCambio(e.target.value)}
        aria-invalid={motivo ? 'true' : undefined}
        aria-describedby={hayMensaje ? idMensaje : undefined}
      />
      {motivo && <small id={idMensaje} className="campo-mensaje error">{motivo}</small>}
      {!motivo && advertencia && (
        <small id={idMensaje} className="campo-mensaje advertencia" role="status">{advertencia}</small>
      )}
    </div>
  )
}

/** El porcentaje y el precio de una forma de pago, lado a lado. */
function FilaDePrecio({ fila, costo, sufijo, onCambio }) {
  const motivo = motivoDe(fila)
  const bajoCosto = fila.precio !== '' && Number(costo) > 0 && Number(fila.precio) < Number(costo)
  const escribir = (campo) => (valor) => onCambio(recalcular(
    { ...fila, [campo]: valor, origen: campo, tocado: true }, costo))
  return (
    <div className="rejilla">
      <CampoDePrecio
        etiqueta={`% de ganancia${sufijo}`}
        valor={fila.porcentaje}
        onCambio={escribir('porcentaje')}
        motivo={fila.origen === 'porcentaje' ? motivo : null}
      />
      <CampoDePrecio
        etiqueta={`Precio de venta${sufijo}`}
        valor={fila.precio}
        onCambio={escribir('precio')}
        motivo={fila.origen === 'precio' ? motivo : null}
        advertencia={bajoCosto
          ? 'Queda por debajo del costo: se vendería con pérdida. Puedes guardarlo así.'
          : null}
      />
    </div>
  )
}

/**
 * Edición de un producto del catálogo (RF-15).
 *
 * No hay campo de existencia, y no es un olvido: la existencia solo
 * cambia por movimientos de inventario, que registran el valor anterior
 * y el posterior. Un formulario que la dejara escribir produciría un
 * salto sin explicación en la única tabla que permite reconstruir qué
 * pasó con una referencia. El dato se muestra, en gris y sin control,
 * para que quien edita sepa cuál es sin poder tocarlo.
 *
 * El precio sí se escribe (ADR-0025), con el porcentaje al lado. Lo que se
 * calcula aquí es solo vista previa: se envía lo que el usuario escribió, y
 * el catálogo se refresca con lo que devuelve el servidor.
 */
export default function FormularioProducto({ producto, categorias, proveedores, onCerrar, onGuardado }) {
  const [datos, setDatos] = useState({
    categoriaId: producto.categoriaId ?? '',
    proveedorId: producto.proveedorId ?? '',
    codigo: producto.codigo ?? '',
    sku: producto.sku ?? '',
    codigoBarras: producto.codigoBarras ?? '',
    nombre: producto.nombre ?? '',
    presentacion: producto.presentacion ?? '',
    descripcion: producto.descripcion ?? '',
    costo: producto.costo ?? '',
    existenciaMinima: producto.existenciaMinima ?? '',
  })
  const [contado, setContado] = useState(() => filaInicial({
    porcentaje: producto.porcentajeContado, margen: producto.margenContado,
    precio: producto.precioContado, precioFijo: producto.precioFijoContado,
  }))
  const [credito, setCredito] = useState(() => filaInicial({
    porcentaje: producto.porcentajeCredito, margen: producto.margenCredito,
    precio: producto.precioCredito, precioFijo: producto.precioFijoCredito,
  }))
  // «Igual al contado» no se guarda como tal: son dos reglas con los mismos
  // datos. Lo que dice si hay un crédito propio es que las cifras difieran.
  const teniaCreditoPropio = producto.precioCredito != null
    && Number(producto.precioCredito) !== Number(producto.precioContado)
  const [otroCredito, setOtroCredito] = useState(teniaCreditoPropio)
  const [error, setError] = useState(null)
  const [guardando, setGuardando] = useState(false)

  const cambiar = (campo) => (e) => setDatos((d) => ({ ...d, [campo]: e.target.value }))

  function cambiarCosto(e) {
    const costo = e.target.value
    setDatos((d) => ({ ...d, costo }))
    // No cuenta como escrito: el recalculado no lo escribió nadie, y el
    // servidor hace por su cuenta lo mismo con la regla que ya tiene.
    setContado((f) => recalcular(f, costo))
    setCredito((f) => recalcular(f, costo))
  }

  async function guardar(e) {
    e.preventDefault()
    if (motivoDe(contado) || (otroCredito && motivoDe(credito))) {
      e.currentTarget.querySelector('[aria-invalid="true"]')?.focus()
      return
    }
    setGuardando(true)
    setError(null)
    try {
      const fresco = await api.editarProducto(producto.productoId, {
        ...datos,
        // Los selectores devuelven cadenas; el backend espera números.
        categoriaId: Number(datos.categoriaId),
        proveedorId: Number(datos.proveedorId),
        existenciaMinima: datos.existenciaMinima === '' ? null : Number(datos.existenciaMinima),
        ...preciosAEnviar(contado, credito, otroCredito, teniaCreditoPropio),
      })
      onGuardado(fresco)
    } catch (e2) {
      // El mensaje del backend es el que sirve: dice qué código está
      // repetido o qué referencia es de otra empresa.
      setError(e2.message)
      setGuardando(false)
    }
  }

  return (
    <div className="velo" role="dialog" aria-modal="true" aria-label={`Editar ${producto.nombre}`}>
      <form className="tarjeta panel" onSubmit={guardar}>
        <div className="panel-cabecera">
          <h3>Editar producto</h3>
          <button type="button" className="boton secundario" onClick={onCerrar}>Cancelar</button>
        </div>

        {error && <div className="aviso error" role="alert">{error}</div>}

        <label className="campo">
          <span>Nombre</span>
          <input value={datos.nombre} onChange={cambiar('nombre')} required maxLength={160} />
        </label>

        <div className="rejilla">
          <label className="campo">
            <span>Categoría</span>
            <select value={datos.categoriaId} onChange={cambiar('categoriaId')} required>
              <option value="">Elige una</option>
              {categorias.map((c) => <option key={c.id} value={c.id}>{c.nombre}</option>)}
            </select>
          </label>

          <label className="campo">
            <span>Proveedor</span>
            <select value={datos.proveedorId} onChange={cambiar('proveedorId')} required>
              <option value="">Elige uno</option>
              {proveedores.map((p) => (
                <option key={p.id} value={p.id}>{p.razonSocial ?? p.nombre}</option>
              ))}
            </select>
          </label>
        </div>

        <div className="rejilla">
          <label className="campo">
            <span>Código</span>
            <input value={datos.codigo} onChange={cambiar('codigo')} required maxLength={60} />
          </label>

          <label className="campo">
            <span>SKU</span>
            <input
              value={datos.sku}
              onChange={cambiar('sku')}
              maxLength={40}
              placeholder="Si lo dejas vacío se usa el código"
            />
          </label>
        </div>

        <div className="rejilla">
          <label className="campo">
            <span>Presentación</span>
            <input value={datos.presentacion} onChange={cambiar('presentacion')} maxLength={60} />
          </label>

          <label className="campo">
            <span>Código de barras</span>
            <input value={datos.codigoBarras} onChange={cambiar('codigoBarras')} maxLength={60} />
          </label>
        </div>

        <div className="rejilla">
          <label className="campo">
            <span>Costo</span>
            <input
              type="number" step="0.01" min="0.01"
              value={datos.costo} onChange={cambiarCosto} required
            />
          </label>

          <label className="campo">
            <span>Existencia mínima</span>
            <input
              type="number" step="1" min="0"
              value={datos.existenciaMinima} onChange={cambiar('existenciaMinima')}
            />
          </label>
        </div>

        <FilaDePrecio fila={contado} costo={datos.costo} sufijo="" onCambio={setContado} />
        <p className="sub-linea ayuda-precio">
          Escribe el porcentaje o el precio y el otro se completa. El que escribas
          es el que se conserva si cambia el costo.
        </p>

        <label className="casilla">
          <input
            type="checkbox"
            checked={otroCredito}
            onChange={(e) => setOtroCredito(e.target.checked)}
          />
          <span>Usar otro precio a crédito</span>
        </label>
        {otroCredito && (
          <FilaDePrecio fila={credito} costo={datos.costo} sufijo=" a crédito" onCambio={setCredito} />
        )}

        <label className="campo">
          <span>Descripción</span>
          <input value={datos.descripcion} onChange={cambiar('descripcion')} maxLength={400} />
        </label>

        <p className="sub-linea">
          Existencia actual: {producto.existencia}. Se cambia con movimientos de
          inventario, no desde aquí, para que todo cambio quede registrado con su
          valor anterior y posterior.
        </p>

        <div className="panel-acciones">
          <button className="boton" type="submit" disabled={guardando}>
            {guardando ? 'Guardando…' : 'Guardar cambios'}
          </button>
        </div>
      </form>
    </div>
  )
}

import { cloneElement, useEffect, useId, useRef, useState } from 'react'
import { api } from '../servicios/api.js'
import { porcentajeDesdePrecio, precioDesdePorcentaje } from '../servicios/precio.js'

const PORCENTAJE_MAXIMO = 9899.99

/**
 * Las cifras de un precio que se puede vender: `precio_unitario` de las ventas
 * es DECIMAL(14,2). Un precio más largo se guardaría y luego no se podría cobrar.
 */
const CIFRAS_DEL_PRECIO = 12

const SOLO_NUMERO = /^-?\d*\.?\d*$/

const FALTA_EL_PRECIO = 'Escribe el porcentaje o el precio.'

/** Por qué no se puede guardar el porcentaje escrito; `null` si se puede. */
function motivoDelPorcentaje(texto, precioQueDa) {
  if (texto === '') return FALTA_EL_PRECIO
  if (!SOLO_NUMERO.test(texto)) return 'Escribe solo cifras.'
  const n = Number(texto)
  if (n <= 0) {
    return 'El porcentaje debe ser mayor que cero. Para vender sin ganancia o con pérdida, escribe el precio.'
  }
  if (!/^\d*(\.\d{0,2}0*)?$/.test(texto)) return 'Usa máximo dos decimales.'
  if (n > PORCENTAJE_MAXIMO) return 'El porcentaje no puede pasar de 9.899,99 %.'
  if (precioQueDa === '0') return 'Con ese porcentaje el precio da 0 pesos. Escribe el precio.'
  if (precioQueDa.length > CIFRAS_DEL_PRECIO) {
    return `Con ese porcentaje el precio pasa de ${CIFRAS_DEL_PRECIO} cifras y no se podría vender.`
  }
  return null
}

/** Por qué no se puede guardar el precio escrito; `null` si se puede. */
function motivoDelPrecio(texto) {
  if (texto === '') return FALTA_EL_PRECIO
  if (!SOLO_NUMERO.test(texto)) return 'Escribe solo cifras.'
  if (Number(texto) <= 0) return 'El precio debe ser mayor que cero.'
  if (!/^\d+(\.0*)?$/.test(texto)) return 'El precio va en pesos enteros, sin centavos.'
  if (texto.split('.')[0].replace(/^0+/, '').length > CIFRAS_DEL_PRECIO) {
    return `El precio admite hasta ${CIFRAS_DEL_PRECIO} cifras.`
  }
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

/**
 * `exigido`: en el alta el contado es obligatorio aunque nadie lo haya tocado,
 * y el crédito también si se marcó la casilla. Al editar, una fila sin tocar
 * conserva lo que ya tiene y no hay nada que exigirle.
 */
function motivoDe(fila, exigido) {
  if (!fila.tocado) return exigido ? FALTA_EL_PRECIO : null
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
 * Lo que hay que decirle al servidor sobre el precio al editar.
 *
 * El caso delicado es el crédito. Si se escribe el contado y no se dice nada
 * del crédito, el servidor lo iguala: es lo que pide la casilla sin marcar.
 * Con la casilla marcada y el crédito sin tocar, hay que pedirle que no lo
 * mueva, porque lo que se ve en pantalla es lo que debe quedar. Y desmarcarla
 * es una orden en sí misma, aunque el contado no haya cambiado.
 */
function preciosAlEditar(contado, credito, otroCredito, teniaCreditoPropio) {
  const envio = {}
  if (contado.tocado) envio.contado = valorEscrito(contado)
  if (otroCredito && credito.tocado) envio.credito = valorEscrito(credito)
  else if (otroCredito && contado.tocado) envio.creditoIgualAlContado = false
  else if (!otroCredito && teniaCreditoPropio && !contado.tocado) envio.creditoIgualAlContado = true
  return envio
}

/** Al nacer no hay regla que conservar: sin crédito propio, el servidor lo iguala. */
function preciosAlCrear(contado, credito, otroCredito) {
  const envio = { contado: valorEscrito(contado) }
  if (otroCredito) envio.credito = valorEscrito(credito)
  return envio
}

/**
 * Si el crédito es otra regla, no solo otra cifra.
 *
 * Comparar solo los precios engaña: al 20 % sobre 1.000 y con 1.200 escritos a
 * crédito, las dos cifras coinciden hoy, pero son dos decisiones distintas. Si
 * se abriera como «igual», cambiar el contado borraría el precio escrito sin
 * que nadie lo viera, y cambiar el costo los separaría con la fila oculta.
 */
function creditoEsOtraRegla(p) {
  if (p.precioCredito == null) return false
  const distintos = (a, b) => (a == null || b == null ? a != b : Number(a) !== Number(b))
  return distintos(p.precioCredito, p.precioContado)
    || distintos(p.precioFijoCredito, p.precioFijoContado)
    || distintos(p.porcentajeCredito, p.porcentajeContado)
}

const ES_CANTIDAD = /^\d+$/

const SIN_CATEGORIAS = 'No hay categorías todavía. Créalas con el botón Categorías del catálogo.'
const SIN_PROVEEDORES = 'No hay proveedores todavía. Sin proveedor no se puede crear el producto.'

/** Lo obligatorio al crear. `SolicitudProducto` exige estos cinco; el costo, positivo. */
function motivosDelAlta(datos, existenciaInicial, categorias, proveedores) {
  const costo = datos.costo === '' ? null : Number(datos.costo)
  const cantidad = (texto) => (texto === '' || ES_CANTIDAD.test(texto)
    ? null
    : 'Escribe una cantidad entera, de cero en adelante.')
  return {
    nombre: datos.nombre.trim() === '' ? 'Escribe el nombre del producto.' : null,
    categoriaId: datos.categoriaId === ''
      ? (categorias.length === 0 ? SIN_CATEGORIAS : 'Elige una categoría.')
      : null,
    proveedorId: datos.proveedorId === ''
      ? (proveedores.length === 0 ? SIN_PROVEEDORES : 'Elige un proveedor.')
      : null,
    codigo: datos.codigo.trim() === '' ? 'Escribe el código del producto.' : null,
    costo: costo === null || costo <= 0 || !/^\d*(\.\d{0,2})?$/.test(datos.costo)
      ? 'Escribe un costo mayor que cero, con dos decimales como máximo.'
      : null,
    existenciaInicial: cantidad(existenciaInicial),
    existenciaMinima: cantidad(comoTexto(datos.existenciaMinima)),
  }
}

/**
 * Un campo con su mensaje al lado. No es un `label` envolvente, porque entonces
 * el mensaje formaría parte del nombre del campo y un lector de pantalla lo
 * leería como su etiqueta.
 *
 * La región del mensaje existe siempre, vacía si no hay nada que decir: una
 * región viva que aparece junto con su texto no se anuncia en todos los lectores
 * de pantalla. Es `status` y no `alert` porque se escribe mientras se teclea, y
 * un aviso a cada pulsación interrumpiría lo que se está escribiendo.
 */
function Campo({ etiqueta, motivo, advertencia, children }) {
  const id = useId()
  const idMensaje = `${id}-mensaje`
  const mensaje = motivo || advertencia || ''
  return (
    <div className="campo">
      <label htmlFor={id}><span>{etiqueta}</span></label>
      {cloneElement(children, {
        id,
        'aria-invalid': motivo ? 'true' : undefined,
        'aria-describedby': mensaje ? idMensaje : undefined,
      })}
      <small
        id={idMensaje}
        role="status"
        className={`campo-mensaje ${motivo ? 'error' : 'advertencia'}`}
      >
        {mensaje}
      </small>
    </div>
  )
}

/** El porcentaje y el precio de una forma de pago, lado a lado. */
function FilaDePrecio({ fila, costo, sufijo, exigido, intentado, onCambio }) {
  const motivo = fila.tocado || intentado ? motivoDe(fila, exigido) : null
  const bajoCosto = fila.precio !== '' && Number(costo) > 0 && Number(fila.precio) < Number(costo)
  const escribir = (campo) => (e) => onCambio(recalcular(
    { ...fila, [campo]: e.target.value, origen: campo, tocado: true }, costo))
  return (
    <div className="rejilla">
      {/* step="any": con 0.01 el navegador tapa con su globo el mensaje que
          explica el problema, y un precio bajo el costo es válido. */}
      <Campo
        etiqueta={`% de ganancia ${sufijo}`}
        // Sin nada escrito no hay uno que culpar: el aviso va en el primero.
        motivo={fila.origen === 'precio' ? null : motivo}
      >
        <input type="number" step="any" value={fila.porcentaje} onChange={escribir('porcentaje')} />
      </Campo>
      <Campo
        etiqueta={`Precio de venta ${sufijo}`}
        motivo={fila.origen === 'precio' ? motivo : null}
        advertencia={bajoCosto
          ? 'Queda por debajo del costo: se vendería con pérdida. Puedes guardarlo así.'
          : null}
      >
        <input type="number" step="any" value={fila.precio} onChange={escribir('precio')} />
      </Campo>
    </div>
  )
}

const SIN_PRODUCTO = {}

/**
 * Alta (P-05) y edición (RF-15) de un producto del catálogo.
 *
 * Al editar no hay campo de existencia, y no es un olvido: la existencia solo
 * cambia por movimientos de inventario, que registran el valor anterior
 * y el posterior. Un formulario que la dejara escribir produciría un
 * salto sin explicación en la única tabla que permite reconstruir qué
 * pasó con una referencia. El dato se muestra, en gris y sin control,
 * para que quien edita sepa cuál es sin poder tocarlo. La existencia inicial
 * del alta no contradice esto: el backend la convierte en ese primer movimiento.
 *
 * El precio sí se escribe (ADR-0025), con el porcentaje al lado. Lo que se
 * calcula aquí es solo vista previa: se envía lo que el usuario escribió, y
 * el catálogo se refresca con lo que devuelve el servidor.
 */
export default function FormularioProducto({
  modo = 'editar', producto = SIN_PRODUCTO, categorias, proveedores, onCerrar, onGuardado,
}) {
  const esAlta = modo === 'crear'
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
  // Aparte de `datos`, que viaja entero al editar: la edición no debe llevarla.
  const [existenciaInicial, setExistenciaInicial] = useState('')
  const [contado, setContado] = useState(() => filaInicial({
    porcentaje: producto.porcentajeContado, margen: producto.margenContado,
    precio: producto.precioContado, precioFijo: producto.precioFijoContado,
  }))
  const [credito, setCredito] = useState(() => filaInicial({
    porcentaje: producto.porcentajeCredito, margen: producto.margenCredito,
    precio: producto.precioCredito, precioFijo: producto.precioFijoCredito,
  }))
  const teniaCreditoPropio = creditoEsOtraRegla(producto)
  const [otroCredito, setOtroCredito] = useState(teniaCreditoPropio)
  const [error, setError] = useState(null)
  const [guardando, setGuardando] = useState(false)
  // Las que se intentaron guardar: los errores de un campo sin tocar no se
  // muestran antes, y el foco salta al primero cada vez que se intenta.
  const [intentos, setIntentos] = useState(0)
  const formulario = useRef(null)
  const aviso = useRef(null)

  useEffect(() => {
    if (intentos > 0) formulario.current?.querySelector('[aria-invalid="true"]')?.focus()
  }, [intentos])

  // El aviso va arriba y el botón abajo, en un panel que se desplaza: sin esto un
  // rechazo del servidor (código repetido) podría quedar fuera de la vista.
  useEffect(() => {
    if (!error) return
    aviso.current?.focus()
    aviso.current?.scrollIntoView?.({ block: 'nearest' })
  }, [error])

  const intentado = intentos > 0
  const motivosCampos = esAlta
    ? motivosDelAlta(datos, existenciaInicial, categorias, proveedores)
    : {}
  const motivo = (campo) => (intentado ? motivosCampos[campo] ?? null : null)

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
    if (motivoDe(contado, esAlta)
      || (otroCredito && motivoDe(credito, esAlta))
      || Object.values(motivosCampos).some(Boolean)) {
      setIntentos((n) => n + 1)
      return
    }
    setGuardando(true)
    setError(null)
    // Los selectores devuelven cadenas; el backend espera números.
    const comunes = {
      ...datos,
      categoriaId: Number(datos.categoriaId),
      proveedorId: Number(datos.proveedorId),
      existenciaMinima: datos.existenciaMinima === '' ? null : Number(datos.existenciaMinima),
    }
    try {
      const fresco = esAlta
        ? await api.crearProducto({
          ...comunes,
          existencia: existenciaInicial === '' ? null : Number(existenciaInicial),
          ...preciosAlCrear(contado, credito, otroCredito),
        })
        : await api.editarProducto(producto.productoId, {
          ...comunes,
          ...preciosAlEditar(contado, credito, otroCredito, teniaCreditoPropio),
        })
      onGuardado(fresco)
    } catch (e2) {
      // El mensaje del backend es el que sirve: dice qué código está
      // repetido o qué referencia es de otra empresa.
      setError(e2.message)
      setGuardando(false)
    }
  }

  const titulo = esAlta ? 'Nuevo producto' : 'Editar producto'
  const campoCosto = (
    <Campo etiqueta="Costo" motivo={motivo('costo')}>
      <input
        type="number" step="0.01" min="0.01"
        value={datos.costo} onChange={cambiarCosto} required
      />
    </Campo>
  )
  const campoMinima = (
    <Campo etiqueta="Existencia mínima" motivo={motivo('existenciaMinima')}>
      <input
        type="number" step="1" min="0"
        value={datos.existenciaMinima} onChange={cambiar('existenciaMinima')}
      />
    </Campo>
  )

  return (
    <div
      className="velo" role="dialog" aria-modal="true"
      aria-label={esAlta ? titulo : `Editar ${producto.nombre}`}
    >
      {/* Al crear, los errores los dice cada campo con palabras: el globo del
          navegador muestra uno solo y desaparece. */}
      <form className="tarjeta panel" onSubmit={guardar} noValidate={esAlta} ref={formulario}>
        <div className="panel-cabecera">
          <h3>{titulo}</h3>
          <button type="button" className="boton secundario" onClick={onCerrar}>Cancelar</button>
        </div>

        {error && <div className="aviso error" role="alert" tabIndex={-1} ref={aviso}>{error}</div>}

        <Campo etiqueta="Nombre" motivo={motivo('nombre')}>
          <input value={datos.nombre} onChange={cambiar('nombre')} required maxLength={160} />
        </Campo>

        <div className="rejilla">
          <Campo
            etiqueta="Categoría"
            motivo={motivo('categoriaId')}
            advertencia={esAlta && categorias.length === 0 ? SIN_CATEGORIAS : null}
          >
            <select value={datos.categoriaId} onChange={cambiar('categoriaId')} required>
              <option value="">Elige una</option>
              {categorias.map((c) => <option key={c.id} value={c.id}>{c.nombre}</option>)}
            </select>
          </Campo>

          <Campo
            etiqueta="Proveedor"
            motivo={motivo('proveedorId')}
            advertencia={esAlta && proveedores.length === 0 ? SIN_PROVEEDORES : null}
          >
            <select value={datos.proveedorId} onChange={cambiar('proveedorId')} required>
              <option value="">Elige uno</option>
              {proveedores.map((p) => (
                <option key={p.id} value={p.id}>{p.razonSocial ?? p.nombre}</option>
              ))}
            </select>
          </Campo>
        </div>

        <div className="rejilla">
          <Campo etiqueta="Código" motivo={motivo('codigo')}>
            <input value={datos.codigo} onChange={cambiar('codigo')} required maxLength={60} />
          </Campo>

          <Campo etiqueta="SKU">
            <input
              value={datos.sku}
              onChange={cambiar('sku')}
              maxLength={40}
              placeholder="Si lo dejas vacío se usa el código"
            />
          </Campo>
        </div>

        <div className="rejilla">
          <Campo etiqueta="Presentación">
            <input value={datos.presentacion} onChange={cambiar('presentacion')} maxLength={60} />
          </Campo>

          <Campo etiqueta="Código de barras">
            <input value={datos.codigoBarras} onChange={cambiar('codigoBarras')} maxLength={60} />
          </Campo>
        </div>

        {/* Al crear, el costo va solo y justo encima del precio, que sale de él;
            las dos existencias quedan juntas más abajo. */}
        {esAlta ? campoCosto : <div className="rejilla">{campoCosto}{campoMinima}</div>}

        <FilaDePrecio
          fila={contado} costo={datos.costo} sufijo="de contado"
          exigido={esAlta} intentado={intentado} onCambio={setContado}
        />
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
          <FilaDePrecio
            fila={credito} costo={datos.costo} sufijo="a crédito"
            exigido={esAlta} intentado={intentado} onCambio={setCredito}
          />
        )}

        <Campo etiqueta="Descripción">
          <input value={datos.descripcion} onChange={cambiar('descripcion')} maxLength={400} />
        </Campo>

        {esAlta && (
          <div className="rejilla">
            <Campo etiqueta="Existencia inicial" motivo={motivo('existenciaInicial')}>
              <input
                type="number" step="1" min="0"
                value={existenciaInicial}
                onChange={(e) => setExistenciaInicial(e.target.value)}
              />
            </Campo>
            {campoMinima}
          </div>
        )}

        <p className="sub-linea">
          {esAlta
            ? `La existencia inicial queda registrada como el primer movimiento de
               inventario. Si dejas vacía la mínima, se usa 5.`
            : `Existencia actual: ${producto.existencia}. Se cambia con movimientos de
               inventario, no desde aquí, para que todo cambio quede registrado con su
               valor anterior y posterior.`}
        </p>

        <div className="panel-acciones">
          <button className="boton" type="submit" disabled={guardando}>
            {guardando ? 'Guardando…' : esAlta ? 'Crear producto' : 'Guardar cambios'}
          </button>
        </div>
      </form>
    </div>
  )
}

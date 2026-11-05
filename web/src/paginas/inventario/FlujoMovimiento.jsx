import { cloneElement, useEffect, useId, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import Aviso from '../../componentes/comun/Aviso.jsx'
import EncabezadoPagina from '../../componentes/comun/EncabezadoPagina.jsx'
import { apiInventario } from '../../servicios/api/inventario.js'
import { NUMERO } from '../../servicios/formato.js'
import ElegirProducto from './ElegirProducto.jsx'
import { conSigno, NOTA_SIN_BORRAR, unidades } from './movimientos.js'

const MOTIVOS_SUGERIDOS = [
  'Conteo físico', 'Mercancía dañada', 'Mercancía perdida', 'Corrección de un error de digitación',
]
const ENTERO = /^\d+$/

/**
 * Lo que cambia entre un ajuste y un ingreso. El ajuste lleva el saldo
 * CONTADO (el backend calcula la diferencia); el ingreso, la cantidad que
 * entra. Ninguno de los dos se puede deshacer: un error se corrige con otro
 * ajuste.
 */
const TIPOS = {
  ajuste: {
    titulo: 'Ajustar existencia',
    descripcion: 'Cuenta lo que hay en el estante y deja escrito por qué difiere de lo que dice el sistema.',
    etiquetaNumero: 'Cantidad contada',
    ayudaNumero: 'Lo que cuentas ahora. El sistema calcula la diferencia.',
    etiquetaMotivo: 'Motivo del ajuste',
    revisar: 'Revisar ajuste',
    confirmar: 'Confirmar ajuste',
    registrado: 'Ajuste registrado',
    otro: 'Registrar otro ajuste',
    consecuencia: 'Queda en el historial y no se puede editar ni borrar. Si te equivocas, se corrige con otro ajuste.',
    cambio: (actual, n) => n - actual,
  },
  ingreso: {
    titulo: 'Registrar ingreso',
    descripcion: 'Anota la mercancía que entra a la bodega.',
    etiquetaNumero: 'Cantidad que entra',
    ayudaNumero: 'Unidades que llegaron.',
    etiquetaMotivo: 'Motivo (opcional)',
    revisar: 'Revisar ingreso',
    confirmar: 'Confirmar ingreso',
    registrado: 'Ingreso registrado',
    otro: 'Registrar otro ingreso',
    consecuencia: 'Queda en el historial y no se puede editar ni borrar. Si entró de más, se corrige con un ajuste.',
    cambio: (_actual, n) => n,
  },
}

const PASOS = { formulario: 'Datos', revision: 'Revisión', listo: 'Listo' }
const NUMERO_DE_PASO = { formulario: 1, revision: 2, listo: 3 }

/** Por qué no se puede revisar lo escrito; cada campo con su mensaje, o `null`. */
function motivos(tipo, d) {
  const n = d.numero.trim()
  const motivo = d.motivo.trim()
  const m = {
    producto: d.producto ? null : 'Elige el producto: búscalo por nombre o código.',
    documento: d.documento.trim().length > 60 ? 'El documento no puede pasar de 60 caracteres.' : null,
  }
  const noEntero = !ENTERO.test(n) ? 'Escribe un número entero, sin decimales ni signos.' : null
  const grande = n.length > 9 ? 'Ese número es demasiado grande.' : null
  if (tipo === 'ajuste') {
    m.numero = n === '' ? 'Escribe cuántas unidades cuentas ahora.' : noEntero ?? grande
    m.motivo = motivo === ''
      ? 'Escribe el motivo del ajuste: es lo que explica por qué cambió la existencia.' : null
  } else {
    m.numero = n === '' ? 'Escribe cuántas unidades entran.'
      : noEntero ?? grande ?? (Number(n) < 1 ? 'La cantidad tiene que ser al menos una unidad.' : null)
    m.motivo = null
  }
  if (motivo.length > 200) m.motivo = 'El motivo no puede pasar de 200 caracteres.'
  return m
}

function Campo({ etiqueta, ayuda, motivo, children }) {
  const id = useId()
  const idMensaje = `${id}-mensaje`
  return (
    <div className="campo">
      <label htmlFor={id}><span>{etiqueta}</span></label>
      {cloneElement(children, {
        id,
        'aria-invalid': motivo ? 'true' : undefined,
        'aria-describedby': idMensaje,
      })}
      <small id={idMensaje} className={`campo-mensaje ${motivo ? 'error' : ''}`}>{motivo || ayuda || ''}</small>
    </div>
  )
}

/**
 * Ajuste (pantallas 43–45) o ingreso, en tres pasos dentro de la misma
 * página: datos, revisión y listo. El paso vive en el estado y no en la URL.
 *
 * La existencia resultante de la revisión es una vista previa calculada con
 * la existencia que se vio al elegir el producto. Al confirmar manda la que
 * devuelve el servidor, y si el saldo se movió entretanto se dice.
 */
export default function FlujoMovimiento({ tipo, productoInicial = null }) {
  const cfg = TIPOS[tipo]
  const [paso, setPaso] = useState('formulario')
  const [datos, setDatos] = useState({ producto: productoInicial, numero: '', motivo: '', documento: '' })
  const [intentos, setIntentos] = useState(0)
  const [enviando, setEnviando] = useState(false)
  const [error, setError] = useState(null)
  const [resultado, setResultado] = useState(null)
  const formulario = useRef(null)
  const aviso = useRef(null)
  const titulo = useRef(null)
  const enCurso = useRef(false)

  const fallos = motivos(tipo, datos)
  const ver = (campo) => (intentos > 0 ? fallos[campo] ?? null : null)

  useEffect(() => {
    if (intentos > 0 && paso === 'formulario') {
      formulario.current?.querySelector('[aria-invalid="true"]')?.focus()
    }
  }, [intentos, paso])

  useEffect(() => {
    if (error) aviso.current?.focus()
  }, [error])

  useEffect(() => {
    if (paso !== 'formulario') titulo.current?.focus()
  }, [paso])

  const cambiar = (campo) => (e) => {
    const valor = e.target.value
    setDatos((d) => ({ ...d, [campo]: valor }))
  }

  const producto = datos.producto
  const actual = producto?.existencia ?? 0
  const numero = Number(datos.numero.trim())
  const cambio = cfg.cambio(actual, numero)

  function revisar(e) {
    e.preventDefault()
    setIntentos((n) => n + 1)
    if (Object.values(fallos).some(Boolean)) return
    setError(null)
    setPaso('revision')
  }

  async function confirmar() {
    if (enCurso.current) return
    enCurso.current = true
    setEnviando(true)
    setError(null)
    const documento = datos.documento.trim() || null
    const motivo = datos.motivo.trim() || null
    try {
      const movimiento = tipo === 'ajuste'
        ? await apiInventario.ajustar({
          productoId: producto.productoId, existenciaContada: numero, motivo, documento,
        })
        : await apiInventario.ingresar({
          productoId: producto.productoId, cantidad: numero, documento, motivo,
        })
      setResultado(movimiento)
      setPaso('listo')
    } catch (err) {
      setError(err.estado === 404
        ? 'Este producto ya no está en tu catálogo. Vuelve atrás y elige otro.'
        : err.message || 'No se pudo completar la operación.')
    } finally {
      enCurso.current = false
      setEnviando(false)
    }
  }

  function empezarOtro() {
    setDatos({ producto: null, numero: '', motivo: '', documento: '' })
    setIntentos(0)
    setResultado(null)
    setError(null)
    setPaso('formulario')
  }

  return (
    <div className="contenido inventario">
      <p className="volver"><Link to="/inventario">Volver a movimientos</Link></p>
      <EncabezadoPagina titulo={cfg.titulo} descripcion={cfg.descripcion} />
      <p className="paso">{`Paso ${NUMERO_DE_PASO[paso]} de 3: ${PASOS[paso]}`}</p>

      {paso === 'formulario' && (
        <form className="tarjeta formulario-inventario" onSubmit={revisar} noValidate ref={formulario}>
          {producto ? (
            <div className="producto-elegido">
              <div>
                <strong>{producto.nombre}</strong>
                <span>{producto.codigo}</span>
              </div>
              <div className="dato">
                <span>Existencia actual</span>
                <strong>{NUMERO.format(producto.existencia)}</strong>
              </div>
              <button
                type="button"
                className="boton secundario"
                onClick={() => setDatos((d) => ({ ...d, producto: null }))}
              >
                Cambiar producto
              </button>
            </div>
          ) : (
            <ElegirProducto
              etiqueta="Producto"
              error={ver('producto')}
              alElegir={(p) => setDatos((d) => ({ ...d, producto: p }))}
            />
          )}

          <Campo etiqueta={cfg.etiquetaNumero} ayuda={cfg.ayudaNumero} motivo={ver('numero')}>
            <input type="text" inputMode="numeric" autoComplete="off" value={datos.numero} onChange={cambiar('numero')} />
          </Campo>

          <Campo etiqueta={cfg.etiquetaMotivo} motivo={ver('motivo')}>
            <input
              type="text"
              autoComplete="off"
              list={tipo === 'ajuste' ? 'motivos-ajuste' : undefined}
              value={datos.motivo}
              onChange={cambiar('motivo')}
            />
          </Campo>
          {tipo === 'ajuste' && (
            <datalist id="motivos-ajuste">
              {MOTIVOS_SUGERIDOS.map((s) => <option key={s} value={s} />)}
            </datalist>
          )}

          <Campo etiqueta="Documento o referencia (opcional)" motivo={ver('documento')}>
            <input type="text" autoComplete="off" value={datos.documento} onChange={cambiar('documento')} />
          </Campo>

          <p className="nota">{NOTA_SIN_BORRAR}</p>
          <div className="acciones">
            <button type="submit" className="boton">{cfg.revisar}</button>
            <Link to="/inventario" className="boton secundario enlace-boton">Cancelar</Link>
          </div>
        </form>
      )}

      {paso === 'revision' && (
        <section className="tarjeta revision" aria-labelledby="titulo-revision">
          <h2 id="titulo-revision" tabIndex={-1} ref={titulo}>Revisa antes de confirmar</h2>
          {error && (
            <div tabIndex={-1} ref={aviso} className="aviso-foco"><Aviso tipo="peligro">{error}</Aviso></div>
          )}
          <dl className="cifras-revision">
            <dt>Producto</dt>
            <dd>{producto.nombre} <small>{producto.codigo}</small></dd>
            <dt>Existencia actual</dt>
            <dd>{NUMERO.format(actual)}</dd>
            <dt>Cambio</dt>
            <dd>{conSigno(cambio)} {Math.abs(cambio) === 1 ? 'unidad' : 'unidades'}</dd>
            <dt>Existencia resultante</dt>
            <dd>{NUMERO.format(actual + cambio)} <small>vista previa</small></dd>
            {datos.motivo.trim() && (<><dt>Motivo</dt><dd>{datos.motivo.trim()}</dd></>)}
            {datos.documento.trim() && (<><dt>Documento</dt><dd>{datos.documento.trim()}</dd></>)}
          </dl>
          {cambio === 0 ? (
            <p className="nota">
              No hay nada que ajustar: lo que contaste ({NUMERO.format(numero)}) es igual a la
              existencia. No se registrará ningún movimiento.
            </p>
          ) : (
            <p className="nota">
              Es una vista previa con la existencia que viste al elegir el producto: al confirmar
              verás la que calcula el sistema. {cfg.consecuencia}
            </p>
          )}
          <div className="acciones">
            {cambio !== 0 && (
              <button type="button" className="boton" disabled={enviando} onClick={confirmar}>
                {enviando ? 'Registrando…' : cfg.confirmar}
              </button>
            )}
            <button type="button" className="boton secundario" disabled={enviando} onClick={() => setPaso('formulario')}>
              Corregir
            </button>
          </div>
        </section>
      )}

      {paso === 'listo' && (
        <section className="tarjeta listo" aria-labelledby="titulo-listo">
          <h2 id="titulo-listo" tabIndex={-1} ref={titulo}>
            {resultado ? cfg.registrado : 'Sin cambios'}
          </h2>
          {resultado ? (
            <>
              <p>{producto.nombre} ahora tiene {unidades(resultado.existenciaPosterior)}.</p>
              {resultado.existenciaAnterior !== actual && (
                <p className="nota">
                  Mientras tanto la existencia cambió: cuando lo revisaste era {NUMERO.format(actual)} y
                  al registrarlo era {NUMERO.format(resultado.existenciaAnterior)}. Vale esta cifra.
                </p>
              )}
              <dl className="cifras-revision">
                <dt>Existencia anterior</dt>
                <dd>{NUMERO.format(resultado.existenciaAnterior)}</dd>
                <dt>Cambio</dt>
                <dd>{conSigno(resultado.existenciaPosterior - resultado.existenciaAnterior)}</dd>
                <dt>Existencia resultante</dt>
                <dd>{NUMERO.format(resultado.existenciaPosterior)}</dd>
              </dl>
            </>
          ) : (
            <p>El conteo coincide con la existencia del sistema: no se registró ningún movimiento.</p>
          )}
          <div className="acciones">
            <Link to={`/inventario/productos/${producto.productoId}`} className="boton enlace-boton">
              Ver el historial de este producto
            </Link>
            <button type="button" className="boton secundario" onClick={empezarOtro}>{cfg.otro}</button>
            <Link to="/inventario" className="boton secundario enlace-boton">Volver a movimientos</Link>
          </div>
        </section>
      )}
    </div>
  )
}

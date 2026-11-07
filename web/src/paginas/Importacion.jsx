import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import EncabezadoPagina from '../componentes/comun/EncabezadoPagina.jsx'
import EstadoCarga from '../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../componentes/comun/EstadoSinPermiso.jsx'
import EstadoVacio from '../componentes/comun/EstadoVacio.jsx'
import TablaOTarjetas from '../componentes/comun/TablaOTarjetas.jsx'
import { useDatos } from '../hooks/useDatos.js'
import { api } from '../servicios/api.js'
import { esAdministrador } from '../servicios/sesion.js'

import { NUMERO, costoEnPesos, porcentaje } from '../servicios/formato.js'
import '../estilos/importacion.css'

/** Las categorías que la V30 guarda, con su texto y su color. */
const PASTILLAS = {
  REFERENCIA_VACIA: { texto: 'Sin referencia', clase: 'peligro' },
  REFERENCIA_REPETIDA: { texto: 'Repetida', clase: 'peligro' },
  REFERENCIA_INEXISTENTE: { texto: 'Sin referencia', clase: 'peligro' },
  COSTO_NO_NUMERICO: { texto: 'Costo inválido', clase: 'peligro' },
  COSTO_NO_POSITIVO: { texto: 'Costo inválido', clase: 'peligro' },
}

/**
 * Qué hacer con cada clase de rechazo. El motivo dice qué falló en esa
 * fila; esto dice cómo arreglarlo, que es lo que quien corrige el
 * archivo necesita. Un código que el front no conozca cae en la
 * indicación general: nunca queda un error sin salida.
 */
const QUE_HACER = {
  REFERENCIA_VACIA: 'Completa la referencia de esa fila en el archivo.',
  REFERENCIA_REPETIDA: 'Deja una sola fila por referencia.',
  REFERENCIA_INEXISTENTE: 'Corrige la referencia o registra el producto en el catálogo.',
  COSTO_NO_NUMERICO: 'Corrige el costo: solo números, sin símbolos ni texto.',
  COSTO_NO_POSITIVO: 'Corrige el costo: tiene que ser mayor que cero.',
}
const QUE_HACER_GENERAL = 'Corrige la fila en el archivo y vuelve a subirlo.'

const PASOS = ['Cargar archivo', 'Revisar', 'Aplicar']

/** El estado de la carga en palabras; el código del backend no se muestra. */
const TEXTO_ESTADO = {
  CARGADA: 'Cargada',
  VALIDADA: 'Validada',
  APLICADA: 'Aplicada',
  RECHAZADA: 'Descartada',
}

/**
 * Cuánto sube o baja el costo, en porcentaje.
 *
 * Es la columna que hace útil la vista previa. Un costo que pasa de
 * 2.100.000 a 2.180.000 no dice nada mirándolo; «+3,8 %» sí, y un
 * «+380 %» en una fila salta a la vista entre cuatrocientas correctas,
 * que es exactamente el error de archivo que nadie detecta leyendo
 * cifras.
 */
function variacion(anterior, nuevo) {
  if (anterior == null || nuevo == null || Number(anterior) === 0) return null
  return ((Number(nuevo) - Number(anterior)) / Number(anterior)) * 100
}

/**
 * A partir de cuánto una variación merece color.
 *
 * Pintar de rojo toda subida deja la columna entera en rojo —los costos
 * suben casi siempre— y entonces el color no señala nada: se aprende a
 * no mirarlo. Lo que hay que ver de un vistazo entre cuatrocientas filas
 * correctas es la que se movió de más, que casi siempre es un error del
 * archivo y no del proveedor.
 */
const VARIACION_LLAMATIVA = 20

function Variacion({ anterior, nuevo }) {
  const v = variacion(anterior, nuevo)
  if (v == null) return <>—</>
  const llamativa = Math.abs(v) >= VARIACION_LLAMATIVA
  return (
    <span className={llamativa ? (v > 0 ? 'variacion-sube' : 'variacion-baja') : undefined}>
      {porcentaje(v, { decimales: 1, conSigno: true })}
    </span>
  )
}

/**
 * «1 referencia pasará a causar IVA» y «2 referencias dejarán de causar IVA»,
 * las dos juntas si hay cruces en ambos sentidos.
 */
function textoCruces(aGravadas, aExcluidas) {
  const frases = []
  if (aGravadas > 0) {
    frases.push(aGravadas === 1
      ? '1 referencia pasará a causar IVA'
      : `${NUMERO.format(aGravadas)} referencias pasarán a causar IVA`)
  }
  if (aExcluidas > 0) {
    frases.push(aExcluidas === 1
      ? '1 referencia dejará de causar IVA'
      : `${NUMERO.format(aExcluidas)} referencias dejarán de causar IVA`)
  }
  return frases.join(' y ')
}

const columnasVistaPrevia = (cruces) => [
  {
    clave: 'referencia', titulo: 'Referencia del proveedor', principal: true,
    render: (f) => (
      <>
        {f.referencia || '—'}
        {f.numeroFila && <span className="sub-linea">fila {f.numeroFila}</span>}
      </>
    ),
  },
  {
    clave: 'descripcion', titulo: 'Producto',
    render: (f) => (
      <>
        {/* Una fila rechazada por referencia inexistente no tiene
            producto, y el guion que marcaría su ausencia sobra cuando
            justo debajo va el motivo explicando por qué no lo hay. */}
        {f.descripcion || (f.motivoRechazo ? '' : '—')}
        {/* El motivo completo va bajo el producto y no dentro de la
            pastilla: la pastilla dice de qué clase es el problema, esto
            dice cuál fue y lo de abajo qué hacer, que es lo que se usa
            para corregir el archivo. */}
        {f.motivoRechazo && <span className="sub-linea">{f.motivoRechazo}</span>}
        {!f.aceptado && (
          <span className="sub-linea que-hacer">
            Qué hacer: {QUE_HACER[f.motivoCodigo] ?? QUE_HACER_GENERAL}
          </span>
        )}
      </>
    ),
  },
  {
    clave: 'costoAnterior', titulo: 'Costo actual', numerica: true,
    render: (f) => (f.costoAnterior == null ? '—' : costoEnPesos(f.costoAnterior)),
  },
  {
    clave: 'costo', titulo: 'Costo nuevo', numerica: true,
    render: (f) => (f.costo == null ? '—' : costoEnPesos(f.costo)),
  },
  {
    clave: 'variacion', titulo: 'Variación', numerica: true,
    render: (f) => <Variacion anterior={f.costoAnterior} nuevo={f.costo} />,
  },
  {
    clave: 'resultado', titulo: 'Resultado',
    render: (f) => {
      const cruza = cruces.some((c) => c.productoId === f.productoId)
      const pastilla = f.aceptado
        ? (cruza
          ? { texto: 'Cruza el umbral', clase: 'critico' }
          : { texto: 'Aceptado', clase: 'activo' })
        : (PASTILLAS[f.motivoCodigo] ?? { texto: 'Rechazado', clase: 'peligro' })
      return <span className={`insignia ${pastilla.clase}`}>{pastilla.texto}</span>
    },
  },
]

/**
 * Pantalla P-07 · Importación de lista de precios.
 *
 * Solo el administrador: el controlador de importaciones responde 403 a
 * cualquier otro rol, así que la pantalla lo dice de entrada en lugar de
 * ofrecer un formulario que siempre fallaría.
 */
export default function Importacion() {
  if (!esAdministrador()) {
    return (
      <div className="contenido">
        <EncabezadoPagina titulo="Importar precios" />
        <EstadoSinPermiso />
      </div>
    )
  }
  return <ImportacionAdministrador />
}

function ImportacionAdministrador() {
  const lista = useDatos(() => api.proveedores())
  const proveedores = lista.datos ?? []
  const [proveedor, setProveedor] = useState('')
  const [carga, setCarga] = useState(null)
  // El problema y, aparte, qué hacer: un error que solo dice qué falló
  // deja a quien lo lee sin saber cómo seguir.
  const [fallo, setFallo] = useState(null)
  const [trabajando, setTrabajando] = useState(false)
  const [confirmando, setConfirmando] = useState(false)
  const [arrastrando, setArrastrando] = useState(false)
  const volver = useRef(null)
  const botonAplicar = useRef(null)
  const cerradaPorUsuario = useRef(false)
  // Guarda síncrona contra el doble envío: `trabajando` es estado y solo
  // cambia en el siguiente render, de modo que dos eventos seguidos
  // podrían pasar antes de que el botón se deshabilite.
  const enCurso = useRef(false)

  // La opción segura recibe el foco al abrir la confirmación: un Enter
  // distraído no debe cambiar todos los precios. Al cerrarla sin aplicar,
  // el foco vuelve al botón que la abrió.
  useEffect(() => {
    if (confirmando) {
      volver.current?.focus()
    } else if (cerradaPorUsuario.current) {
      cerradaPorUsuario.current = false
      botonAplicar.current?.focus()
    }
  }, [confirmando])

  function cerrarConfirmacion() {
    cerradaPorUsuario.current = true
    setConfirmando(false)
  }

  async function subir(archivo) {
    if (!archivo || enCurso.current) return
    if (!proveedor) {
      setFallo({
        mensaje: 'Elige primero de qué proveedor es la lista.',
        queHacer: 'Escoge el proveedor en la lista y vuelve a subir el archivo.',
      })
      return
    }
    setFallo(null)
    enCurso.current = true
    setTrabajando(true)
    try {
      setCarga(await api.cargarLista(proveedor, archivo))
    } catch (e) {
      // El mensaje del servidor es el que sirve: dice si fue el formato,
      // el tamaño, el número de filas o el encabezado.
      setFallo({ mensaje: e.message, queHacer: 'Corrige el archivo y vuelve a subirlo.' })
    } finally {
      enCurso.current = false
      setTrabajando(false)
    }
  }

  async function decidir(accion, queHacer) {
    if (enCurso.current) return
    setFallo(null)
    enCurso.current = true
    setTrabajando(true)
    try {
      setCarga(await accion(carga.cargaId))
      setConfirmando(false)
    } catch (e) {
      setFallo({ mensaje: e.message, queHacer })
    } finally {
      enCurso.current = false
      setTrabajando(false)
    }
  }

  const estado = carga?.estado
  const paso = !carga ? 0 : estado === 'VALIDADA' ? 1 : 2
  const cruces = carga?.cruces ?? []
  const aGravadas = cruces.filter((c) => c.tratamientoDespues === 'GRAVADO')
  const aExcluidas = cruces.length - aGravadas.length
  const decidible = estado === 'VALIDADA'

  function cuerpo() {
    if (lista.cargando && !lista.datos) return <EstadoCarga mensaje="Cargando los proveedores…" />
    if (lista.sinPermiso) return <EstadoSinPermiso />
    if (lista.error && !carga) {
      return (
        <EstadoError error="No se pudieron cargar los proveedores."
                     alReintentar={lista.recargar} />
      )
    }
    if (!carga && proveedores.length === 0) {
      return (
        <EstadoVacio
          titulo="Todavía no hay proveedores"
          detalle="Para importar una lista hace falta registrar antes de qué proveedor viene."
          accion={<Link to="/proveedores">Ir a Proveedores</Link>}
        />
      )
    }
    return null
  }
  const bloqueo = cuerpo()

  return (
    <div className="contenido importacion">
      <EncabezadoPagina
        titulo="Importar precios"
        descripcion="Sube la lista de costos de un proveedor, revisa el resultado y decide si aplicarla."
      />

      {/* El indicador de tres pasos. La P-07 lo pide y no es adorno:
          su trabajo es dejar claro en todo momento que leer no cambia
          el catálogo y que solo aplicar lo hace. */}
      <ol className="pasos" aria-label="Progreso de la importación">
        {PASOS.map((nombre, i) => (
          <li key={nombre} className={i === paso ? 'actual' : i < paso ? 'hecho' : undefined}
              aria-current={i === paso ? 'step' : undefined}>
            <span className="pasos-numero">{i < paso ? '✓' : i + 1}</span>
            {nombre}
          </li>
        ))}
        {estado && <li className="pasos-estado">{TEXTO_ESTADO[estado] ?? estado}</li>}
      </ol>

      {fallo && (
        <div className="aviso error" role="alert">
          <strong>{fallo.mensaje}</strong>
          <span className="sub-linea">{fallo.queHacer}</span>
        </div>
      )}

      {bloqueo}

      {!bloqueo && !carga && (
        <div className="tarjeta">
          <label className="campo">
            <span>Proveedor</span>
            <select value={proveedor} onChange={(e) => setProveedor(e.target.value)}>
              <option value="">Elige uno</option>
              {proveedores.map((p) => (
                <option key={p.id} value={p.id}>{p.razonSocial ?? p.nombre}</option>
              ))}
            </select>
          </label>

          {/* El control de archivo nativo se oculta y la etiqueta hace
              de botón. No es por estética: su texto lo pone el
              navegador en el idioma del sistema —«Choose File» en uno
              en inglés— y no hay forma de traducirlo. La etiqueta
              además es la zona de arrastre que pide el diseño de la
              P-07. */}
          <label
            className={`zona-archivo${arrastrando ? ' arrastrando' : ''}`}
            onDragOver={(e) => { e.preventDefault(); setArrastrando(true) }}
            onDragLeave={() => setArrastrando(false)}
            onDrop={(e) => {
              e.preventDefault()
              setArrastrando(false)
              // El control está deshabilitado mientras se lee, pero soltar
              // un archivo no pasa por él.
              if (trabajando) return
              subir(e.dataTransfer.files?.[0])
            }}
          >
            <span className="campo-rotulo">Archivo de lista de precios</span>
            <input
              type="file"
              accept=".xlsx,.csv"
              disabled={trabajando}
              className="oculto-visualmente"
              onChange={(e) => subir(e.target.files?.[0])}
            />
            <strong>{trabajando ? 'Leyendo el archivo…' : 'Elegir archivo'}</strong>
            <span className="sub-linea">o arrástralo aquí</span>
          </label>
          <p className="leyenda">
            Se admiten .xlsx y .csv de hasta 10 MB y {NUMERO.format(20000)} filas. El archivo se lee
            y se revisa; el catálogo no cambia hasta que lo apliques.
          </p>
        </div>
      )}

      {carga && (
        <>
          <div className="tarjetas-cifra importacion-cifras">
            <div className="tarjeta cifra">
              <span className="cifra-rotulo">Registros leídos</span>
              <strong className="cifra-valor">{NUMERO.format(carga.leidos)}</strong>
              <span className="cifra-pie">{carga.nombreArchivo}</span>
            </div>
            <div className="tarjeta cifra">
              <span className="cifra-rotulo">Aceptados</span>
              <strong className="cifra-valor exito">{NUMERO.format(carga.aceptados)}</strong>
              <span className="cifra-pie">
                {carga.leidos > 0
                  ? `${porcentaje((carga.aceptados / carga.leidos) * 100, { decimales: 1 })} del archivo`
                  : '—'}
              </span>
            </div>
            <div className="tarjeta cifra">
              <span className="cifra-rotulo">Rechazados</span>
              <strong className="cifra-valor">{NUMERO.format(carga.rechazados)}</strong>
              <span className="cifra-pie">Con motivo registrado</span>
            </div>
            <div className="tarjeta cifra">
              <span className="cifra-rotulo">Cruzan umbral</span>
              <strong className="cifra-valor aviso-texto">{NUMERO.format(cruces.length)}</strong>
              <span className="cifra-pie">Cambian de tratamiento de IVA</span>
            </div>
          </div>

          {/* Qué pasa con las filas que no entran y qué opciones hay.
              Va antes de la tabla: es lo que decide si se aplica ya o se
              corrige primero. */}
          {estado === 'VALIDADA' && carga.rechazados > 0 && (
            <div className="aviso" role="status">
              <strong>
                {carga.rechazados === 1
                  ? '1 fila no se aplicará.'
                  : `${NUMERO.format(carga.rechazados)} filas no se aplicarán.`}
              </strong>
              <span className="sub-linea">
                {carga.rechazados === 1 ? 'Dice' : 'Cada una dice'} abajo qué falló y qué hacer.
                {' '}{carga.rechazados === 1 ? 'Corrígela' : 'Corrígelas'} en el archivo y
                vuelve a subirlo, o aplica solo {carga.aceptados === 1
                  ? 'la aceptada'
                  : `las ${NUMERO.format(carga.aceptados)} aceptadas`}.
              </span>
            </div>
          )}

          <div className="tarjeta">
            <div className="panel-cabecera">
              <h2>Vista previa</h2>
              {estado === 'VALIDADA' && (
                <span className="peligro-texto">El catálogo aún no cambia</span>
              )}
            </div>

            <TablaOTarjetas
              etiqueta="Filas leídas de la lista"
              filas={carga.muestra}
              claveFila={(f) => `${f.numeroFila}-${f.referencia}`}
              columnas={columnasVistaPrevia(cruces)}
            />

            {carga.leidos > carga.muestra.length && (
              <p className="leyenda">
                Se muestran {NUMERO.format(carga.muestra.length)} de {NUMERO.format(carga.leidos)} registros, los
                rechazados primero. Al aplicar {carga.aceptados === 1
                  ? 'entra el 1 aceptado'
                  : `entran los ${NUMERO.format(carga.aceptados)} aceptados`}.
              </p>
            )}
          </div>

          {/* El aviso del umbral. Va aparte y en ámbar porque no es un
              error del archivo: es una consecuencia de los costos
              nuevos que quien decide tiene que ver antes de decidir. */}
          {cruces.length > 0 && estado === 'VALIDADA' && (
            <div className="aviso" role="status">
              <strong>
                {aGravadas.length > 0 && (aGravadas.length === 1
                  ? 'Una referencia pasaría a causar IVA'
                  : `${aGravadas.length} referencias pasarían a causar IVA`)}
                {aGravadas.length > 0 && aExcluidas > 0 && '; '}
                {aExcluidas > 0 && (aExcluidas === 1
                  ? 'una dejaría de causarlo'
                  : `${aExcluidas} dejarían de causarlo`)}
              </strong>
              <span className="sub-linea">
                {cruces.map((c) => c.nombre || c.codigo).join(', ')}{' '}
                {cruces.length === 1 ? 'cruza' : 'cruzan'} el umbral
                del artículo 424 con el costo nuevo.
              </span>
            </div>
          )}

          {estado === 'APLICADA' && (
            <div className="aviso exito" role="status">
              <strong>
                {carga.aceptados === 1
                  ? '1 referencia actualizada'
                  : `${NUMERO.format(carga.aceptados)} referencias actualizadas`}
              </strong>
              <span className="sub-linea">
                Los precios por porcentaje ya salen del costo nuevo. Los precios
                escritos se conservan y cambia su ganancia.
              </span>
            </div>
          )}

          {estado === 'RECHAZADA' && (
            <div className="aviso" role="status">
              <strong>Carga descartada</strong>
              <span className="sub-linea">
                Ningún costo cambió. El detalle queda registrado para poder
                reclamarle al proveedor.
              </span>
            </div>
          )}

          {decidible && !confirmando && (
            <div className="acciones-pie">
              <button className="boton secundario" disabled={trabajando}
                      onClick={() => decidir(api.rechazarCarga,
                        'La carga sigue pendiente. Vuelve a intentarlo.')}>
                Rechazar carga
              </button>
              <button ref={botonAplicar} className="boton peligro"
                      disabled={trabajando || carga.aceptados === 0}
                      onClick={() => setConfirmando(true)}>
                Aplicar al catálogo
              </button>
            </div>
          )}

          {/* «El botón de aplicar exige confirmación explícita», dice la
              especificación de la P-07. Lo que se confirma no es la
              acción sino su alcance: cuántas referencias y cuántas
              cambian de tratamiento tributario. Escape la cierra. */}
          {decidible && confirmando && (
            <div className="aviso error" role="alertdialog"
                 aria-label="Confirmar aplicación al catálogo"
                 onKeyDown={(e) => { if (e.key === 'Escape') cerrarConfirmacion() }}>
              <strong>
                {carga.aceptados === 1
                  ? 'Se va a actualizar 1 costo del catálogo'
                  : `Se van a actualizar ${NUMERO.format(carga.aceptados)} costos del catálogo`}
                {cruces.length > 0 && `, y ${textoCruces(aGravadas.length, aExcluidas)}`}.
              </strong>
              <span className="sub-linea">
                Es una operación de todo o nada y no se deshace sola: para volver
                atrás hay que cargar la lista anterior.
              </span>
              <div className="acciones-pie">
                <button ref={volver} className="boton secundario" disabled={trabajando}
                        onClick={cerrarConfirmacion}>
                  Volver a revisar
                </button>
                <button className="boton peligro" disabled={trabajando}
                        onClick={() => decidir(api.aplicarCarga,
                          'No se cambió ningún precio. Vuelve a intentarlo; si sigue fallando, '
                          + 'descarta la carga y súbela de nuevo.')}>
                  {trabajando ? 'Aplicando…' : 'Sí, aplicar al catálogo'}
                </button>
              </div>
            </div>
          )}

          {estado !== 'VALIDADA' && (
            <div className="acciones-pie">
              <button className="boton" onClick={() => { setCarga(null); setFallo(null) }}>
                Importar otra lista
              </button>
            </div>
          )}
        </>
      )}
    </div>
  )
}

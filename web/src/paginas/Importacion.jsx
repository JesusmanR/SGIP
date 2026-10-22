import { useEffect, useState } from 'react'
import Barra from '../componentes/Barra.jsx'
import { api } from '../servicios/api.js'
import { leerSesion } from '../servicios/sesion.js'

const PESOS = new Intl.NumberFormat('es-CO', {
  style: 'currency', currency: 'COP', maximumFractionDigits: 0,
})

/** Las categorías que la V30 guarda, con su texto y su color. */
const PASTILLAS = {
  REFERENCIA_VACIA: { texto: 'Sin referencia', clase: 'peligro' },
  REFERENCIA_REPETIDA: { texto: 'Repetida', clase: 'peligro' },
  REFERENCIA_INEXISTENTE: { texto: 'Sin referencia', clase: 'peligro' },
  COSTO_NO_NUMERICO: { texto: 'Costo inválido', clase: 'peligro' },
  COSTO_NO_POSITIVO: { texto: 'Costo inválido', clase: 'peligro' },
}

const PASOS = ['Cargar archivo', 'Revisar', 'Aplicar']

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
  const signo = v > 0 ? '+' : ''
  return (
    <span className={llamativa ? (v > 0 ? 'sube' : 'baja') : undefined}>
      {signo}{v.toFixed(1).replace('.', ',')} %
    </span>
  )
}

/** Pantalla P-07 · Importación de lista de precios. */
export default function Importacion() {
  const [proveedores, setProveedores] = useState([])
  const [proveedor, setProveedor] = useState('')
  const [carga, setCarga] = useState(null)
  const [error, setError] = useState(null)
  const [trabajando, setTrabajando] = useState(false)
  const [confirmando, setConfirmando] = useState(false)
  const [arrastrando, setArrastrando] = useState(false)
  const esAdministrador = leerSesion()?.rol === 'ADMINISTRADOR'

  useEffect(() => {
    api.proveedores().then(setProveedores).catch(() => {
      setError('No se pudieron cargar los proveedores.')
    })
  }, [])

  async function subir(archivo) {
    if (!archivo) return
    if (!proveedor) {
      setError('Elige primero de qué proveedor es la lista.')
      return
    }
    setError(null)
    setTrabajando(true)
    try {
      setCarga(await api.cargarLista(proveedor, archivo))
    } catch (e) {
      // El mensaje del servidor es el que sirve: dice si fue el formato,
      // el tamaño, el número de filas o el encabezado.
      setError(e.message)
    } finally {
      setTrabajando(false)
    }
  }

  async function decidir(accion) {
    setError(null)
    setTrabajando(true)
    try {
      setCarga(await accion(carga.cargaId))
      setConfirmando(false)
    } catch (e) {
      setError(e.message)
    } finally {
      setTrabajando(false)
    }
  }

  const estado = carga?.estado
  const paso = !carga ? 0 : estado === 'VALIDADA' ? 1 : 2
  const cruces = carga?.cruces ?? []
  const decidible = estado === 'VALIDADA' && esAdministrador

  return (
    <>
      <Barra />
      <main className="contenido">
        <h2>Importar precios</h2>

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
          {estado && <li className="pasos-estado">{estado}</li>}
        </ol>

        {error && <div className="aviso error" role="alert">{error}</div>}

        {!carga && (
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
                en inglés— y no hay forma de traducirlo. Una pantalla en
                español con un botón en inglés en su acción principal es
                lo primero que se nota. La etiqueta además es la zona de
                arrastre que pide el diseño de la P-07. */}
            <label
              className={`zona-archivo${arrastrando ? ' arrastrando' : ''}`}
              onDragOver={(e) => { e.preventDefault(); setArrastrando(true) }}
              onDragLeave={() => setArrastrando(false)}
              onDrop={(e) => {
                e.preventDefault()
                setArrastrando(false)
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
              Se admiten .xlsx y .csv de hasta 10 MB y 20 000 filas. El archivo se lee
              y se revisa; el catálogo no cambia hasta que lo apliques.
            </p>
          </div>
        )}

        {carga && (
          <>
            <div className="tarjetas-cifra">
              <div className="tarjeta cifra">
                <span className="cifra-rotulo">Registros leídos</span>
                <strong className="cifra-valor">{carga.leidos}</strong>
                <span className="cifra-pie">{carga.nombreArchivo}</span>
              </div>
              <div className="tarjeta cifra">
                <span className="cifra-rotulo">Aceptados</span>
                <strong className="cifra-valor exito">{carga.aceptados}</strong>
                <span className="cifra-pie">
                  {carga.leidos > 0
                    ? `${((carga.aceptados / carga.leidos) * 100).toFixed(1)
                      .replace('.', ',')} % del archivo`
                    : '—'}
                </span>
              </div>
              <div className="tarjeta cifra">
                <span className="cifra-rotulo">Rechazados</span>
                <strong className="cifra-valor">{carga.rechazados}</strong>
                <span className="cifra-pie">Con motivo registrado</span>
              </div>
              <div className="tarjeta cifra">
                <span className="cifra-rotulo">Cruzan umbral</span>
                <strong className="cifra-valor aviso-texto">{cruces.length}</strong>
                <span className="cifra-pie">Pasan a gravadas</span>
              </div>
            </div>

            <div className="tarjeta">
              <div className="panel-cabecera">
                <h3>Vista previa</h3>
                {estado === 'VALIDADA' && (
                  <span className="peligro-texto">El catálogo aún no cambia</span>
                )}
              </div>

              <div className="tabla-ancha">
                <table>
                  <thead>
                    <tr>
                      <th>Referencia del proveedor</th>
                      <th>Producto</th>
                      <th className="numero">Costo actual</th>
                      <th className="numero">Costo nuevo</th>
                      <th className="numero">Variación</th>
                      <th>Resultado</th>
                    </tr>
                  </thead>
                  <tbody>
                    {carga.muestra.map((f) => {
                      const cruza = cruces.some((c) => c.productoId === f.productoId)
                      const pastilla = f.aceptado
                        ? (cruza
                          ? { texto: 'Cruza el umbral', clase: 'critico' }
                          : { texto: 'Aceptado', clase: 'activo' })
                        : (PASTILLAS[f.motivoCodigo]
                          ?? { texto: 'Rechazado', clase: 'peligro' })
                      return (
                        <tr key={`${f.numeroFila}-${f.referencia}`}>
                          <td>
                            {f.referencia || '—'}
                            {f.numeroFila && (
                              <span className="sub-linea">fila {f.numeroFila}</span>
                            )}
                          </td>
                          <td>
                            {/* Una fila rechazada por referencia inexistente
                                no tiene producto, y el guion que marcaría
                                su ausencia sobra cuando justo debajo va el
                                motivo explicando por qué no lo hay. */}
                            {f.descripcion || (f.motivoRechazo ? '' : '—')}
                            {/* El motivo completo va bajo el producto y no
                                dentro de la pastilla: la pastilla dice de
                                qué clase es el problema y esto dice cuál
                                fue, que es lo que se usa para corregir el
                                archivo. */}
                            {f.motivoRechazo && (
                              <span className="sub-linea">{f.motivoRechazo}</span>
                            )}
                          </td>
                          <td className="numero">
                            {f.costoAnterior == null ? '—' : PESOS.format(f.costoAnterior)}
                          </td>
                          <td className="numero">
                            {f.costo == null ? '—' : PESOS.format(f.costo)}
                          </td>
                          <td className="numero">
                            <Variacion anterior={f.costoAnterior} nuevo={f.costo} />
                          </td>
                          <td>
                            <span className={`insignia ${pastilla.clase}`}>
                              {pastilla.texto}
                            </span>
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>

              {carga.leidos > carga.muestra.length && (
                <p className="leyenda">
                  Se muestran {carga.muestra.length} de {carga.leidos} registros, los
                  rechazados primero. Al aplicar entran los {carga.aceptados} aceptados.
                </p>
              )}
            </div>

            {/* El aviso del umbral. Va aparte y en ámbar porque no es un
                error del archivo: es una consecuencia de los costos
                nuevos que quien decide tiene que ver antes de decidir. */}
            {cruces.length > 0 && estado === 'VALIDADA' && (
              <div className="aviso" role="status">
                <strong>
                  {cruces.length === 1
                    ? 'Una referencia pasaría a causar IVA'
                    : `${cruces.length} referencias pasarían a causar IVA`}
                </strong>
                <span className="sub-linea">
                  {cruces.map((c) => c.nombre || c.codigo).join(', ')} superan el umbral
                  del artículo 424 con el costo nuevo.
                </span>
              </div>
            )}

            {estado === 'APLICADA' && (
              <div className="aviso exito" role="status">
                <strong>{carga.aceptados} referencias actualizadas</strong>
                <span className="sub-linea">
                  Los precios de venta ya salen del costo nuevo.
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
                        onClick={() => decidir(api.rechazarCarga)}>
                  Rechazar carga
                </button>
                <button className="boton peligro" disabled={trabajando || carga.aceptados === 0}
                        onClick={() => setConfirmando(true)}>
                  Aplicar al catálogo
                </button>
              </div>
            )}

            {/* «El botón de aplicar exige confirmación explícita», dice la
                especificación de la P-07. Lo que se confirma no es la
                acción sino su alcance: cuántas referencias y cuántas
                cambian de tratamiento tributario. */}
            {decidible && confirmando && (
              <div className="aviso error" role="alertdialog">
                <strong>
                  Se van a actualizar {carga.aceptados} costos del catálogo
                  {cruces.length > 0 && `, y ${cruces.length} referencia`}
                  {cruces.length > 1 && 's'}
                  {cruces.length > 0 && ' pasarán a causar IVA'}.
                </strong>
                <span className="sub-linea">
                  Es una operación de todo o nada y no se deshace sola: para volver
                  atrás hay que cargar la lista anterior.
                </span>
                <div className="acciones-pie">
                  <button className="boton secundario" disabled={trabajando}
                          onClick={() => setConfirmando(false)}>
                    Volver a revisar
                  </button>
                  <button className="boton peligro" disabled={trabajando}
                          onClick={() => decidir(api.aplicarCarga)}>
                    {trabajando ? 'Aplicando…' : 'Sí, aplicar al catálogo'}
                  </button>
                </div>
              </div>
            )}

            {estado !== 'VALIDADA' && (
              <div className="acciones-pie">
                <button className="boton" onClick={() => { setCarga(null); setError(null) }}>
                  Importar otra lista
                </button>
              </div>
            )}
          </>
        )}
      </main>
    </>
  )
}

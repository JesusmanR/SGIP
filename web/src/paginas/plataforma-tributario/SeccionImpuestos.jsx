import { useEffect, useRef, useState } from 'react'
import Aviso from '../../componentes/comun/Aviso.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import EstadoVacio from '../../componentes/comun/EstadoVacio.jsx'
import TablaOTarjetas from '../../componentes/comun/TablaOTarjetas.jsx'
import { apiPlataformaTributario } from '../../servicios/api/plataforma-tributario.js'
import { NUMERO } from '../../servicios/formato.js'
import { erroresPorCampo, numeroEscrito, sinCampo } from './campos.js'
import Campo from '../../componentes/comun/Campo.jsx'

const CON_TOPE = 'EXCLUIDO_HASTA_UMBRAL'
const TRATAMIENTOS = {
  GRAVADO: 'Gravado',
  EXCLUIDO_HASTA_UMBRAL: 'Excluido hasta el tope',
  EXCLUIDO: 'Excluido',
}
const CAMPOS_DEL_SERVIDOR = ['tarifa', 'umbralUvt', 'fundamento', 'motivo']
const PORCENTAJE = new Intl.NumberFormat('es-CO', { maximumFractionDigits: 2 })
const DURO = String.fromCharCode(160)

/** La tarifa viaja como fracción (0,19); a la vista va como porcentaje. */
const comoPorcentaje = (tarifa) => `${PORCENTAJE.format(Number(tarifa) * 100)}${DURO}%`
const aNumero = (texto) => Number(String(texto).trim().replace(',', '.'))

// Los límites de SolicitudTipoImpuesto: la tarifa, una fracción con 4 decimales; el tope, 6 enteros y 2 decimales.
const MENSAJE_TARIFA = 'La tarifa va entre 0 y 1: por ejemplo 0,19 para el 19 %.'

function motivoDeLaTarifa(texto) {
  const numero = numeroEscrito(texto)
  if (!numero || numero.valor > 1) return MENSAJE_TARIFA
  if (numero.decimales > 4) return 'La tarifa admite como máximo 4 decimales: por ejemplo 0,1925.'
  return null
}

function motivoDelTope(texto) {
  const numero = numeroEscrito(texto)
  if (!numero || !(numero.valor >= 0.01)) return 'Escribe el tope en UVT, mayor que cero.'
  if (numero.decimales > 2) return 'El tope admite como máximo 2 decimales.'
  if (numero.enteros > 6) return 'El tope admite hasta 6 cifras enteras.'
  return null
}

function motivos(d, conTope) {
  return {
    tarifa: motivoDeLaTarifa(d.tarifa),
    umbralUvt: conTope ? motivoDelTope(d.umbralUvt) : null,
    fundamento: d.fundamento.length > 160 ? 'El fundamento no puede pasar de 160 caracteres.' : null,
    motivo: d.motivo.length > 200 ? 'El motivo no puede pasar de 200 caracteres.' : null,
  }
}

/**
 * Edita tarifa, tope y fundamento de un tipo. El código y el tratamiento no se
 * tocan: el servidor no lo permite. Guardar pide confirmación, porque el
 * catálogo es de todas las tiendas y el cambio rige al instante.
 */
function EditarImpuesto({ impuesto, alGuardar, alCancelar }) {
  const conTope = impuesto.tratamiento === CON_TOPE
  const [form, setForm] = useState({
    // La coma, como dice la ayuda y como se escribe en Colombia.
    tarifa: String(impuesto.tarifa ?? '').replace('.', ','), umbralUvt: String(impuesto.umbralUvt ?? ''),
    fundamento: impuesto.fundamento ?? '', motivo: '',
  })
  const [intentos, setIntentos] = useState(0)
  const [confirmando, setConfirmando] = useState(false)
  const [volverAlBoton, setVolverAlBoton] = useState(false)
  const [enviando, setEnviando] = useState(false)
  const [error, setError] = useState(null)
  const [camposServidor, setCamposServidor] = useState({})
  const formulario = useRef(null)
  const caja = useRef(null)
  const botonGuardar = useRef(null)
  const enCurso = useRef(false)

  const fallos = motivos(form, conTope)
  // Lo que dijo el servidor de un campo va primero y se quita al editar ese campo.
  const ver = (campo) => camposServidor[campo] ?? (intentos > 0 ? fallos[campo] : null)

  useEffect(() => {
    formulario.current?.querySelector('input')?.focus()
  }, [])
  useEffect(() => {
    if (intentos > 0) formulario.current?.querySelector('[aria-invalid="true"]')?.focus()
  }, [intentos])
  useEffect(() => {
    if (confirmando) caja.current?.focus()
  }, [confirmando])
  // El foco vuelve al botón cuando la confirmación ya se cerró y el botón ya está en pantalla.
  useEffect(() => {
    if (confirmando || !volverAlBoton) return
    botonGuardar.current?.focus()
    setVolverAlBoton(false)
  }, [confirmando, volverAlBoton])

  const cambiar = (campo) => (e) => {
    const valor = e.target.value
    setForm((f) => ({ ...f, [campo]: valor }))
    setCamposServidor((previos) => sinCampo(previos, campo))
  }

  function pedirConfirmacion(e) {
    e.preventDefault()
    setIntentos((n) => n + 1)
    setError(null)
    setCamposServidor({})
    if (Object.values(fallos).some(Boolean)) return
    setConfirmando(true)
  }

  function volverAEditar() {
    setVolverAlBoton(true)
    setConfirmando(false)
  }

  async function guardar() {
    if (enCurso.current) return
    enCurso.current = true
    setEnviando(true)
    try {
      await apiPlataformaTributario.cambiarImpuesto(impuesto.codigo, {
        tarifa: aNumero(form.tarifa),
        umbralUvt: conTope ? aNumero(form.umbralUvt) : null,
        fundamento: form.fundamento.trim() || null,
        motivo: form.motivo.trim() || null,
      })
      alGuardar(`${impuesto.nombre} guardado.`)
    } catch (err) {
      const delCampo = err.estado === 400 ? erroresPorCampo(err.message, CAMPOS_DEL_SERVIDOR) : null
      if (delCampo) {
        setCamposServidor(delCampo)
        setIntentos((n) => n + 1)
      } else {
        setError(err.message || 'No se pudo completar la operación.')
        setVolverAlBoton(true)
      }
      setConfirmando(false)
      enCurso.current = false
      setEnviando(false)
    }
  }

  return (
    <form
      className="tarjeta formulario-tributario" onSubmit={pedirConfirmacion} noValidate ref={formulario}
      aria-label={`Editar ${impuesto.nombre}`}
    >
      <h3>Editar {impuesto.nombre}</h3>
      <p className="nota">
        Cambia lo que rige para todo el país. El código y el tratamiento no se cambian aquí.
      </p>
      {error && <Aviso tipo="peligro">{error}</Aviso>}
      <fieldset className="campos" disabled={confirmando}>
        <Campo etiqueta="Tarifa (como fracción)" motivo={ver('tarifa')} ayuda="0,19 es el 19 %; 0 es sin impuesto.">
          <input type="text" inputMode="decimal" autoComplete="off" value={form.tarifa} onChange={cambiar('tarifa')} />
        </Campo>
        {conTope && (
          <Campo etiqueta="Tope en UVT" motivo={ver('umbralUvt')} ayuda="Hasta este valor, en UVT, el producto queda excluido.">
            <input type="number" inputMode="decimal" step="any" autoComplete="off" value={form.umbralUvt} onChange={cambiar('umbralUvt')} />
          </Campo>
        )}
        <Campo etiqueta="Fundamento" motivo={ver('fundamento')} ayuda="Opcional. La norma en que se apoya.">
          <input type="text" autoComplete="off" value={form.fundamento} onChange={cambiar('fundamento')} />
        </Campo>
        <Campo etiqueta="Motivo" motivo={ver('motivo')} ayuda="Opcional. Queda en la bitácora de la plataforma.">
          <input type="text" autoComplete="off" value={form.motivo} onChange={cambiar('motivo')} />
        </Campo>
      </fieldset>
      {confirmando ? (
        <div className="confirmacion" role="group" aria-labelledby="impuesto-pregunta" tabIndex={-1} ref={caja}>
          <p id="impuesto-pregunta" className="confirmacion-pregunta">{`¿Guardar los cambios de ${impuesto.nombre}?`}</p>
          <p>
            Este cambio afecta a todas las tiendas y rige al instante, sin esperar a nada más.
          </p>
          <div className="acciones">
            <button type="button" className="boton" disabled={enviando} onClick={guardar}>Sí, guardar</button>
            <button type="button" className="boton secundario" disabled={enviando} onClick={volverAEditar}>
              Volver a editar
            </button>
          </div>
        </div>
      ) : (
        <div className="acciones">
          <button type="submit" className="boton" ref={botonGuardar}>Guardar impuesto</button>
          <button type="button" className="boton secundario" onClick={alCancelar}>Cancelar</button>
        </div>
      )}
    </form>
  )
}

/** Catálogo nacional de impuestos: se consulta y se edita; no se crean tipos nuevos. */
export default function SeccionImpuestos({ impuestos }) {
  const { datos, cargando, error, sinPermiso, recargar } = impuestos
  const [editando, setEditando] = useState(null)
  const [exito, setExito] = useState(null)
  const [foco, setFoco] = useState(null)
  const botones = useRef(new Map())

  // Al cerrar la edición, el foco vuelve al botón «Editar» de ese impuesto.
  useEffect(() => {
    if (editando || !foco) return
    botones.current.get(foco)?.focus()
    setFoco(null)
  }, [editando, foco])

  const cerrar = (codigo) => { setFoco(codigo); setEditando(null) }

  const columnas = [
    { clave: 'nombre', titulo: 'Impuesto', principal: true },
    { clave: 'tratamiento', titulo: 'Tratamiento', render: (i) => TRATAMIENTOS[i.tratamiento] ?? i.tratamiento },
    { clave: 'tarifa', titulo: 'Tarifa', numerica: true, render: (i) => comoPorcentaje(i.tarifa) },
    {
      clave: 'umbralUvt', titulo: 'Tope en UVT', numerica: true,
      render: (i) => (i.umbralUvt == null ? '—' : NUMERO.format(i.umbralUvt)),
    },
    { clave: 'fundamento', titulo: 'Fundamento', render: (i) => i.fundamento || '—' },
    {
      clave: 'acciones', titulo: 'Acciones',
      render: (i) => (
        <button
          type="button" className="boton secundario menudo"
          aria-label={`Editar ${i.nombre}`}
          ref={(el) => { if (el) botones.current.set(i.codigo, el); else botones.current.delete(i.codigo) }}
          onClick={() => { setExito(null); setEditando(i) }}
        >
          Editar
        </button>
      ),
    },
  ]

  let lista
  if (sinPermiso) lista = <EstadoSinPermiso />
  else if (error) lista = <EstadoError error={error} alReintentar={recargar} />
  else if (cargando && !datos) lista = <EstadoCarga mensaje="Cargando los impuestos…" />
  else if (datos.length === 0) {
    lista = <EstadoVacio titulo="Todavía no hay impuestos" detalle="El catálogo nacional está vacío." />
  } else {
    lista = (
      <TablaOTarjetas etiqueta="Impuestos nacionales" columnas={columnas} filas={datos} claveFila={(i) => i.tipoImpuestoId} />
    )
  }

  return (
    <section className="seccion-tributaria" aria-labelledby="titulo-impuestos">
      <h2 id="titulo-impuestos">Impuestos nacionales</h2>
      <p className="nota">
        Lo que rige igual para todas las tiendas. Cada tienda decide por su lado si cobra o no el impuesto.
      </p>
      {exito && <Aviso tipo="exito">{exito}</Aviso>}
      {lista}
      {editando && (
        <EditarImpuesto
          key={editando.codigo}
          impuesto={editando}
          alCancelar={() => cerrar(editando.codigo)}
          alGuardar={(mensaje) => { setExito(mensaje); cerrar(editando.codigo); recargar() }}
        />
      )}
    </section>
  )
}

import { useEffect, useRef, useState } from 'react'
import Aviso from '../../componentes/comun/Aviso.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import EstadoVacio from '../../componentes/comun/EstadoVacio.jsx'
import TablaOTarjetas from '../../componentes/comun/TablaOTarjetas.jsx'
import { apiPlataformaTributario } from '../../servicios/api/plataforma-tributario.js'
import { NUMERO } from '../../servicios/formato.js'
import Campo from './Campo.jsx'

const CON_TOPE = 'EXCLUIDO_HASTA_UMBRAL'
const TRATAMIENTOS = {
  GRAVADO: 'Gravado',
  EXCLUIDO_HASTA_UMBRAL: 'Excluido hasta el tope',
  EXCLUIDO: 'Excluido',
}
const PORCENTAJE = new Intl.NumberFormat('es-CO', { maximumFractionDigits: 2 })
const DURO = String.fromCharCode(160)

/** La tarifa viaja como fracción (0,19); a la vista va como porcentaje. */
const comoPorcentaje = (tarifa) => `${PORCENTAJE.format(Number(tarifa) * 100)}${DURO}%`
const aNumero = (texto) => Number(String(texto).trim().replace(',', '.'))

function motivos(d, conTope) {
  const tarifa = aNumero(d.tarifa)
  const tope = aNumero(d.umbralUvt)
  return {
    tarifa: String(d.tarifa).trim() === '' || !Number.isFinite(tarifa) || tarifa < 0 || tarifa > 1
      ? 'La tarifa va entre 0 y 1: por ejemplo 0,19 para el 19 %.' : null,
    umbralUvt: conTope && (String(d.umbralUvt).trim() === '' || !(tope > 0))
      ? 'Escribe el tope en UVT, mayor que cero.' : null,
    fundamento: d.fundamento.length > 160 ? 'El fundamento no puede pasar de 160 caracteres.' : null,
    motivo: d.motivo.length > 200 ? 'El motivo no puede pasar de 200 caracteres.' : null,
  }
}

/** Edita tarifa, tope y fundamento de un tipo. El código y el tratamiento no se tocan: el servidor no lo permite. */
function EditarImpuesto({ impuesto, alGuardar, alCancelar }) {
  const conTope = impuesto.tratamiento === CON_TOPE
  const [form, setForm] = useState({
    tarifa: String(impuesto.tarifa ?? ''), umbralUvt: String(impuesto.umbralUvt ?? ''),
    fundamento: impuesto.fundamento ?? '', motivo: '',
  })
  const [intentos, setIntentos] = useState(0)
  const [enviando, setEnviando] = useState(false)
  const [error, setError] = useState(null)
  const formulario = useRef(null)
  const enCurso = useRef(false)

  const fallos = motivos(form, conTope)
  const ver = (campo) => (intentos > 0 ? fallos[campo] : null)

  useEffect(() => {
    formulario.current?.querySelector('input')?.focus()
  }, [])
  useEffect(() => {
    if (intentos > 0) formulario.current?.querySelector('[aria-invalid="true"]')?.focus()
  }, [intentos])

  const cambiar = (campo) => (e) => {
    const valor = e.target.value
    setForm((f) => ({ ...f, [campo]: valor }))
  }

  async function enviar(e) {
    e.preventDefault()
    if (enCurso.current) return
    setIntentos((n) => n + 1)
    setError(null)
    if (Object.values(fallos).some(Boolean)) return

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
      setError(err.message || 'No se pudo completar la operación.')
      enCurso.current = false
      setEnviando(false)
    }
  }

  return (
    <form
      className="tarjeta formulario-tributario" onSubmit={enviar} noValidate ref={formulario}
      aria-label={`Editar ${impuesto.nombre}`}
    >
      <h3>Editar {impuesto.nombre}</h3>
      <p className="nota">
        Cambia lo que rige para todo el país. El código y el tratamiento no se cambian aquí.
      </p>
      {error && <Aviso tipo="peligro">{error}</Aviso>}
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
      <div className="acciones">
        <button type="submit" className="boton" disabled={enviando}>
          {enviando ? 'Guardando…' : 'Guardar impuesto'}
        </button>
        <button type="button" className="boton secundario" disabled={enviando} onClick={alCancelar}>Cancelar</button>
      </div>
    </form>
  )
}

/** Catálogo nacional de impuestos: se consulta y se edita; no se crean tipos nuevos. */
export default function SeccionImpuestos({ impuestos }) {
  const { datos, cargando, error, sinPermiso, recargar } = impuestos
  const [editando, setEditando] = useState(null)
  const [exito, setExito] = useState(null)

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
          aria-label={`Editar ${i.nombre}`} onClick={() => { setExito(null); setEditando(i) }}
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
          alCancelar={() => setEditando(null)}
          alGuardar={(mensaje) => { setEditando(null); setExito(mensaje); recargar() }}
        />
      )}
    </section>
  )
}

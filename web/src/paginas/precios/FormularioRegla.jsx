import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import Aviso from '../../componentes/comun/Aviso.jsx'
import EncabezadoPagina from '../../componentes/comun/EncabezadoPagina.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import TablaOTarjetas from '../../componentes/comun/TablaOTarjetas.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { apiPrecios } from '../../servicios/api/precios.js'
import { PESOS } from '../../servicios/formato.js'
import Campo from './Campo.jsx'
import { fechaDia, hoyISO, sumarDias } from './fechas.js'
import {
  etiquetaDePago, etiquetaDeTratamiento, factorAPorcentaje, FORMAS_CALCULO, FORMAS_PAGO,
  porcentajeAFactor, textoDeAlcance,
} from './reglas.js'

const TITULOS = {
  crear: 'Nueva regla de ganancia',
  corregir: 'Corregir la regla',
  reemplazar: 'Reemplazar la regla',
}

const aNumero = (texto) => (texto === '' ? null : Number(texto))

/** Por qué no se puede enviar lo escrito; cada campo con su mensaje, o `null`. */
function motivos(modo, regla, d, hoy) {
  const m = { porcentaje: porcentajeAFactor(d.porcentaje, d.formaCalculo).error ?? null, desde: null, hasta: null }
  if (d.desde === '') m.desde = 'Elige desde qué día rige.'
  else if (modo !== 'crear' && d.desde < hoy) m.desde = 'La regla no puede empezar antes de hoy.'
  else if (modo === 'reemplazar' && d.desde <= regla.vigenteDesde) {
    m.desde = `Tiene que empezar después del día en que empezó la actual (${fechaDia(regla.vigenteDesde)}).`
  } else if (modo === 'reemplazar' && regla.vigenteHasta && sumarDias(d.desde, -1) > regla.vigenteHasta) {
    // Cerrar la actual un día más tarde de su fin planeado la alargaría y podría solaparla con otra.
    m.desde = `La regla actual ya termina el ${fechaDia(regla.vigenteHasta)}: la nueva tiene que empezar a más tardar el ${fechaDia(sumarDias(regla.vigenteHasta, 1))}.`
  }
  if (d.hasta !== '' && d.desde !== '' && d.hasta < d.desde) {
    m.hasta = 'La fecha final no puede ser anterior a la inicial.'
  }
  return m
}

/** Lo que el backend recibe: con la regla ya creada, el alcance y la forma de pago no se mueven. */
function armarCuerpo(modo, regla, d) {
  const base = modo === 'crear'
    ? { categoriaId: aNumero(d.categoria), proveedorId: aNumero(d.proveedor), formaPago: d.formaPago }
    : { categoriaId: regla.categoriaId, proveedorId: regla.proveedorId, formaPago: regla.formaPago }
  return {
    ...base,
    formaCalculo: d.formaCalculo,
    factor: porcentajeAFactor(d.porcentaje, d.formaCalculo).factor,
    vigenteDesde: d.desde,
    vigenteHasta: d.hasta === '' ? null : d.hasta,
  }
}

function Resultado({ simulacion }) {
  if (!simulacion) return null
  if (simulacion.cargando) return <p role="status" className="nota">Buscando los productos que cambiarían…</p>
  if (simulacion.error) return <Aviso tipo="peligro">{simulacion.error}</Aviso>
  const cruces = simulacion.datos
  if (cruces.length === 0) {
    return <p role="status" className="nota">Ningún producto cambia de tratamiento de IVA con esta regla.</p>
  }
  const columnas = [
    { clave: 'nombre', titulo: 'Producto', principal: true },
    { clave: 'codigo', titulo: 'Código' },
    { clave: 'antes', titulo: 'Precio antes', numerica: true, render: (c) => PESOS.format(Number(c.precioAntes)) },
    { clave: 'despues', titulo: 'Precio después', numerica: true, render: (c) => PESOS.format(Number(c.precioDespues)) },
    { clave: 'trAntes', titulo: 'IVA antes', render: (c) => etiquetaDeTratamiento(c.tratamientoAntes) },
    { clave: 'trDespues', titulo: 'IVA después', render: (c) => etiquetaDeTratamiento(c.tratamientoDespues) },
  ]
  return (
    <div className="resultado-simulacion">
      <p className="nota" role="status">
        {`${cruces.length} ${cruces.length === 1 ? 'producto cambiaría' : 'productos cambiarían'} de tratamiento de IVA si guardas esta regla.`}
      </p>
      <TablaOTarjetas
        etiqueta="Productos que cambian de impuesto"
        columnas={columnas}
        filas={cruces}
        claveFila={(c) => c.productoId}
      />
    </div>
  )
}

function Formulario({ modo, regla, categorias = [], proveedores = [] }) {
  const navegar = useNavigate()
  const hoy = hoyISO()
  const [datos, setDatos] = useState(() => {
    const siguiente = regla ? sumarDias(regla.vigenteDesde, 1) : hoy
    return {
      categoria: '',
      proveedor: '',
      formaPago: 'CONTADO',
      formaCalculo: regla?.formaCalculo ?? 'SOBRE_COSTO',
      porcentaje: regla ? factorAPorcentaje(regla.factor) : '',
      desde: modo === 'reemplazar'
        ? (siguiente > hoy ? siguiente : hoy)
        : (regla?.vigenteDesde ?? hoy),
      hasta: modo === 'reemplazar' ? '' : (regla?.vigenteHasta ?? ''),
    }
  })
  const [intentos, setIntentos] = useState(0)
  const [enviando, setEnviando] = useState(false)
  const [error, setError] = useState(null)
  const [simulacion, setSimulacion] = useState(null)
  const formulario = useRef(null)
  const aviso = useRef(null)
  const enCurso = useRef(false)
  const turnoSimulacion = useRef(0)

  const categoriaId = modo === 'crear' ? aNumero(datos.categoria) : regla.categoriaId
  const proveedorId = modo === 'crear' ? aNumero(datos.proveedor) : regla.proveedorId
  const { datos: alcance } = useDatos(
    () => apiPrecios.alcance(categoriaId, proveedorId),
    [categoriaId, proveedorId],
  )

  const fallos = motivos(modo, regla, datos, hoy)
  const ver = (campo) => (intentos > 0 ? fallos[campo] : null)
  const hayFallos = Object.values(fallos).some(Boolean)

  useEffect(() => {
    if (intentos > 0) formulario.current?.querySelector('[aria-invalid="true"]')?.focus()
  }, [intentos])
  useEffect(() => {
    if (error) aviso.current?.focus()
  }, [error])

  const cambiar = (campo) => (e) => {
    const valor = e.target.value
    // Lo simulado era de lo que había escrito antes: ya no corresponde.
    turnoSimulacion.current += 1
    setSimulacion(null)
    setDatos((d) => ({ ...d, [campo]: valor }))
  }

  async function simular() {
    setIntentos((n) => n + 1)
    if (hayFallos) return
    const turno = ++turnoSimulacion.current
    setSimulacion({ cargando: true })
    try {
      const simulado = await apiPrecios.simular(armarCuerpo(modo, regla, datos))
      if (turno === turnoSimulacion.current) setSimulacion({ datos: simulado })
    } catch (e) {
      if (turno === turnoSimulacion.current) {
        setSimulacion({ error: e.message || 'No se pudo calcular el efecto de la regla.' })
      }
    }
  }

  async function enviar(e) {
    e.preventDefault()
    if (enCurso.current) return
    setIntentos((n) => n + 1)
    setError(null)
    if (hayFallos) return

    enCurso.current = true
    setEnviando(true)
    const cuerpo = armarCuerpo(modo, regla, datos)
    try {
      if (modo === 'crear') {
        await apiPrecios.crear(cuerpo)
        navegar('/precios', { state: { aviso: 'Regla creada.' } })
      } else if (modo === 'corregir') {
        await apiPrecios.corregir(regla.reglaId, cuerpo)
        navegar('/precios', { state: { aviso: 'Cambios guardados.' } })
      } else {
        // Una sola llamada: el servidor cierra la vigente el día anterior y abre la
        // nueva en una transacción. Si falla, no se tocó nada.
        await apiPrecios.reemplazar(regla.reglaId, cuerpo)
        navegar('/precios', {
          state: { aviso: `Regla reemplazada. La anterior quedó cerrada el ${fechaDia(sumarDias(datos.desde, -1))}.` },
        })
      }
    } catch (err) {
      enCurso.current = false
      setEnviando(false)
      setError(err.message || 'No se pudo completar la operación.')
    }
  }

  let etiquetaBoton
  if (enviando) etiquetaBoton = 'Guardando…'
  else if (modo === 'crear') etiquetaBoton = 'Guardar regla'
  else if (modo === 'corregir') etiquetaBoton = 'Guardar cambios'
  else etiquetaBoton = 'Cerrar la actual y crear la nueva'
  const productos = alcance?.productos

  return (
    <div className="contenido seccion-precios">
      <p className="volver"><Link to="/precios">Volver a las reglas</Link></p>
      <EncabezadoPagina
        titulo={TITULOS[modo]}
        descripcion={modo === 'crear'
          ? 'Esta regla no es obligatoria: sirve para darle un porcentaje a muchos productos a la vez.'
          : undefined}
      />

      <form className="tarjeta formulario-regla" onSubmit={enviar} noValidate ref={formulario}>
        {error && (
          <div tabIndex={-1} ref={aviso} className="aviso-foco">
            <Aviso tipo="peligro">{error}</Aviso>
          </div>
        )}

        {modo === 'crear' ? (
          <div className="rejilla">
            <Campo etiqueta="Categoría">
              <select value={datos.categoria} onChange={cambiar('categoria')}>
                <option value="">Todas las categorías</option>
                {categorias.map((c) => <option key={c.id} value={c.id}>{c.nombre}</option>)}
              </select>
            </Campo>
            <Campo etiqueta="Proveedor">
              <select value={datos.proveedor} onChange={cambiar('proveedor')}>
                <option value="">Todos los proveedores</option>
                {proveedores.map((p) => <option key={p.id} value={p.id}>{p.razonSocial}</option>)}
              </select>
            </Campo>
            <Campo etiqueta="Se cobra">
              <select value={datos.formaPago} onChange={cambiar('formaPago')}>
                {FORMAS_PAGO.map((f) => <option key={f.valor} value={f.valor}>{f.etiqueta}</option>)}
              </select>
            </Campo>
          </div>
        ) : (
          <>
            <dl className="datos-fijos">
              <div><dt>Se aplica a</dt><dd>{textoDeAlcance(regla)}</dd></div>
              <div><dt>Se cobra</dt><dd>{etiquetaDePago(regla.formaPago)}</dd></div>
            </dl>
            <p className="nota">A qué productos se aplica y si es de contado o a crédito no se cambia: sería otra regla.</p>
          </>
        )}

        <p className="alcance" role="status">
          {typeof productos === 'number'
            ? `Esta regla alcanzaría ${productos} ${productos === 1 ? 'producto activo' : 'productos activos'}. `
            : ''}
          No cuenta los que ya tienen precio o porcentaje propio: una regla general no los alcanza.
        </p>

        <fieldset className="opciones">
          <legend>Cómo se calcula</legend>
          {FORMAS_CALCULO.map((f) => (
            <label key={f.valor} className="opcion">
              <input
                type="radio" name="formaCalculo" value={f.valor}
                checked={datos.formaCalculo === f.valor} onChange={cambiar('formaCalculo')}
              />
              <span className="opcion-texto">
                <strong>{f.etiqueta}</strong>
                <small>{f.ayuda}</small>
              </span>
            </label>
          ))}
        </fieldset>

        <Campo
          etiqueta="Porcentaje de ganancia" motivo={ver('porcentaje')}
          ayuda="Escribe el porcentaje con coma, por ejemplo 12,5."
        >
          <input type="text" inputMode="decimal" autoComplete="off" value={datos.porcentaje} onChange={cambiar('porcentaje')} />
        </Campo>

        <div className="rejilla">
          <Campo etiqueta="Empieza el" motivo={ver('desde')}>
            <input type="date" value={datos.desde} onChange={cambiar('desde')} />
          </Campo>
          <Campo
            etiqueta="Termina el (opcional)" motivo={ver('hasta')}
            ayuda="Déjalo vacío si no tiene fecha final."
          >
            <input type="date" value={datos.hasta} onChange={cambiar('hasta')} />
          </Campo>
        </div>

        {modo === 'reemplazar' && datos.desde !== '' && (
          <p className="nota">
            {`Al guardar, la regla actual se cierra el ${fechaDia(sumarDias(datos.desde, -1))} y la nueva empieza el ${fechaDia(datos.desde)}. No se borra nada: la actual queda en el historial.`}
          </p>
        )}

        <div className="simulacion">
          <button type="button" className="boton secundario" disabled={Boolean(simulacion?.cargando)} onClick={simular}>
            Ver qué productos cambiarían de impuesto
          </button>
          <Resultado simulacion={simulacion} />
        </div>

        <div className="acciones">
          <button type="submit" className="boton" disabled={enviando}>{etiquetaBoton}</button>
          <Link to="/precios" className="boton secundario enlace-boton">Cancelar</Link>
        </div>
      </form>
    </div>
  )
}

/** Para elegir categoría y proveedor hay que traerlos: carga, error con reintento y sin permiso. */
function ConCatalogos() {
  const { datos, error, sinPermiso, recargar } = useDatos(
    () => Promise.all([apiPrecios.categorias(), apiPrecios.proveedores()]),
    [],
  )
  if (datos) return <Formulario modo="crear" categorias={datos[0]} proveedores={datos[1]} />

  let cuerpo
  if (sinPermiso) cuerpo = <EstadoSinPermiso />
  else if (error) cuerpo = <EstadoError error={error} alReintentar={recargar} />
  else cuerpo = <EstadoCarga mensaje="Cargando categorías y proveedores…" />
  return (
    <div className="contenido seccion-precios">
      <EncabezadoPagina titulo={TITULOS.crear} />
      {cuerpo}
    </div>
  )
}

/**
 * Alta, corrección y reemplazo de una regla (P-47). Corregir en el sitio solo
 * vale para una regla que aún no empieza; una que rige se reemplaza: se cierra
 * y se abre otra, para no reescribir lo que explicó ventas pasadas.
 */
export default function FormularioRegla({ modo, regla }) {
  return modo === 'crear' ? <ConCatalogos /> : <Formulario modo={modo} regla={regla} />
}

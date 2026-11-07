import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import Aviso from '../../componentes/comun/Aviso.jsx'
import EncabezadoPagina from '../../componentes/comun/EncabezadoPagina.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { apiImpuestos } from '../../servicios/api/impuestos.js'
import BuscadorProducto from './BuscadorProducto.jsx'
import Campo from '../../componentes/comun/Campo.jsx'
import { hoyISO } from './fechas.js'
import { descripcionDeImpuesto } from './tributos.js'

const FUNDAMENTO_MAXIMO = 160

const ALCANCES = [
  { valor: 'todos', etiqueta: 'Todos los productos', ayuda: 'La regla general de tu empresa.' },
  { valor: 'categoria', etiqueta: 'Una categoría', ayuda: 'Gana sobre la general.' },
  { valor: 'producto', etiqueta: 'Un producto', ayuda: 'Gana sobre la categoría y la general.' },
]

/** Por qué no se puede enviar lo escrito; cada campo con su mensaje, o `null`. */
function motivos(d) {
  return {
    categoria: d.alcance === 'categoria' && d.categoria === '' ? 'Elige una categoría.' : null,
    producto: d.alcance === 'producto' && !d.producto ? 'Busca y elige un producto.' : null,
    impuesto: d.impuesto === '' ? 'Elige un impuesto.' : null,
    desde: d.desde === '' ? 'Elige desde qué día rige.' : null,
    hasta: d.hasta !== '' && d.desde !== '' && d.hasta < d.desde
      ? 'La fecha final no puede ser anterior a la inicial.' : null,
    fundamento: d.fundamento.trim().length > FUNDAMENTO_MAXIMO
      ? `El fundamento admite hasta ${FUNDAMENTO_MAXIMO} caracteres.` : null,
  }
}

function Formulario({ catalogo, categorias }) {
  const navegar = useNavigate()
  const [datos, setDatos] = useState(() => ({
    alcance: 'todos', categoria: '', producto: null, impuesto: '',
    desde: hoyISO(), hasta: '', fundamento: '',
  }))
  const [intentos, setIntentos] = useState(0)
  const [enviando, setEnviando] = useState(false)
  const [error, setError] = useState(null)
  const formulario = useRef(null)
  const aviso = useRef(null)
  const enCurso = useRef(false)

  const fallos = motivos(datos)
  const ver = (campo) => (intentos > 0 ? fallos[campo] : null)
  const elegido = catalogo.find((i) => i.codigo === datos.impuesto)

  useEffect(() => {
    if (intentos > 0) formulario.current?.querySelector('[aria-invalid="true"]')?.focus()
  }, [intentos])
  useEffect(() => {
    if (error) aviso.current?.focus()
  }, [error])

  const poner = (campo, valor) => setDatos((d) => ({ ...d, [campo]: valor }))
  const texto = (campo) => (e) => poner(campo, e.target.value)

  async function enviar(e) {
    e.preventDefault()
    if (enCurso.current) return
    setIntentos((n) => n + 1)
    setError(null)
    if (Object.values(fallos).some(Boolean)) return

    enCurso.current = true
    setEnviando(true)
    try {
      await apiImpuestos.crearRegla({
        categoriaId: datos.alcance === 'categoria' ? Number(datos.categoria) : null,
        productoId: datos.alcance === 'producto' ? datos.producto.productoId : null,
        codigoImpuesto: datos.impuesto,
        vigenteDesde: datos.desde,
        vigenteHasta: datos.hasta === '' ? null : datos.hasta,
        fundamento: datos.fundamento.trim() === '' ? null : datos.fundamento.trim(),
      })
      navegar('/impuestos', { state: { aviso: 'Regla creada.' } })
    } catch (err) {
      enCurso.current = false
      setEnviando(false)
      setError(err.message || 'No se pudo crear la regla.')
    }
  }

  return (
    <div className="contenido seccion-impuestos">
      <p className="volver"><Link to="/impuestos">Volver a impuestos</Link></p>
      <EncabezadoPagina
        titulo="Nueva regla de IVA"
        descripcion="Elige a qué productos se aplica y qué impuesto llevan."
      />

      <form className="tarjeta formulario-regla" onSubmit={enviar} noValidate ref={formulario}>
        {error && (
          <div tabIndex={-1} ref={aviso} className="aviso-foco">
            <Aviso tipo="peligro">{error}</Aviso>
          </div>
        )}

        <fieldset className="opciones">
          <legend>A qué productos se aplica</legend>
          {ALCANCES.map((a) => (
            <label key={a.valor} className="opcion">
              <input
                type="radio" name="alcance" value={a.valor}
                checked={datos.alcance === a.valor} onChange={() => poner('alcance', a.valor)}
              />
              <span className="opcion-texto">
                <strong>{a.etiqueta}</strong>
                <small>{a.ayuda}</small>
              </span>
            </label>
          ))}
        </fieldset>

        {datos.alcance === 'categoria' && (
          <Campo rol="alert" etiqueta="Categoría" motivo={ver('categoria')}>
            <select value={datos.categoria} onChange={texto('categoria')}>
              <option value="">Elige una categoría</option>
              {categorias.map((c) => <option key={c.id} value={c.id}>{c.nombre}</option>)}
            </select>
          </Campo>
        )}
        {datos.alcance === 'producto' && (
          <BuscadorProducto
            elegido={datos.producto}
            alElegir={(p) => poner('producto', p)}
            alCambiar={() => poner('producto', null)}
            motivo={ver('producto')}
          />
        )}

        <Campo rol="alert"
          etiqueta="Impuesto" motivo={ver('impuesto')}
          ayuda={elegido ? descripcionDeImpuesto(elegido.tratamiento, elegido.tarifa) : 'Los publica la plataforma.'}
        >
          <select value={datos.impuesto} onChange={texto('impuesto')}>
            <option value="">Elige un impuesto</option>
            {catalogo.map((i) => <option key={i.codigo} value={i.codigo}>{i.nombre}</option>)}
          </select>
        </Campo>

        <div className="rejilla">
          <Campo rol="alert" etiqueta="Empieza el" motivo={ver('desde')}>
            <input type="date" value={datos.desde} onChange={texto('desde')} />
          </Campo>
          <Campo rol="alert"
            etiqueta="Termina el (opcional)" motivo={ver('hasta')}
            ayuda="Déjalo vacío si no tiene fecha final."
          >
            <input type="date" value={datos.hasta} onChange={texto('hasta')} />
          </Campo>
        </div>

        <Campo rol="alert"
          etiqueta="Fundamento (opcional)" motivo={ver('fundamento')}
          ayuda="Una nota para ti: por qué aplica este impuesto."
        >
          <input type="text" autoComplete="off" value={datos.fundamento} onChange={texto('fundamento')} />
        </Campo>

        <p className="nota">
          Esta regla solo se cobra si la empresa es responsable de IVA. Una vez creada no se edita: si hay que
          cambiarla, se cierra y se crea otra.
        </p>

        <div className="acciones">
          <button type="submit" className="boton" disabled={enviando}>
            {enviando ? 'Guardando…' : 'Guardar regla'}
          </button>
          <Link to="/impuestos" className="boton secundario enlace-boton">Cancelar</Link>
        </div>
      </form>
    </div>
  )
}

/** Trae el catálogo y las categorías para elegir: carga, error con reintento y sin permiso. */
export default function FormularioReglaIva() {
  const { datos, error, sinPermiso, recargar } = useDatos(
    () => Promise.all([apiImpuestos.catalogo(), apiImpuestos.categorias()]),
    [],
  )
  if (datos) return <Formulario catalogo={datos[0]} categorias={datos[1]} />

  let cuerpo
  if (sinPermiso) cuerpo = <EstadoSinPermiso />
  else if (error) cuerpo = <EstadoError error={error} alReintentar={recargar} />
  else cuerpo = <EstadoCarga mensaje="Cargando impuestos y categorías…" />
  return (
    <div className="contenido seccion-impuestos">
      <EncabezadoPagina titulo="Nueva regla de IVA" />
      {cuerpo}
    </div>
  )
}

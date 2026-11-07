import { useEffect, useRef, useState } from 'react'
import Aviso from '../../componentes/comun/Aviso.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import EstadoVacio from '../../componentes/comun/EstadoVacio.jsx'
import TablaOTarjetas from '../../componentes/comun/TablaOTarjetas.jsx'
import { apiPlataformaTributario } from '../../servicios/api/plataforma-tributario.js'
import { erroresPorCampo, numeroEscrito, sinCampo } from './campos.js'
import Campo from '../../componentes/comun/Campo.jsx'
import { UVT_PESOS } from './formato.js'

const VACIO = { anio: '', valor: '', resolucion: '', motivo: '' }
const CAMPOS_DEL_SERVIDOR = ['valor', 'resolucion', 'motivo']

// Los mismos límites que el servidor: el año, el rango que acepta; el valor, SolicitudValorUvt.
const ANIO_MINIMO = 2006
const ANIO_MAXIMO = 2100

function motivoDelAnio(texto) {
  const anio = Number(texto)
  if (texto === '' || !Number.isInteger(anio)) return 'Escribe el año, por ejemplo 2027.'
  if (anio < ANIO_MINIMO || anio > ANIO_MAXIMO) return `El año va de ${ANIO_MINIMO} a ${ANIO_MAXIMO}.`
  return null
}

function motivoDelValor(texto) {
  if (String(texto).trim() === '') return 'Escribe el valor de la UVT en pesos.'
  const numero = numeroEscrito(texto)
  if (!numero) return 'Escribe el valor solo con cifras, por ejemplo 52374 o 52374,5.'
  if (numero.valor < 1) return 'El valor tiene que ser de al menos 1 peso.'
  if (numero.decimales > 2) return 'El valor admite como máximo 2 decimales.'
  if (numero.enteros > 10) return 'El valor admite hasta 10 cifras enteras.'
  return null
}

function motivos(d) {
  return {
    anio: motivoDelAnio(d.anio),
    valor: motivoDelValor(d.valor),
    resolucion: d.resolucion.length > 60 ? 'La resolución no puede pasar de 60 caracteres.' : null,
    motivo: d.motivo.length > 200 ? 'El motivo no puede pasar de 200 caracteres.' : null,
  }
}

/**
 * UVT por año: se consulta y se fija. Fijar un año que ya existe lo corrige
 * (la misma llamada), y quedan guardados los años anteriores para reconstruir
 * una factura vieja con el valor de su época.
 */
export default function SeccionUvt({ uvt, alGuardar }) {
  const { datos, cargando, error, sinPermiso, recargar } = uvt
  const [form, setForm] = useState(VACIO)
  const [intentos, setIntentos] = useState(0)
  const [enviando, setEnviando] = useState(false)
  const [errorServidor, setErrorServidor] = useState(null)
  const [camposServidor, setCamposServidor] = useState({})
  const [exito, setExito] = useState(null)
  const formulario = useRef(null)
  const enCurso = useRef(false)

  const fallos = motivos(form)
  // Lo que dijo el servidor de un campo va primero y se quita al editar ese campo.
  const ver = (campo) => camposServidor[campo] ?? (intentos > 0 ? fallos[campo] : null)

  useEffect(() => {
    if (intentos > 0) formulario.current?.querySelector('[aria-invalid="true"]')?.focus()
  }, [intentos])

  const cambiar = (campo) => (e) => {
    const valor = e.target.value
    setForm((f) => ({ ...f, [campo]: valor }))
    setCamposServidor((previos) => sinCampo(previos, campo))
  }

  const precargar = (fila) => {
    setForm({ anio: String(fila.anio), valor: String(fila.valor), resolucion: fila.resolucion ?? '', motivo: '' })
    setIntentos(0)
    setExito(null)
    setErrorServidor(null)
    setCamposServidor({})
    formulario.current?.querySelector('input')?.focus()
  }

  async function enviar(e) {
    e.preventDefault()
    if (enCurso.current) return
    setIntentos((n) => n + 1)
    setErrorServidor(null)
    setCamposServidor({})
    setExito(null)
    if (Object.values(fallos).some(Boolean)) return

    enCurso.current = true
    setEnviando(true)
    try {
      const anio = Number(form.anio)
      await apiPlataformaTributario.fijarUvt(anio, {
        valor: Number(form.valor), resolucion: form.resolucion, motivo: form.motivo,
      })
      setExito(`UVT del ${anio} guardada.`)
      setForm(VACIO)
      setIntentos(0)
      alGuardar()
    } catch (err) {
      const delCampo = err.estado === 400 ? erroresPorCampo(err.message, CAMPOS_DEL_SERVIDOR) : null
      if (delCampo) {
        setCamposServidor(delCampo)
        setIntentos((n) => n + 1)
      } else {
        setErrorServidor(err.message || 'No se pudo completar la operación.')
      }
    } finally {
      enCurso.current = false
      setEnviando(false)
    }
  }

  const columnas = [
    { clave: 'anio', titulo: 'Año', principal: true },
    { clave: 'valor', titulo: 'Valor', numerica: true, render: (f) => UVT_PESOS.format(f.valor) },
    { clave: 'resolucion', titulo: 'Resolución', render: (f) => f.resolucion || '—' },
    {
      clave: 'acciones', titulo: 'Acciones',
      render: (f) => (
        <button
          type="button" className="boton secundario menudo"
          aria-label={`Cambiar la UVT de ${f.anio}`} onClick={() => precargar(f)}
        >
          Cambiar
        </button>
      ),
    },
  ]

  let lista
  if (sinPermiso) lista = <EstadoSinPermiso />
  else if (error) lista = <EstadoError error={error} alReintentar={recargar} />
  else if (cargando && !datos) lista = <EstadoCarga mensaje="Cargando la UVT…" />
  else if (datos.length === 0) {
    lista = (
      <EstadoVacio
        titulo="Todavía no hay valores de la UVT"
        detalle="Fija el primero abajo. Las tiendas pueden vender sin ella."
      />
    )
  } else {
    lista = (
      <TablaOTarjetas etiqueta="UVT por año" columnas={columnas} filas={datos} claveFila={(f) => f.anio} />
    )
  }

  return (
    <section className="seccion-tributaria" aria-labelledby="titulo-uvt">
      <h2 id="titulo-uvt">Unidad de valor tributario (UVT)</h2>
      <p className="nota">
        La UVT es opcional para las tiendas: solo la usa quien activó la exclusión de IVA por tope.
        Se guarda por año para poder reconstruir una factura vieja con el valor de su época.
      </p>
      {lista}

      {!sinPermiso && (
        <form className="tarjeta formulario-tributario" onSubmit={enviar} noValidate ref={formulario} aria-label="Fijar la UVT de un año">
          <h3>Fijar la UVT de un año</h3>
          {exito && <Aviso tipo="exito">{exito}</Aviso>}
          {errorServidor && <Aviso tipo="peligro">{errorServidor}</Aviso>}
          <Campo etiqueta="Año" motivo={ver('anio')}>
            <input type="number" inputMode="numeric" autoComplete="off" value={form.anio} onChange={cambiar('anio')} />
          </Campo>
          <Campo etiqueta="Valor de la UVT (en pesos)" motivo={ver('valor')}>
            <input type="number" inputMode="decimal" step="any" autoComplete="off" value={form.valor} onChange={cambiar('valor')} />
          </Campo>
          <Campo etiqueta="Resolución" motivo={ver('resolucion')} ayuda="Opcional. La norma que fija el valor.">
            <input type="text" autoComplete="off" value={form.resolucion} onChange={cambiar('resolucion')} />
          </Campo>
          <Campo etiqueta="Motivo" motivo={ver('motivo')} ayuda="Opcional. Queda en la bitácora de la plataforma.">
            <input type="text" autoComplete="off" value={form.motivo} onChange={cambiar('motivo')} />
          </Campo>
          <div className="acciones">
            <button type="submit" className="boton" disabled={enviando}>
              {enviando ? 'Guardando…' : 'Fijar UVT'}
            </button>
          </div>
        </form>
      )}
    </section>
  )
}

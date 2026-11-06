import { useEffect, useRef, useState } from 'react'
import Aviso from '../../componentes/comun/Aviso.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import EstadoVacio from '../../componentes/comun/EstadoVacio.jsx'
import TablaOTarjetas from '../../componentes/comun/TablaOTarjetas.jsx'
import { apiPlataformaTributario } from '../../servicios/api/plataforma-tributario.js'
import { PESOS } from '../../servicios/formato.js'
import Campo from './Campo.jsx'

const VACIO = { anio: '', valor: '', resolucion: '', motivo: '' }

function motivos(d) {
  const anio = Number(d.anio)
  const valor = Number(d.valor)
  return {
    anio: d.anio === '' || !Number.isInteger(anio) || anio < 2000 || anio > 2100
      ? 'Escribe el año, por ejemplo 2027.' : null,
    valor: d.valor === '' ? 'Escribe el valor de la UVT en pesos.'
      : !(valor > 0) ? 'El valor tiene que ser mayor que cero.' : null,
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
  const [exito, setExito] = useState(null)
  const formulario = useRef(null)
  const enCurso = useRef(false)

  const fallos = motivos(form)
  const ver = (campo) => (intentos > 0 ? fallos[campo] : null)

  useEffect(() => {
    if (intentos > 0) formulario.current?.querySelector('[aria-invalid="true"]')?.focus()
  }, [intentos])

  const cambiar = (campo) => (e) => {
    const valor = e.target.value
    setForm((f) => ({ ...f, [campo]: valor }))
  }

  const precargar = (fila) => {
    setForm({ anio: String(fila.anio), valor: String(fila.valor), resolucion: fila.resolucion ?? '', motivo: '' })
    setIntentos(0)
    setExito(null)
    setErrorServidor(null)
    formulario.current?.querySelector('input')?.focus()
  }

  async function enviar(e) {
    e.preventDefault()
    if (enCurso.current) return
    setIntentos((n) => n + 1)
    setErrorServidor(null)
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
      setErrorServidor(err.message || 'No se pudo completar la operación.')
    } finally {
      enCurso.current = false
      setEnviando(false)
    }
  }

  const columnas = [
    { clave: 'anio', titulo: 'Año', principal: true },
    { clave: 'valor', titulo: 'Valor', numerica: true, render: (f) => PESOS.format(f.valor) },
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

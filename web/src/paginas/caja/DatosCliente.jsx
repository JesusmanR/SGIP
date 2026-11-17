import { useId, useState } from 'react'
import Aviso from '../../componentes/comun/Aviso.jsx'
import Campo from '../../componentes/comun/Campo.jsx'
import { actualizarCliente, clientePorIdentificacion, crearCliente } from '../../servicios/api/caja.js'
import '../../estilos/caja.css'
import { domicilioVacio } from './venta.js'

const TIPOS_DOCUMENTO = [['CC', 'Cédula de ciudadanía'], ['NIT', 'NIT'], ['CE', 'Cédula de extranjería'], ['PASAPORTE', 'Pasaporte']]
const CORREO = /^\S+@\S+\.\S+$/

/** Alta o corrección de un cliente: nombre, correo y la autorización de datos, que es obligatoria. */
function FormularioCliente({ inicial, titulo, textoGuardar, alGuardar, alCancelar }) {
  const [datos, setDatos] = useState({
    nombre: inicial.nombre ?? '', correo: inicial.correo ?? '', telefono: inicial.telefono ?? '',
    direccion: inicial.direccion ?? '', ciudad: inicial.ciudad ?? '',
  })
  const [autoriza, setAutoriza] = useState(false)
  const [intentado, setIntentado] = useState(false)
  const [guardando, setGuardando] = useState(false)
  const [fallo, setFallo] = useState(null)
  const idAutorizacion = useId()
  const cambiar = (campo) => (e) => setDatos((d) => ({ ...d, [campo]: e.target.value }))

  const motivos = {}
  if (!datos.nombre.trim()) motivos.nombre = 'Escribe el nombre del cliente.'
  if (!CORREO.test(datos.correo.trim())) motivos.correo = 'Escribe un correo válido.'
  if (!autoriza) motivos.autoriza = 'Falta la autorización para tratar sus datos.'
  const mostrar = (campo) => (intentado ? motivos[campo] : undefined)

  async function enviar(evento) {
    evento.preventDefault()
    setIntentado(true)
    if (Object.keys(motivos).length > 0) return
    setGuardando(true)
    setFallo(null)
    try {
      const cuerpo = {
        nombre: datos.nombre.trim(), tipoDocumento: inicial.tipoDocumento, identificacion: inicial.identificacion,
        correo: datos.correo.trim(), autorizacionDatos: true,
      }
      for (const campo of ['telefono', 'direccion', 'ciudad']) {
        if (datos[campo].trim()) cuerpo[campo] = datos[campo].trim()
      }
      await alGuardar(cuerpo)
    } catch (error) {
      setFallo(error.message || 'No se pudo guardar el cliente.')
      setGuardando(false)
    }
  }

  return (
    <form onSubmit={enviar} noValidate className="caja-formulario-cliente" aria-label={titulo}>
      <p className="nota">{inicial.tipoDocumento} {inicial.identificacion}</p>
      {fallo && <Aviso tipo="peligro">{fallo}</Aviso>}
      <Campo etiqueta="Nombre completo" motivo={mostrar('nombre')}>
        <input type="text" value={datos.nombre} onChange={cambiar('nombre')} maxLength={160} autoComplete="off" />
      </Campo>
      <Campo etiqueta="Correo electrónico" motivo={mostrar('correo')}>
        <input type="email" value={datos.correo} onChange={cambiar('correo')} maxLength={120} autoComplete="off" />
      </Campo>
      <Campo etiqueta="Teléfono (opcional)">
        <input type="text" inputMode="tel" value={datos.telefono} onChange={cambiar('telefono')} maxLength={20} autoComplete="off" />
      </Campo>
      <label className="casilla">
        <input
          type="checkbox" checked={autoriza} onChange={(e) => setAutoriza(e.target.checked)}
          aria-describedby={mostrar('autoriza') ? idAutorizacion : undefined}
        />
        El cliente autoriza el tratamiento de sus datos
      </label>
      <small id={idAutorizacion} role="status" className="campo-mensaje error">{mostrar('autoriza') ?? ''}</small>
      <div className="caja-acciones">
        <button type="submit" className="boton" disabled={guardando}>{textoGuardar}</button>
        <button type="button" className="boton secundario" disabled={guardando} onClick={alCancelar}>Cancelar</button>
      </div>
    </form>
  )
}

function DatosDeDomicilio({ domicilio, cliente, alCambiar }) {
  const cambiar = (campo) => (e) => alCambiar({ ...domicilio, [campo]: e.target.value })
  return (
    <div className="caja-domicilio">
      {!cliente && <p className="nota">Un domicilio necesita un cliente identificado: no puede ser Consumidor final.</p>}
      <div className="rejilla">
        <Campo etiqueta="Teléfono">
          <input type="text" inputMode="tel" value={domicilio.telefono} onChange={cambiar('telefono')} maxLength={20} autoComplete="off" />
        </Campo>
        <Campo etiqueta="Dirección">
          <input type="text" value={domicilio.direccion} onChange={cambiar('direccion')} maxLength={200} autoComplete="off" />
        </Campo>
        <Campo etiqueta="Ciudad">
          <input type="text" value={domicilio.ciudad} onChange={cambiar('ciudad')} maxLength={80} autoComplete="off" />
        </Campo>
        <Campo etiqueta="¿Quién recibe?">
          <input type="text" value={domicilio.recibe} onChange={cambiar('recibe')} maxLength={120} autoComplete="off" />
        </Campo>
      </div>
      <label className="casilla">
        <input
          type="checkbox" checked={domicilio.contraEntrega}
          onChange={(e) => alCambiar({ ...domicilio, contraEntrega: e.target.checked })}
        />
        ¿Paga contra entrega?
      </label>
    </div>
  )
}

/**
 * El cliente de la venta y, si hace falta, su domicilio. «Consumidor final» es lo
 * normal y está siempre a la vista; identificar a alguien pide su documento y, si
 * no existe, nombre, correo y su autorización para tratar los datos.
 * Todo esto vive solo en el estado de la pantalla.
 */
export default function DatosCliente({ cliente, alCambiarCliente, domicilio, alCambiarDomicilio }) {
  const [modo, setModo] = useState('resumen') // resumen | buscar | encontrado | nuevo | corregir
  const [tipo, setTipo] = useState('CC')
  const [identificacion, setIdentificacion] = useState('')
  const [encontrado, setEncontrado] = useState(null)
  const [buscando, setBuscando] = useState(false)
  const [fallo, setFallo] = useState(null)

  async function buscar(evento) {
    evento.preventDefault()
    const numero = identificacion.trim()
    if (!numero) {
      setFallo('Escribe el número de identificación.')
      return
    }
    setBuscando(true)
    setFallo(null)
    try {
      const existente = await clientePorIdentificacion(tipo, numero)
      setEncontrado(existente)
      setModo(existente ? 'encontrado' : 'nuevo')
    } catch (error) {
      setFallo(error.message || 'No se pudo buscar el cliente.')
    } finally {
      setBuscando(false)
    }
  }

  function usar(c) {
    alCambiarCliente(c)
    setModo('resumen')
    setIdentificacion('')
  }

  function irAConsumidorFinal() {
    alCambiarCliente(null)
    setModo('resumen')
    setFallo(null)
  }

  function alternarDomicilio(activo) {
    alCambiarDomicilio(activo ? domicilioVacio(cliente) : null)
  }

  return (
    <section className="caja-cliente" aria-label="Cliente">
      <h2 className="caja-titulo-seccion">Cliente</h2>
      <div className="caja-acciones" role="group" aria-label="Quién compra">
        <button type="button" className="boton secundario caja-opcion" aria-pressed={!cliente} onClick={irAConsumidorFinal}>
          Consumidor final
        </button>
        <button
          type="button" className="boton secundario caja-opcion" aria-pressed={Boolean(cliente)}
          onClick={() => { setModo('buscar'); setFallo(null) }}
        >
          Identificar cliente
        </button>
      </div>

      {cliente && modo === 'resumen' && (
        <p className="caja-cliente-actual">
          <strong>{cliente.nombre}</strong>
          <span className="sub-linea">{cliente.tipoDocumento} {cliente.identificacion}</span>
        </p>
      )}

      {modo === 'buscar' && (
        <form onSubmit={buscar} noValidate className="caja-formulario-cliente">
          <div className="rejilla">
            <Campo etiqueta="Tipo de documento">
              <select value={tipo} onChange={(e) => setTipo(e.target.value)}>
                {TIPOS_DOCUMENTO.map(([v, texto]) => <option key={v} value={v}>{texto}</option>)}
              </select>
            </Campo>
            <Campo etiqueta="Identificación">
              <input type="text" inputMode="numeric" value={identificacion} onChange={(e) => setIdentificacion(e.target.value)} maxLength={20} autoComplete="off" />
            </Campo>
          </div>
          {fallo && <Aviso tipo="peligro">{fallo}</Aviso>}
          <div className="caja-acciones">
            <button type="submit" className="boton" disabled={buscando}>Buscar cliente</button>
            <button type="button" className="boton secundario" onClick={() => setModo('resumen')}>Cancelar</button>
          </div>
        </form>
      )}

      {modo === 'encontrado' && encontrado && (
        <div className="caja-cliente-encontrado">
          <p className="caja-cliente-actual">
            <strong>{encontrado.nombre}</strong>
            <span className="sub-linea">{encontrado.tipoDocumento} {encontrado.identificacion}</span>
            <span className="sub-linea">{encontrado.correo}</span>
          </p>
          <div className="caja-acciones">
            <button type="button" className="boton" onClick={() => usar(encontrado)}>Usar este cliente</button>
            <button type="button" className="boton secundario" onClick={() => setModo('corregir')}>Corregir datos</button>
          </div>
        </div>
      )}

      {modo === 'nuevo' && (
        <FormularioCliente
          inicial={{ tipoDocumento: tipo, identificacion: identificacion.trim() }}
          titulo="Cliente nuevo" textoGuardar="Guardar cliente" alCancelar={() => setModo('resumen')}
          alGuardar={async (cuerpo) => usar(await crearCliente(cuerpo))}
        />
      )}

      {modo === 'corregir' && encontrado && (
        <FormularioCliente
          inicial={encontrado} titulo="Corregir datos del cliente" textoGuardar="Guardar cambios"
          alCancelar={() => setModo('encontrado')}
          alGuardar={async (cuerpo) => usar(await actualizarCliente(encontrado.id, cuerpo))}
        />
      )}

      <label className="casilla caja-casilla-domicilio">
        <input type="checkbox" checked={Boolean(domicilio)} onChange={(e) => alternarDomicilio(e.target.checked)} />
        Domicilio
      </label>
      {domicilio && <DatosDeDomicilio domicilio={domicilio} cliente={cliente} alCambiar={alCambiarDomicilio} />}
    </section>
  )
}

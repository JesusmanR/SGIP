import { useId, useState } from 'react'
import '../../estilos/caja.css'
import Aviso from '../comun/Aviso.jsx'
import Campo from '../comun/Campo.jsx'

export const TIPOS_DOCUMENTO = [
  ['CC', 'Cédula de ciudadanía'], ['NIT', 'NIT'], ['CE', 'Cédula de extranjería'], ['PASAPORTE', 'Pasaporte'],
]
const CORREO = /^\S+@\S+\.\S+$/

/**
 * Alta o corrección de un cliente: nombre, correo y la autorización de datos, que es obligatoria
 * (el servidor la exige también al editar). La dirección y la ciudad no se piden aquí, pero viajan
 * tal como estaban para que guardar no las borre.
 *
 * Con `documentoEditable` el tipo y el número se escriben en el formulario (pantalla Clientes);
 * sin él, el documento va fijo y solo se muestra (la caja, que ya buscó al cliente por él).
 * Con `domicilioEditable` se piden también la dirección y la ciudad (pantalla Clientes); la ciudad vacía la
 * completa el servidor con la de la empresa.
 * `inicial.autorizoDatos` deja la casilla marcada si el cliente ya había autorizado.
 */
export default function FormularioCliente({
  inicial, titulo, textoGuardar, alGuardar, alCancelar, documentoEditable = false, domicilioEditable = false,
}) {
  const [datos, setDatos] = useState({
    tipoDocumento: inicial.tipoDocumento, identificacion: inicial.identificacion ?? '',
    nombre: inicial.nombre ?? '', correo: inicial.correo ?? '', telefono: inicial.telefono ?? '',
    direccion: inicial.direccion ?? '', ciudad: inicial.ciudad ?? '',
  })
  const [autoriza, setAutoriza] = useState(Boolean(inicial.autorizoDatos))
  const [intentado, setIntentado] = useState(false)
  const [guardando, setGuardando] = useState(false)
  const [fallo, setFallo] = useState(null)
  const idAutorizacion = useId()
  const cambiar = (campo) => (e) => setDatos((d) => ({ ...d, [campo]: e.target.value }))

  const motivos = {}
  if (documentoEditable && !datos.identificacion.trim()) motivos.identificacion = 'Escribe el número de documento.'
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
        nombre: datos.nombre.trim(), tipoDocumento: datos.tipoDocumento, identificacion: datos.identificacion.trim(),
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
      {documentoEditable ? (
        <div className="rejilla">
          <Campo etiqueta="Tipo de documento">
            <select value={datos.tipoDocumento} onChange={cambiar('tipoDocumento')}>
              {TIPOS_DOCUMENTO.map(([v, texto]) => <option key={v} value={v}>{texto}</option>)}
            </select>
          </Campo>
          <Campo etiqueta="Número de documento" motivo={mostrar('identificacion')}>
            <input
              type="text" inputMode="numeric" value={datos.identificacion} onChange={cambiar('identificacion')}
              maxLength={20} autoComplete="off"
            />
          </Campo>
        </div>
      ) : (
        <p className="nota">{datos.tipoDocumento} {datos.identificacion}</p>
      )}
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
      {domicilioEditable && (
        <>
          <Campo etiqueta="Dirección (opcional)">
            <input type="text" value={datos.direccion} onChange={cambiar('direccion')} maxLength={200} autoComplete="off" />
          </Campo>
          <Campo etiqueta="Ciudad" ayuda="Si la dejas vacía, se usa la ciudad de la empresa.">
            <input type="text" value={datos.ciudad} onChange={cambiar('ciudad')} maxLength={80} autoComplete="off" />
          </Campo>
        </>
      )}
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

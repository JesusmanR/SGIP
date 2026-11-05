import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import Aviso from '../../componentes/comun/Aviso.jsx'
import EncabezadoPagina from '../../componentes/comun/EncabezadoPagina.jsx'
import { crearProveedor } from '../../servicios/api/proveedores.js'
import Campo from '../compras/Campo.jsx'

const VACIO = { razonSocial: '', nit: '', contacto: '', correo: '', telefono: '', plazoDias: '0' }
const CORREO = /^[^\s@]+@[^\s@]+$/

/** Los motivos por los que el formulario no se puede enviar; vacío si está bien. */
function validar(v) {
  const e = {}
  const razon = v.razonSocial.trim()
  if (!razon) e.razonSocial = 'La razón social es obligatoria.'
  else if (razon.length > 160) e.razonSocial = 'La razón social no puede pasar de 160 caracteres.'
  const nit = v.nit.trim()
  if (!nit) e.nit = 'El NIT es obligatorio.'
  else if (nit.length > 20) e.nit = 'El NIT no puede pasar de 20 caracteres.'
  if (v.contacto.trim().length > 120) e.contacto = 'El contacto no puede pasar de 120 caracteres.'
  const correo = v.correo.trim()
  if (correo && !CORREO.test(correo)) e.correo = 'Revisa el correo: debe verse como nombre@empresa.co.'
  else if (correo.length > 120) e.correo = 'El correo no puede pasar de 120 caracteres.'
  if (v.telefono.trim().length > 20) e.telefono = 'El teléfono no puede pasar de 20 caracteres.'
  if (!/^\d+$/.test(v.plazoDias.trim()) || Number(v.plazoDias.trim()) > 365) {
    e.plazoDias = 'Escribe los días como un número entero de 0 a 365. Cero es de contado.'
  }
  return e
}

const nuloSiVacio = (texto) => (texto.trim() === '' ? null : texto.trim())

export default function NuevoProveedor() {
  const navegar = useNavigate()
  const formulario = useRef(null)
  const aviso = useRef(null)
  const [valores, setValores] = useState(VACIO)
  const [errores, setErrores] = useState({})
  const [errorServidor, setErrorServidor] = useState(null)
  const [enviando, setEnviando] = useState(false)
  // Cada intento fallido cuenta, para volver a enfocar el primer error.
  const [intentos, setIntentos] = useState(0)

  useEffect(() => {
    if (intentos > 0) formulario.current?.querySelector('[aria-invalid="true"]')?.focus()
  }, [intentos])
  useEffect(() => { if (errorServidor) aviso.current?.focus() }, [errorServidor])

  const cambiar = (clave) => (evento) => {
    setValores((previo) => ({ ...previo, [clave]: evento.target.value }))
    setErrores((previo) => ({ ...previo, [clave]: undefined }))
  }

  async function enviar(evento) {
    evento.preventDefault()
    if (enviando) return
    const encontrados = validar(valores)
    setErrorServidor(null)
    setErrores(encontrados)
    if (Object.keys(encontrados).length > 0) { setIntentos((n) => n + 1); return }

    setEnviando(true)
    try {
      const creado = await crearProveedor({
        razonSocial: valores.razonSocial.trim(),
        nit: valores.nit.trim(),
        contacto: nuloSiVacio(valores.contacto),
        correo: nuloSiVacio(valores.correo),
        telefono: nuloSiVacio(valores.telefono),
        plazoDias: Number(valores.plazoDias.trim()),
      })
      navegar(`/proveedores/${creado.id}`, { state: { creado: true } })
    } catch (error) {
      setEnviando(false)
      if (error.estado === 409) {
        // El único choque de este alta es el NIT repetido.
        setErrores({ nit: error.message })
        setIntentos((n) => n + 1)
      } else {
        setErrorServidor(error.message || 'No se pudo guardar el proveedor. Inténtalo otra vez.')
      }
    }
  }

  return (
    <div className="contenido seccion-proveedores">
      <EncabezadoPagina titulo="Nuevo proveedor" descripcion="Con él podrás crear productos y órdenes de compra." />
      <form ref={formulario} className="tarjeta formulario-angosto" onSubmit={enviar} noValidate>
        {errorServidor && (
          <div ref={aviso} tabIndex={-1}><Aviso tipo="peligro">{errorServidor}</Aviso></div>
        )}
        <Campo etiqueta="Razón social" motivo={errores.razonSocial}>
          <input type="text" value={valores.razonSocial} onChange={cambiar('razonSocial')} autoComplete="organization" />
        </Campo>
        <Campo etiqueta="NIT" motivo={errores.nit} ayuda="Con o sin dígito de verificación.">
          <input type="text" value={valores.nit} onChange={cambiar('nit')} />
        </Campo>
        <Campo etiqueta="Persona de contacto (opcional)" motivo={errores.contacto}>
          <input type="text" value={valores.contacto} onChange={cambiar('contacto')} />
        </Campo>
        <Campo etiqueta="Correo (opcional)" motivo={errores.correo}>
          <input type="email" value={valores.correo} onChange={cambiar('correo')} autoComplete="off" />
        </Campo>
        <Campo etiqueta="Teléfono (opcional)" motivo={errores.telefono}>
          <input type="tel" value={valores.telefono} onChange={cambiar('telefono')} />
        </Campo>
        <Campo etiqueta="Plazo de pago en días" motivo={errores.plazoDias} ayuda="Cero significa de contado.">
          <input type="text" inputMode="numeric" value={valores.plazoDias} onChange={cambiar('plazoDias')} />
        </Campo>
        <div className="acciones-pie">
          <Link className="boton secundario" to="/proveedores">Cancelar</Link>
          <button type="submit" className="boton" disabled={enviando}>
            {enviando ? 'Guardando…' : 'Guardar proveedor'}
          </button>
        </div>
      </form>
    </div>
  )
}

import { useRef, useState } from 'react'
import Aviso from '../../componentes/comun/Aviso.jsx'
import Campo from '../../componentes/comun/Campo.jsx'
import { apiPlataformaEmpresas } from '../../servicios/api/plataforma-empresas.js'

const CAMPO_POR_TIPO = {
  'urn:sgip:empresa:documento-invalido': 'numero',
  'urn:sgip:nit-repetido': 'numero',
}

const AYUDA_NUMERO = 'Escribe solo el número, sin el dígito de verificación.'

/** Por qué no se puede enviar lo escrito. El servidor repite estas reglas; aquí solo se evita el viaje. */
function motivos(d) {
  const numero = d.numero.trim()
  const razon = d.razonSocial.trim()
  const motivo = d.motivo.trim()
  return {
    numero: numero === '' ? 'Escribe el número del documento.'
      : numero.length > 40 ? 'El número no puede pasar de 40 caracteres.' : null,
    razonSocial: razon === '' ? 'Escribe la razón social.'
      : razon.length > 160 ? 'La razón social no puede pasar de 160 caracteres.' : null,
    motivo: motivo.length < 10 ? 'Cuenta el motivo en al menos 10 caracteres.'
      : motivo.length > 200 ? 'El motivo no puede pasar de 200 caracteres.' : null,
  }
}

/**
 * La identidad legal de la empresa (tipo de documento, número y razón social). Solo la cambia el
 * SUPER_ADMIN, siempre con un motivo que queda en la bitácora. Los rechazos del servidor
 * (documento inválido, número repetido) se muestran junto al número, con su texto tal cual.
 */
export default function IdentidadEmpresa({ empresa, alExito }) {
  const [datos, setDatos] = useState({
    tipoDocumento: empresa.tipoDocumento ?? 'NIT',
    // Las empresas anteriores a la V48 guardan el dígito de verificación («NNN-D»): el servidor rechaza el guion.
    numero: (empresa.nit ?? '').split('-')[0],
    razonSocial: empresa.razonSocial ?? '',
    motivo: '',
  })
  const [intentos, setIntentos] = useState(0)
  const [enviando, setEnviando] = useState(false)
  const [conflictos, setConflictos] = useState({})
  const [error, setError] = useState(null)
  const formulario = useRef(null)
  const enCurso = useRef(false)

  const fallos = motivos(datos)
  const ver = (campo) => conflictos[campo] ?? (intentos > 0 ? fallos[campo] : null)

  const cambiar = (campo) => (e) => {
    const valor = e.target.value
    setDatos((d) => ({ ...d, [campo]: valor }))
    setConflictos((previos) => {
      if (!(campo in previos)) return previos
      const resto = { ...previos }
      delete resto[campo]
      return resto
    })
  }

  const enfocarPrimerError = () => {
    setTimeout(() => formulario.current?.querySelector('[aria-invalid="true"]')?.focus(), 0)
  }

  async function enviar(e) {
    e.preventDefault()
    if (enCurso.current) return
    setIntentos((n) => n + 1)
    setError(null)
    if (Object.values(fallos).some(Boolean) || Object.keys(conflictos).length > 0) {
      enfocarPrimerError()
      return
    }
    enCurso.current = true
    setEnviando(true)
    try {
      await apiPlataformaEmpresas.cambiarIdentidad(String(empresa.empresaId), {
        tipoDocumento: datos.tipoDocumento,
        numero: datos.numero.trim(),
        razonSocial: datos.razonSocial.trim(),
        motivo: datos.motivo.trim(),
      })
      setDatos((d) => ({ ...d, motivo: '' }))
      setIntentos(0)
      alExito('Identidad actualizada.')
    } catch (err) {
      const campo = [400, 409].includes(err.estado) ? (CAMPO_POR_TIPO[err.tipo] ?? null) : null
      if (campo) {
        setConflictos((previos) => ({ ...previos, [campo]: err.message }))
        enfocarPrimerError()
      } else {
        setError(err.message || 'No se pudo cambiar la identidad.')
      }
    } finally {
      enCurso.current = false
      setEnviando(false)
    }
  }

  return (
    <section className="tarjeta identidad" aria-labelledby="t-identidad">
      <h2 id="t-identidad">Identidad</h2>
      <p className="nota">
        Es lo que identifica a la empresa ante sus clientes y en los recibos. Cámbiala solo para corregir un error o
        por un cambio legal; queda registrado con tu motivo.
      </p>
      {error && <Aviso tipo="peligro">{error}</Aviso>}
      <form onSubmit={enviar} noValidate ref={formulario} className="identidad-formulario">
        <Campo etiqueta="Tipo de documento">
          <select value={datos.tipoDocumento} onChange={cambiar('tipoDocumento')}>
            <option value="NIT">NIT</option>
            <option value="CC">CC (cédula)</option>
          </select>
        </Campo>
        <Campo etiqueta="Número" ayuda={AYUDA_NUMERO} motivo={ver('numero')}>
          <input type="text" inputMode="numeric" autoComplete="off" maxLength={40} value={datos.numero} onChange={cambiar('numero')} />
        </Campo>
        <Campo etiqueta="Razón social" motivo={ver('razonSocial')}>
          <input type="text" autoComplete="off" maxLength={160} value={datos.razonSocial} onChange={cambiar('razonSocial')} />
        </Campo>
        <Campo etiqueta="Motivo" ayuda="Entre 10 y 200 caracteres. Queda en la bitácora de la plataforma." motivo={ver('motivo')}>
          <input type="text" autoComplete="off" maxLength={200} value={datos.motivo} onChange={cambiar('motivo')} />
        </Campo>
        <div className="acciones">
          <button type="submit" className="boton" disabled={enviando}>
            {enviando ? 'Guardando…' : 'Cambiar identidad'}
          </button>
        </div>
      </form>
    </section>
  )
}

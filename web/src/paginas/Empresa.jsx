import { useState } from 'react'
import { Link } from 'react-router-dom'
import Campo from '../componentes/comun/Campo.jsx'
import EncabezadoPagina from '../componentes/comun/EncabezadoPagina.jsx'
import EstadoCarga from '../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../componentes/comun/EstadoSinPermiso.jsx'
import SubirImagen from '../componentes/SubirImagen.jsx'
import { useDatos } from '../hooks/useDatos.js'
import { api } from '../servicios/api.js'
import { prepararImagen } from '../servicios/imagenes.js'
import { esAdministrador } from '../servicios/sesion.js'
import '../estilos/empresa.css'

const PIN = /^\d{4,6}$/

/**
 * El PIN con el que el administrador autoriza descuentos, devoluciones y ventas sin existencia en la caja.
 * Nunca se muestra: solo se sabe si ya hay uno. Para cambiarlo hay que escribir el actual; la primera vez no.
 */
function MiPin() {
  const estado = useDatos(() => api.estadoPin())
  const [actual, setActual] = useState('')
  const [nuevo, setNuevo] = useState('')
  const [repetido, setRepetido] = useState('')
  const [motivos, setMotivos] = useState({})
  const [fallo, setFallo] = useState(null)
  const [guardado, setGuardado] = useState(false)
  const [enviando, setEnviando] = useState(false)
  const [configurado, setConfigurado] = useState(null)
  const hay = configurado ?? estado.datos?.configurado
  const soloNumeros = (setter) => (e) => setter(e.target.value.replace(/\D/g, ''))

  async function guardar(evento) {
    evento.preventDefault()
    setFallo(null)
    setGuardado(false)
    const nuevosMotivos = {}
    if (hay && actual === '') nuevosMotivos.actual = 'Escribe tu PIN actual.'
    if (!PIN.test(nuevo)) nuevosMotivos.nuevo = 'El PIN tiene de 4 a 6 números.'
    else if (repetido !== nuevo) nuevosMotivos.repetido = 'Los dos PIN no coinciden.'
    setMotivos(nuevosMotivos)
    if (Object.keys(nuevosMotivos).length > 0) return
    setEnviando(true)
    try {
      await api.fijarPin(hay ? actual : null, nuevo)
      setConfigurado(true)
      setGuardado(true)
    } catch (error) {
      setFallo(error.message || 'No se pudo guardar el PIN.')
    } finally {
      // El PIN no se queda en la pantalla, salga bien o mal.
      setActual('')
      setNuevo('')
      setRepetido('')
      setEnviando(false)
    }
  }

  return (
    <section className="tarjeta empresa-pin" aria-labelledby="t-pin">
      <h2 id="t-pin">Mi PIN de autorización</h2>
      {estado.cargando && <p>Consultando tu PIN…</p>}
      {estado.error && <div className="aviso error" role="alert">No se pudo consultar el estado de tu PIN.</div>}
      {!estado.cargando && !estado.error && (
        <>
          <p>
            {hay
              ? 'Ya tienes un PIN. Para cambiarlo, escribe el actual y el nuevo.'
              : 'Todavía no tienes un PIN. Créalo para poder autorizar descuentos y devoluciones en la caja.'}
          </p>
          {fallo && <div className="aviso error" role="alert">{fallo}</div>}
          {guardado && <div className="aviso exito" role="status">PIN guardado.</div>}
          <form onSubmit={guardar} noValidate className="empresa-pin-formulario">
            {hay && (
              <Campo rol="alert" etiqueta="PIN actual" motivo={motivos.actual}>
                <input type="password" inputMode="numeric" autoComplete="off" maxLength={6} value={actual} onChange={soloNumeros(setActual)} />
              </Campo>
            )}
            <Campo rol="alert" etiqueta="PIN nuevo" ayuda="De 4 a 6 números." motivo={motivos.nuevo}>
              <input type="password" inputMode="numeric" autoComplete="off" maxLength={6} value={nuevo} onChange={soloNumeros(setNuevo)} />
            </Campo>
            <Campo rol="alert" etiqueta="Repite el PIN nuevo" motivo={motivos.repetido}>
              <input type="password" inputMode="numeric" autoComplete="off" maxLength={6} value={repetido} onChange={soloNumeros(setRepetido)} />
            </Campo>
            <button type="submit" className="boton" disabled={enviando}>Guardar PIN</button>
          </form>
        </>
      )}
    </section>
  )
}

const CONTACTO = [
  ['nombreComercial', 'Nombre comercial', 150],
  ['direccion', 'Dirección', 200],
  ['ciudad', 'Ciudad', 80],
  ['telefono', 'Teléfono', 20],
  ['correoContacto', 'Correo de contacto', 120],
]
const FORMA_DE_CORREO = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const textoDe = (empresa) => Object.fromEntries(CONTACTO.map(([campo]) => [campo, empresa[campo] ?? '']))

/**
 * Los datos de contacto que salen en el recibo. El administrador los edita; los demás roles solo los ven.
 * Siempre se mandan los cinco: el servidor reemplaza los cinco y lo que falte queda en blanco.
 */
function DatosDeContacto({ empresa, puedeEditar, alGuardar, alFallar }) {
  const [valores, setValores] = useState(() => textoDe(empresa))
  const [motivos, setMotivos] = useState({})
  const [enviando, setEnviando] = useState(false)

  if (!puedeEditar) {
    return (
      <section className="tarjeta empresa-contacto" aria-labelledby="t-contacto">
        <h2 id="t-contacto">Datos de contacto</h2>
        <dl className="empresa-lectura">
          {CONTACTO.map(([campo, etiqueta]) => (
            <div key={campo}><dt>{etiqueta}</dt><dd>{empresa[campo] || '—'}</dd></div>
          ))}
        </dl>
      </section>
    )
  }

  async function guardar(evento) {
    evento.preventDefault()
    const nuevos = {}
    const correo = valores.correoContacto.trim()
    if (correo !== '' && !FORMA_DE_CORREO.test(correo)) nuevos.correoContacto = 'Escribe un correo válido, como contacto@tienda.com.'
    setMotivos(nuevos)
    if (Object.keys(nuevos).length > 0) return
    setEnviando(true)
    try {
      const guardada = await api.guardarEmpresa(Object.fromEntries(
        CONTACTO.map(([campo]) => [campo, valores[campo].trim()]),
      ))
      setValores(textoDe(guardada))
      alGuardar(guardada)
    } catch (error) {
      alFallar(error.message || 'No se pudieron guardar los datos.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <section className="tarjeta empresa-contacto" aria-labelledby="t-contacto">
      <h2 id="t-contacto">Datos de contacto</h2>
      <p className="sub-linea">El nombre comercial, la dirección y el teléfono salen en el encabezado del recibo.</p>
      <form onSubmit={guardar} noValidate className="empresa-contacto-formulario">
        {CONTACTO.map(([campo, etiqueta, max]) => (
          <Campo key={campo} etiqueta={etiqueta} motivo={motivos[campo]}>
            <input
              type={campo === 'correoContacto' ? 'email' : 'text'}
              autoComplete="off"
              maxLength={max}
              value={valores[campo]}
              onChange={(e) => setValores((v) => ({ ...v, [campo]: e.target.value }))}
            />
          </Campo>
        ))}
        <div className="acciones">
          <button type="submit" className="boton" disabled={enviando}>Guardar cambios</button>
        </div>
      </form>
    </section>
  )
}

/**
 * Mi empresa.
 *
 * La ven el administrador, el vendedor y el cliente, de modo que aquí no
 * hay ni costos ni márgenes. Razón social y NIT (o CC) son de solo
 * lectura para todos: los cambia la plataforma. El administrador edita
 * los datos de contacto y el logo.
 *
 * Los impuestos son opcionales: no cobrar IVA es un estado válido, así
 * que la pantalla no lo marca como pendiente ni como error. Solo el
 * administrador, que es quien puede configurarlos, ve el enlace.
 */
export default function Empresa() {
  const { datos, cargando, error, sinPermiso, recargar } = useDatos(() => api.empresa())
  const [reemplazo, setReemplazo] = useState(null)
  const [fallo, setFallo] = useState(null)
  const [aviso, setAviso] = useState(null)
  const esAdmin = esAdministrador()
  const empresa = reemplazo ?? datos

  /**
   * Un logo y no varios: SubirImagen entrega siempre un arreglo, pero
   * aquí la casilla no admite selección múltiple y por tanto trae uno.
   *
   * No atrapa el error a propósito: lo recoge SubirImagen y lo anuncia
   * por onFallo. Atraparlo aquí hacía que el componente creyera que la
   * subida había ido bien y volviera a pedir la imagen sin motivo.
   */
  async function subirLogo(archivos) {
    setFallo(null)
    setAviso(null)
    setReemplazo(await api.subirLogo(await prepararImagen(archivos[0])))
    setAviso('Logo actualizado.')
  }

  function fallar(mensaje) {
    setAviso(null)
    setFallo(mensaje)
  }

  return (
    <div className="contenido" aria-busy={cargando || undefined}>
      <EncabezadoPagina
        titulo="Mi empresa"
        descripcion="Los datos con los que tu empresa aparece en el sistema."
      />

      {cargando && !empresa && <EstadoCarga mensaje="Cargando los datos de la empresa…" />}
      {!cargando && sinPermiso && <EstadoSinPermiso />}
      {!cargando && error && !sinPermiso && (
        <EstadoError error={error} alReintentar={recargar} />
      )}

      {empresa && (
        <>
          {fallo && <div className="aviso error" role="alert">{fallo}</div>}
          {aviso && <div className="aviso exito" role="status">{aviso}</div>}

          <section className="tarjeta empresa-perfil" aria-label="Datos de la empresa">
            <SubirImagen
              src={empresa.logoUrl}
              alt={`Logo de ${empresa.razonSocial}`}
              onSubir={subirLogo}
              onFallo={fallar}
              puedeEditar={esAdmin}
              redondo
            />
            <div className="empresa-datos">
              <p className="titulo-empresa">{empresa.razonSocial}</p>
              <p className="sub-linea">{empresa.tipoDocumento ?? 'NIT'} {empresa.nit}</p>
              {esAdmin && <p className="sub-linea">Para cambiarlos, contacta a soporte</p>}
              <p className="sub-linea">
                {esAdmin
                  ? 'Toca el logo para subir o reemplazarlo. JPEG, PNG o WebP; se ajusta solo, sin recortarlo ni deformarlo.'
                  : 'Solo el administrador puede cambiar el logo.'}
              </p>
            </div>
          </section>

          <DatosDeContacto
            key={`${empresa.nombreComercial}|${empresa.direccion}|${empresa.ciudad}|${empresa.telefono}|${empresa.correoContacto}`}
            empresa={empresa}
            puedeEditar={esAdmin}
            alGuardar={(guardada) => { setFallo(null); setReemplazo(guardada); setAviso('Datos guardados.') }}
            alFallar={(mensaje) => { setAviso(null); setFallo(mensaje) }}
          />

          {esAdmin && (
            <section className="tarjeta empresa-impuestos" aria-labelledby="t-impuestos">
              <h2 id="t-impuestos">Impuestos</h2>
              <p>
                Son opcionales. Si no cobras IVA no hay nada que configurar y
                tus precios salen sin impuesto.
              </p>
              <Link to="/impuestos">Configurar impuestos</Link>
            </section>
          )}

          {esAdmin && <MiPin />}
        </>
      )}
    </div>
  )
}

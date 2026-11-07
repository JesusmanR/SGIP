import { useRef, useState } from 'react'
import Aviso from '../../componentes/comun/Aviso.jsx'
import Confirmacion from '../../componentes/comun/Confirmacion.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import EstadoVacio from '../../componentes/comun/EstadoVacio.jsx'
import TablaOTarjetas from '../../componentes/comun/TablaOTarjetas.jsx'
import { apiPlataformaEmpresas } from '../../servicios/api/plataforma-empresas.js'

const ROLES = { ADMINISTRADOR: 'Administrador', VENDEDOR: 'Vendedor', CLIENTE: 'Cliente' }

/** Un rol que esta pantalla no conoce se muestra tal cual, en lugar de esconderlo. */
const rolEnTexto = (rol) => ROLES[rol] ?? rol

/** El estado va siempre en texto: el color no alcanza para quien no lo distingue. */
const estadoEnTexto = (u) => [u.activo ? 'Activo' : 'Desactivado', u.bloqueado ? 'Bloqueado' : null]
  .filter(Boolean).join(', ')

/**
 * Pantallas 74 y 75, «Accesos»: las personas de la empresa y, en quien hoy no
 * puede entrar por intentos fallidos, la única acción de acceso que existe en
 * el servidor, desbloquear. No hay ruta para ver ni cambiar contraseñas.
 *
 * El foco: la confirmación se abre debajo de la tabla y no en su lugar, de modo
 * que al cancelar o fallar el botón que la abrió sigue ahí y recibe el foco. Si
 * se desbloqueó, ese botón desaparece con la recarga y el foco va a la sección.
 */
export default function UsuariosEmpresa({ empresaId, usuarios, alExito }) {
  const { datos, cargando, error, sinPermiso, recargar } = usuarios
  const [confirmando, setConfirmando] = useState(null)
  const [enviando, setEnviando] = useState(false)
  const [errorAccion, setErrorAccion] = useState(null)
  const seccion = useRef(null)
  const desbloqueado = useRef(false)
  const enCurso = useRef(false)

  function abrir(persona) {
    setErrorAccion(null)
    desbloqueado.current = false
    setConfirmando(persona)
  }

  async function desbloquear() {
    if (enCurso.current || !confirmando) return
    enCurso.current = true
    setEnviando(true)
    const persona = confirmando
    try {
      await apiPlataformaEmpresas.desbloquearUsuario(empresaId, persona.id)
      desbloqueado.current = true
      setConfirmando(null)
      alExito(`Se desbloqueó a ${persona.nombre}.`)
      recargar()
    } catch (e) {
      setConfirmando(null)
      setErrorAccion(e.message || 'No se pudo completar la operación.')
    } finally {
      enCurso.current = false
      setEnviando(false)
    }
  }

  const columnas = [
    { clave: 'nombre', titulo: 'Nombre', principal: true },
    { clave: 'correo', titulo: 'Correo' },
    { clave: 'rol', titulo: 'Rol', render: (u) => rolEnTexto(u.rol) },
    { clave: 'estado', titulo: 'Estado', render: estadoEnTexto },
    {
      clave: 'acciones', titulo: 'Acciones',
      render: (u) => (u.bloqueado ? (
        <button
          type="button" className="boton secundario menudo"
          aria-label={`Desbloquear a ${u.nombre}`}
          onClick={() => abrir(u)}
        >
          Desbloquear
        </button>
      ) : null),
    },
  ]

  let lista
  if (sinPermiso) lista = <EstadoSinPermiso />
  else if (error) lista = <EstadoError error={error} alReintentar={recargar} />
  else if (cargando && !datos) lista = <EstadoCarga mensaje="Cargando usuarios…" />
  else if (datos.length === 0) {
    lista = <EstadoVacio titulo="Esta empresa no tiene usuarios" detalle="Su administrador los crea desde su propia cuenta." />
  } else {
    lista = (
      <TablaOTarjetas etiqueta="Usuarios de la empresa" columnas={columnas} filas={datos} claveFila={(u) => u.id} />
    )
  }

  return (
    <section className="tarjeta usuarios-empresa" aria-labelledby="usuarios-titulo" tabIndex={-1} ref={seccion}>
      <h2 id="usuarios-titulo">Usuarios de la empresa</h2>
      <p className="nota">
        Una cuenta se bloquea tras varios intentos fallidos de entrada. Desbloquearla no cambia la contraseña:
        la persona sigue entrando con la suya. El bloqueo también se quita solo, pasado un tiempo.
      </p>
      {errorAccion && <Aviso tipo="peligro">{errorAccion}</Aviso>}
      {lista}
      {confirmando && (
        <Confirmacion
          pregunta={`¿Desbloquear a ${confirmando.nombre}?`}
          detalle={`${confirmando.correo} podrá volver a intentar entrar. Esto no cambia su contraseña: sigue entrando con la suya.`}
          textoConfirmar="Sí, desbloquear"
          enviando={enviando}
          focoEstable={seccion}
          volverA={() => (desbloqueado.current ? seccion.current : undefined)}
          alConfirmar={desbloquear}
          alCancelar={() => setConfirmando(null)}
        />
      )}
    </section>
  )
}

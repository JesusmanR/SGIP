import { Link } from 'react-router-dom'
import EncabezadoPagina from '../../componentes/comun/EncabezadoPagina.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import EstadoVacio from '../../componentes/comun/EstadoVacio.jsx'
import TablaOTarjetas from '../../componentes/comun/TablaOTarjetas.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { apiUsuarios } from '../../servicios/api/usuarios.js'
import EstadoUsuario from './EstadoUsuario.jsx'
import { etiquetaDeRol } from './roles.js'

const COLUMNAS = [
  {
    clave: 'nombre', titulo: 'Nombre', principal: true,
    render: (u) => <Link to={`/usuarios/${u.usuarioId}`}>{u.nombre}</Link>,
  },
  { clave: 'correo', titulo: 'Correo' },
  { clave: 'rol', titulo: 'Rol', render: (u) => etiquetaDeRol(u.rol) },
  { clave: 'estado', titulo: 'Estado', render: (u) => <EstadoUsuario usuario={u} /> },
]

const NUEVO = (
  <Link to="/usuarios/nuevo" className="boton enlace-boton">Nuevo usuario</Link>
)

/** Pantalla P-20 · lista de usuarios de la empresa. */
export default function ListaUsuarios() {
  const { datos, cargando, error, sinPermiso, recargar } = useDatos(() => apiUsuarios.listar(), [])

  let cuerpo
  if (sinPermiso) cuerpo = <EstadoSinPermiso />
  else if (error) cuerpo = <EstadoError error={error} alReintentar={recargar} />
  else if (cargando && !datos) cuerpo = <EstadoCarga mensaje="Cargando usuarios…" />
  else if (datos.length === 0) {
    cuerpo = (
      <EstadoVacio
        titulo="Todavía no hay usuarios"
        detalle="Crea el primero para que pueda entrar al sistema."
      />
    )
  } else {
    cuerpo = (
      <TablaOTarjetas
        etiqueta="Usuarios"
        columnas={COLUMNAS}
        filas={datos}
        claveFila={(u) => u.usuarioId}
      />
    )
  }

  return (
    <div className="contenido usuarios">
      <EncabezadoPagina
        titulo="Usuarios"
        descripcion="Las personas que entran al sistema de tu negocio."
        acciones={sinPermiso ? null : NUEVO}
      />
      {cuerpo}
    </div>
  )
}

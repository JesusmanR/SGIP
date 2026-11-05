/**
 * El estado de una cuenta, siempre en texto: los colores de estado se
 * confunden en escala de grises (ADR-0009).
 *
 * Una cuenta puede estar a la vez activa y bloqueada, o inactiva y con el
 * cambio de contraseña pendiente, así que cada condición lleva su propia
 * etiqueta en lugar de elegir una sola.
 */
export default function EstadoUsuario({ usuario }) {
  return (
    <span className="estados-usuario">
      <span className={`estado-usuario ${usuario.activo ? 'activo' : 'inactivo'}`}>
        {usuario.activo ? 'Activo' : 'Inactivo'}
      </span>
      {usuario.bloqueado && <span className="estado-usuario bloqueado">Bloqueado</span>}
      {usuario.cambioClaveRequerido && (
        <span className="estado-usuario pendiente">Debe cambiar la contraseña</span>
      )}
    </span>
  )
}

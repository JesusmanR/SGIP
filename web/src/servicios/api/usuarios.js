import { pedir } from '../api.js'

/**
 * Llamadas de la sección Usuarios (RF-12). Solo las rutas que el backend
 * tiene hoy: no hay ninguna para restablecer contraseñas, y por eso aquí
 * tampoco.
 *
 * Los cuerpos se arman campo a campo: el backend no acepta la empresa ni
 * el correo al editar, y lo que no se manda no se puede mandar por error.
 */
export const apiUsuarios = {
  listar: () => pedir('/usuarios'),
  ver: (usuarioId) => pedir(`/usuarios/${usuarioId}`),
  crear: ({ nombre, correo, clave, rol }) =>
    pedir('/usuarios', { method: 'POST', body: { nombre, correo, clave, rol } }),
  editar: (usuarioId, { nombre, rol }) =>
    pedir(`/usuarios/${usuarioId}`, { method: 'PUT', body: { nombre, rol } }),
  activar: (usuarioId) => pedir(`/usuarios/${usuarioId}/activar`, { method: 'POST' }),
  desactivar: (usuarioId) => pedir(`/usuarios/${usuarioId}/desactivar`, { method: 'POST' }),
  desbloquear: (usuarioId) => pedir(`/usuarios/${usuarioId}/desbloquear`, { method: 'POST' }),
}

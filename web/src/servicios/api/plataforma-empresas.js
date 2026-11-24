import { pedir } from '../api.js'

const RAIZ = '/plataforma/empresas'

/** Lo que se deja en blanco viaja como nulo: el backend valida el correo aunque venga vacío. */
const opcional = (texto) => {
  const limpio = typeof texto === 'string' ? texto.trim() : texto
  return limpio === '' || limpio === undefined ? null : limpio
}

/**
 * Llamadas de la sección Empresas de la plataforma (RF-01 a RF-03 y ADR-0028).
 * Los usuarios de una empresa se listan para desbloquear a una persona; no hay
 * ruta para restablecer contraseñas, y por eso aquí tampoco.
 *
 * El alta no manda estado ni plan: el estado inicial lo fija el servidor.
 */
export const apiPlataformaEmpresas = {
  listar: () => pedir(RAIZ),
  ver: (empresaId) => pedir(`${RAIZ}/${empresaId}`),
  darDeAlta: (d) => pedir(RAIZ, {
    method: 'POST',
    body: {
      tipoDocumento: d.tipoDocumento ?? 'NIT',
      nit: d.nit,
      razonSocial: d.razonSocial,
      correoContacto: opcional(d.correoContacto),
      telefono: opcional(d.telefono),
      ciudad: opcional(d.ciudad),
      administradorNombre: d.administradorNombre,
      administradorCorreo: d.administradorCorreo,
      administradorClave: d.administradorClave,
    },
  }),
  /** SUPER_ADMIN: tipo (NIT o CC), número sin dígito de verificación, razón social y motivo. */
  cambiarIdentidad: (empresaId, d) => pedir(`${RAIZ}/${empresaId}/identidad`, {
    method: 'PUT',
    body: { tipoDocumento: d.tipoDocumento, numero: d.numero, razonSocial: d.razonSocial, motivo: d.motivo },
  }),
  activar: (empresaId, motivo) =>
    pedir(`${RAIZ}/${empresaId}/activar`, { method: 'POST', body: { motivo: opcional(motivo) } }),
  desactivar: (empresaId, motivo) =>
    pedir(`${RAIZ}/${empresaId}/desactivar`, { method: 'POST', body: { motivo: opcional(motivo) } }),
  /** Id, nombre, correo, rol, activo y bloqueado (hoy no puede entrar). Nunca la clave. */
  listarUsuarios: (empresaId) => pedir(`${RAIZ}/${empresaId}/usuarios`),
  desbloquearUsuario: (empresaId, usuarioId) =>
    pedir(`${RAIZ}/${empresaId}/usuarios/${usuarioId}/desbloquear`, { method: 'POST' }),
}

/**
 * Los roles que un administrador de empresa puede repartir
 * (ServicioUsuarios.ROLES_ASIGNABLES). SUPER_ADMIN no está a propósito.
 */
export const ROLES = [
  { valor: 'ADMINISTRADOR', etiqueta: 'Administrador' },
  { valor: 'VENDEDOR', etiqueta: 'Vendedor' },
  { valor: 'CLIENTE', etiqueta: 'Cliente' },
]

export function etiquetaDeRol(valor) {
  return ROLES.find((r) => r.valor === valor)?.etiqueta ?? valor
}

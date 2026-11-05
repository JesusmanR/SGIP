import { pedir } from '../api.js'

/**
 * Llamadas de la sección «Proveedores». El servidor solo ofrece listar
 * (los activos), ver y crear: no hay ruta para editar ni desactivar.
 */
export const listarProveedores = () => pedir('/proveedores')
export const verProveedor = (proveedorId) => pedir(`/proveedores/${proveedorId}`)
export const crearProveedor = (datos) => pedir('/proveedores', { method: 'POST', body: datos })

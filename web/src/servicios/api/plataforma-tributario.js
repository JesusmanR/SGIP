import { pedir } from '../api.js'

const RAIZ = '/plataforma/tributario'

const opcional = (texto) => {
  const limpio = typeof texto === 'string' ? texto.trim() : texto
  return limpio === '' || limpio === undefined ? null : limpio
}

/**
 * Llamadas de los datos tributarios nacionales (RF-05). Solo las rutas que
 * existen: no se crean tipos de impuesto, y del tipo solo se cambian
 * tarifa, tope y fundamento (no el código ni el tratamiento).
 */
export const apiPlataformaTributario = {
  listarUvt: () => pedir(`${RAIZ}/uvt`),
  alertaUvt: () => pedir(`${RAIZ}/alerta-uvt`),
  fijarUvt: (anio, { valor, resolucion, motivo }) => pedir(`${RAIZ}/uvt/${anio}`, {
    method: 'PUT',
    body: { valor, resolucion: opcional(resolucion), motivo: opcional(motivo) },
  }),
  listarImpuestos: () => pedir(`${RAIZ}/impuestos`),
  cambiarImpuesto: (codigo, { tarifa, umbralUvt, fundamento, motivo }) =>
    pedir(`${RAIZ}/impuestos/${encodeURIComponent(codigo)}`, {
      method: 'PUT',
      body: { tarifa, umbralUvt, fundamento, motivo },
    }),
}

import { pedir } from '../api.js'

/**
 * La prueba gratuita de la empresa (RF-73). Una lectura: responde también en
 * modo consulta, que es justo cuando la pantalla tiene que avisarlo.
 */
export const apiPrueba = {
  estado: () => pedir('/empresa/prueba'),
}

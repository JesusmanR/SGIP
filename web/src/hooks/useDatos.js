import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * Carga datos asíncronos para una pantalla.
 *
 * `cargar` devuelve una promesa; `dependencias` dicen cuándo volver a
 * pedir. Una respuesta que llega tarde, de una petición ya reemplazada o
 * de una pantalla que se desmontó, se ignora: sin eso, una búsqueda lenta
 * pisaría el resultado de la siguiente.
 *
 * Si cambian las dependencias, `datos` vuelve a null: son datos de otra
 * cosa (pasar del detalle 5 al 6 no debe enseñar el 5 mientras llega el 6).
 * Solo `recargar` conserva los datos a la vista mientras llegan los nuevos,
 * porque pide lo mismo otra vez.
 *
 * `sinPermiso` es verdadero ante un 403, para que la pantalla muestre
 * EstadoSinPermiso en lugar de un error genérico.
 */
export function useDatos(cargar, dependencias = []) {
  const [estado, setEstado] = useState({ datos: null, cargando: true, error: null })
  const [intento, setIntento] = useState(0)
  const intentoAnterior = useRef(0)

  // `cargar` suele ser una función nueva en cada render: lo que decide si
  // se vuelve a pedir son las dependencias que la pantalla declara.
  useEffect(() => {
    let vigente = true
    const esRecarga = intento !== intentoAnterior.current
    intentoAnterior.current = intento
    setEstado((previo) => ({ datos: esRecarga ? previo.datos : null, cargando: true, error: null }))
    Promise.resolve()
      .then(cargar)
      .then((datos) => { if (vigente) setEstado({ datos, cargando: false, error: null }) })
      .catch((error) => { if (vigente) setEstado({ datos: null, cargando: false, error }) })
    return () => { vigente = false }
  }, [...dependencias, intento])

  const recargar = useCallback(() => setIntento((n) => n + 1), [])

  return { ...estado, sinPermiso: estado.error?.estado === 403, recargar }
}

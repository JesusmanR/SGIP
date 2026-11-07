import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * Carga datos asíncronos para una pantalla.
 *
 * `cargar` devuelve una promesa; `dependencias` dicen cuándo volver a
 * pedir. Una respuesta que llega tarde, de una petición ya reemplazada o
 * de una pantalla que se desmontó, se ignora: sin eso, una búsqueda lenta
 * pisaría el resultado de la siguiente.
 *
 * Si cambian las dependencias, `datos` vuelve a null ya en ese mismo render: son datos de otra
 * cosa (pasar del detalle 5 al 6 no debe enseñar el 5 mientras llega el 6).
 * Solo `recargar` conserva los datos a la vista mientras llegan los nuevos,
 * porque pide lo mismo otra vez.
 *
 * `sinPermiso` es verdadero ante un 403, para que la pantalla muestre
 * EstadoSinPermiso en lugar de un error genérico.
 */
export function useDatos(cargar, dependencias = []) {
  const [estado, setEstado] = useState({ datos: null, cargando: true, error: null, deps: dependencias })
  const [intento, setIntento] = useState(0)
  const intentoAnterior = useRef(0)

  // `cargar` suele ser una función nueva en cada render: lo que decide si
  // se vuelve a pedir son las dependencias que la pantalla declara.
  useEffect(() => {
    let vigente = true
    const esRecarga = intento !== intentoAnterior.current
    intentoAnterior.current = intento
    setEstado((previo) => ({ datos: esRecarga ? previo.datos : null, cargando: true, error: null, deps: dependencias }))
    Promise.resolve()
      .then(cargar)
      .then((datos) => { if (vigente) setEstado({ datos, cargando: false, error: null, deps: dependencias }) })
      .catch((error) => { if (vigente) setEstado({ datos: null, cargando: false, error, deps: dependencias }) })
    return () => { vigente = false }
  }, [...dependencias, intento])

  const recargar = useCallback(() => setIntento((n) => n + 1), [])

  // El efecto corre después de pintar: en el render en que cambian las
  // dependencias el estado aún es de las anteriores. Se descarta aquí, para
  // que no haya un fotograma con datos de otra cosa.
  const mismasDeps = estado.deps.length === dependencias.length
    && estado.deps.every((d, i) => Object.is(d, dependencias[i]))
  const { datos, cargando, error } = mismasDeps
    ? estado
    : { datos: null, cargando: true, error: null }

  return { datos, cargando, error, sinPermiso: error?.estado === 403, recargar }
}

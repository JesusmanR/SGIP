import { leerSesion, borrarSesion } from './sesion.js'

/**
 * Único punto por el que el front habla con el backend.
 *
 * Centralizarlo tiene dos razones concretas: la cabecera de autorización
 * se arma en un solo sitio, y un 401 se traduce en un cierre de sesión
 * en lugar de en un error raro en mitad de una pantalla.
 */
export class ErrorApi extends Error {
  /**
   * `tipo` es el `type` del ProblemDetail. Un mismo estado tiene varias causas
   * (un 409 puede ser un código repetido o un producto que cambió), y el estado
   * solo no dice cuál: quien necesite distinguirlas mira el tipo.
   */
  constructor(estado, detalle, tipo, titulo) {
    super(detalle)
    this.estado = estado
    this.tipo = tipo
    this.titulo = titulo
  }
}

/**
 * Subida de archivos. No pone Content-Type a propósito: con FormData lo
 * fija el navegador, y tiene que incluir el «boundary» que separa las
 * partes. Escribirlo a mano rompe la petición de una forma que el
 * servidor reporta como «no llegó ningún archivo».
 */
async function subir(ruta, archivo) {
  const sesion = leerSesion()
  const cuerpo = new FormData()
  cuerpo.append('archivo', archivo)

  const cabeceras = {}
  if (sesion?.token) cabeceras.Authorization = `Bearer ${sesion.token}`

  const respuesta = await fetch(`/api/v1${ruta}`, { method: 'POST', headers: cabeceras, body: cuerpo })
  if (respuesta.status === 401) {
    borrarSesion()
    throw new ErrorApi(401, 'La sesión expiró. Vuelve a entrar.')
  }
  if (!respuesta.ok) {
    let detalle = 'No se pudo subir el archivo.'
    try {
      const json = await respuesta.json()
      if (json?.detail) detalle = json.detail
    } catch {
      // Sin cuerpo JSON queda el mensaje genérico.
    }
    throw new ErrorApi(respuesta.status, detalle)
  }
  return respuesta.json()
}

/**
 * Trae una imagen protegida y devuelve una URL que <img> puede usar.
 *
 * Hace falta porque una etiqueta <img src> no manda cabeceras: el
 * navegador la pide por su cuenta, sin Authorization, y el backend la
 * rechaza por anónima. Con fetch sí se puede autenticar, y el resultado
 * se envuelve en una URL de objeto.
 *
 * Quien la pide es responsable de liberarla con URL.revokeObjectURL
 * cuando deje de usarla: mientras no se haga, el navegador conserva esos
 * bytes en memoria.
 */
export async function imagenAutenticada(ruta) {
  const sesion = leerSesion()
  const cabeceras = {}
  if (sesion?.token) cabeceras.Authorization = `Bearer ${sesion.token}`

  const respuesta = await fetch(ruta, { headers: cabeceras })
  if (!respuesta.ok) {
    // A diferencia del resto, un 401 aquí NO cierra la sesión. Una imagen
    // que no carga no demuestra que la sesión caducó, y echar al usuario
    // por una miniatura rota es peor que mostrar la miniatura rota. Si la
    // sesión de verdad expiró, la próxima llamada de datos lo detecta.
    throw new ErrorApi(respuesta.status, 'No se pudo cargar la imagen.')
  }
  return URL.createObjectURL(await respuesta.blob())
}

/**
 * Descarga un archivo binario protegido y lo guarda con el nombre que manda el servidor.
 *
 * Como `imagenAutenticada`, un 401 NO cierra la sesión: una descarga que falla no
 * demuestra que la sesión caducó, y la próxima llamada de datos lo detecta. El nombre
 * sale de `Content-Disposition`; sin él queda `respaldo`.
 */
async function descargar(ruta, respaldo, falla) {
  const sesion = leerSesion()
  const cabeceras = {}
  if (sesion?.token) cabeceras.Authorization = `Bearer ${sesion.token}`

  const respuesta = await fetch(ruta, { headers: cabeceras })
  if (!respuesta.ok) {
    let detalle = falla
    let tipo
    try {
      const cuerpo = await respuesta.json()
      if (cuerpo?.detail) detalle = cuerpo.detail
      tipo = cuerpo?.type
    } catch {
      // Sin cuerpo JSON queda el mensaje genérico.
    }
    throw new ErrorApi(respuesta.status, detalle, tipo)
  }

  const disposicion = respuesta.headers?.get('Content-Disposition') ?? ''
  const nombre = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposicion)?.[1]
  const url = URL.createObjectURL(await respuesta.blob())
  const enlace = document.createElement('a')
  enlace.href = url
  enlace.download = nombre ? decodeURIComponent(nombre.trim()) : respaldo
  document.body.appendChild(enlace)
  enlace.click()
  enlace.remove()
  URL.revokeObjectURL(url)
}

export async function pedir(ruta, opciones = {}) {
  const sesion = leerSesion()
  const cabeceras = { ...opciones.headers }
  if (opciones.body !== undefined) cabeceras['Content-Type'] = 'application/json'
  // El ingreso es público y no lleva token. Si lo llevara, el servidor
  // autenticaría con la sesión que la pestaña aún guarde, y quien tiene el
  // cambio de contraseña pendiente (o la empresa en modo de consulta)
  // recibiría un 403 al intentar entrar de nuevo, como sí mismo o como otra
  // cuenta.
  if (sesion?.token && ruta !== '/auth/login') cabeceras.Authorization = `Bearer ${sesion.token}`

  const respuesta = await fetch(`/api/v1${ruta}`, {
    ...opciones,
    headers: cabeceras,
    body: opciones.body === undefined ? undefined : JSON.stringify(opciones.body),
  })

  // Un 401 del login significa «credenciales incorrectas», no «sesión
  // caducada». Si entrara aquí, un intento fallido borraría la sesión que
  // la pestaña ya tuviera; por eso cae en el tratamiento general de abajo.
  if (respuesta.status === 401 && ruta !== '/auth/login') {
    // El token caducó o alguien cerró la sesión desde otro dispositivo.
    borrarSesion()
    throw new ErrorApi(401, 'La sesión expiró. Vuelve a entrar.')
  }

  if (!respuesta.ok) {
    // El backend responde con ProblemDetail, que trae «detail».
    let detalle = 'No se pudo completar la operación.'
    let tipo
    let titulo
    try {
      const cuerpo = await respuesta.json()
      if (cuerpo?.detail) detalle = cuerpo.detail
      tipo = cuerpo?.type
      titulo = cuerpo?.title
    } catch {
      // Una respuesta sin cuerpo JSON deja el mensaje genérico.
    }
    throw new ErrorApi(respuesta.status, detalle, tipo, titulo)
  }

  if (respuesta.status === 204) return null
  return respuesta.json()
}

/**
 * Arma la cadena de consulta descartando lo que no se eligió.
 *
 * Mandar `categoria=` vacío no es lo mismo que no mandarla: el backend
 * recibiría una cadena en lugar de nulo y el filtro dejaría de anularse
 * a sí mismo. Se descartan nulo, indefinido y cadena vacía; el cero no,
 * porque es un identificador válido en otras consultas.
 */
function consulta(parametros) {
  const partes = Object.entries(parametros)
    .filter(([, valor]) => valor !== null && valor !== undefined && valor !== '')
    .map(([clave, valor]) => `${clave}=${encodeURIComponent(valor)}`)
  return partes.length === 0 ? '' : `?${partes.join('&')}`
}

export const api = {
  entrar: (correo, clave) => pedir('/auth/login', { method: 'POST', body: { correo, clave } }),

  // El panel de indicadores (P-02) en una sola llamada. No son cinco,
  // una por tarjeta, para que las cinco cifras vengan del mismo
  // instante: con cinco peticiones el conteo de agotadas podría ser de
  // antes de un movimiento y la tabla de críticas de después, y la
  // pantalla mostraría dos verdades que no concuerdan.
  panel: () => pedir('/panel'),
  salir: () => pedir('/auth/logout', { method: 'POST' }),
  // Los errores de contraseña llegan como 400 y no como 401, y es a
  // propósito: un 401 hace que `pedir` dé por expirada la sesión y la
  // borre, y equivocarse al teclear no debe echar a nadie.
  cambiarClave: (claveActual, claveNueva) =>
    pedir('/auth/clave', { method: 'POST', body: { claveActual, claveNueva } }),
  catalogo: ({ busqueda, categoria, proveedor, estado } = {}) =>
    pedir(`/productos${consulta({ busqueda, categoria, proveedor, estado })}`),
  producto: (productoId) => pedir(`/productos/${productoId}`),
  crearProducto: (datos) => pedir('/productos', { method: 'POST', body: datos }),
  editarProducto: (productoId, datos) =>
    pedir(`/productos/${productoId}`, { method: 'PUT', body: datos }),
  categorias: () => pedir('/categorias'),
  crearCategoria: (datos) => pedir('/categorias', { method: 'POST', body: datos }),
  editarCategoria: (categoriaId, datos) =>
    pedir(`/categorias/${categoriaId}`, { method: 'PUT', body: datos }),
  desactivarCategoria: (categoriaId) =>
    pedir(`/categorias/${categoriaId}/desactivar`, { method: 'POST' }),
  proveedores: () => pedir('/proveedores'),

  // Importación de listas de precios (P-07). Son tres llamadas y no una
  // a propósito: subir el archivo lo valida y lo deja registrado sin
  // tocar un solo costo, y aplicar o rechazar es la decisión que la
  // persona toma después de mirar la vista previa.
  cargarLista: (proveedorId, archivo) =>
    subir(`/importaciones?proveedor=${encodeURIComponent(proveedorId)}`, archivo),
  verCarga: (cargaId) => pedir(`/importaciones/${cargaId}`),
  aplicarCarga: (cargaId) =>
    pedir(`/importaciones/${cargaId}/aplicar`, { method: 'POST' }),
  rechazarCarga: (cargaId) =>
    pedir(`/importaciones/${cargaId}/rechazar`, { method: 'POST' }),
  /** La plantilla .xlsx con los productos del proveedor, lista para llenar los costos. */
  descargarPlantilla: (proveedorId) =>
    descargar(`/api/v1/importaciones/plantilla${consulta({ proveedorId })}`,
      'plantilla-precios.xlsx', 'No se pudo descargar la plantilla.'),
  empresa: () => pedir('/empresa'),
  /** Los cinco datos de contacto, siempre completos: lo que falte queda en blanco. Nunca el resumen del GET. */
  guardarEmpresa: (d) => pedir('/empresa', {
    method: 'PUT',
    body: {
      nombreComercial: d.nombreComercial, direccion: d.direccion, ciudad: d.ciudad,
      telefono: d.telefono, correoContacto: d.correoContacto,
    },
  }),
  // El PIN de autorización del administrador (caja). Nunca se lee: solo se sabe si ya hay uno.
  estadoPin: () => pedir('/perfil/pin'),
  fijarPin: (pinActual, pinNuevo) =>
    pedir('/perfil/pin', { method: 'PUT', body: { pinActual, pinNuevo } }),
  subirLogo: (archivo) => subir('/empresa/logo', archivo),
  subirImagen: (productoId, archivo) => subir(`/productos/${productoId}/imagenes`, archivo),
  imagenesDe: (productoId) => pedir(`/productos/${productoId}/imagenes`),
  borrarImagen: (imagenId) => pedir(`/imagenes/${imagenId}`, { method: 'DELETE' }),
  hacerPrincipal: (imagenId) => pedir(`/imagenes/${imagenId}/principal`, { method: 'POST' }),
}

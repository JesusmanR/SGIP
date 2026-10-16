/**
 * Prepara una imagen en el navegador antes de enviarla.
 *
 * Existe por dos defectos distintos que tienen la misma solución.
 *
 * 1. WebP se rechazaba con «la imagen está dañada o incompleta». El
 *    archivo estaba perfecto: la máquina virtual de Java no trae lector
 *    de WebP, de modo que el servidor reconocía el formato y después no
 *    sabía decodificarlo. El navegador sí sabe —es el formato que él
 *    mismo pide a media web—, así que lo convierte aquí y el servidor
 *    recibe algo que puede leer. El servidor sigue siendo la autoridad:
 *    valida y normaliza igual lo que llegue. Esto solo le ahorra un
 *    formato que no entiende.
 *
 * 2. Una foto de teléfono pesa entre tres y ocho megas y la pantalla
 *    final la muestra en 800 píxeles. Mandar los diez megapíxeles
 *    completos para que el servidor los tire es gastar la conexión del
 *    local en nada. Reducir a 1600 de lado deja el doble de lo que el
 *    servidor va a guardar, así que no se pierde calidad visible.
 */

/**
 * Tope de lo que el servidor admite recibir. Es copia de
 * AlmacenArchivos.BYTES_MAXIMOS, y a propósito: avisar aquí evita subir
 * veinte megas para que los rechacen al llegar. Si algún día los dos
 * números dejaran de coincidir, el servidor sigue siendo el que decide y
 * responde con su propio mensaje.
 */
export const TOPE_BYTES = 10 * 1024 * 1024

/** Lo que el selector de archivos ofrece. WebP entra porque se convierte. */
export const FORMATOS_ACEPTADOS = 'image/jpeg,image/png,image/webp'

/** Lado máximo tras reducir. El servidor recorta a 800; esto deja margen. */
const LADO_MAXIMO = 1600

/** Lo que el servidor puede decodificar por sí mismo. */
const LEGIBLES_EN_SERVIDOR = ['image/jpeg', 'image/png']

export class ErrorImagen extends Error {}

export async function prepararImagen(archivo) {
  if (LEGIBLES_EN_SERVIDOR.includes(archivo.type) && archivo.size <= TOPE_BYTES) {
    return archivo
  }
  return transcodificar(archivo)
}

async function transcodificar(archivo) {
  let mapa
  try {
    mapa = await createImageBitmap(archivo)
  } catch {
    // El navegador tampoco sabe leerlo: HEIC de iPhone, un TIFF, un
    // archivo roto de verdad. Aquí sí se puede culpar al archivo.
    throw new ErrorImagen(
      `El navegador no puede leer «${archivo.name}». Guárdala como JPEG o PNG y vuelve a intentarlo.`,
    )
  }

  const escala = Math.min(1, LADO_MAXIMO / Math.max(mapa.width, mapa.height))
  const ancho = Math.max(1, Math.round(mapa.width * escala))
  const alto = Math.max(1, Math.round(mapa.height * escala))

  const lienzo = document.createElement('canvas')
  lienzo.width = ancho
  lienzo.height = alto
  lienzo.getContext('2d').drawImage(mapa, 0, 0, ancho, alto)
  mapa.close?.()

  // PNG primero cuando el original podía tener transparencia: un logo
  // convertido a JPEG aparece con un rectángulo blanco alrededor.
  const puedeTenerTransparencia = archivo.type === 'image/png' || archivo.type === 'image/webp'
  let salida = puedeTenerTransparencia
    ? await aArchivo(lienzo, archivo.name, 'image/png')
    : await aArchivo(lienzo, archivo.name, 'image/jpeg', 0.9)

  // Un PNG de una fotografía pesa mucho más que el WebP del que salió.
  // Si se pasa del tope, la transparencia ya no es lo que está en juego.
  if (salida.size > TOPE_BYTES && salida.type === 'image/png') {
    salida = await aArchivo(lienzo, archivo.name, 'image/jpeg', 0.9)
  }
  if (salida.size > TOPE_BYTES) {
    throw new ErrorImagen(
      `«${archivo.name}» sigue pesando ${megas(salida.size)} MB tras reducirla, y el tope es ${megas(TOPE_BYTES)} MB.`,
    )
  }
  return salida
}

function aArchivo(lienzo, nombreOriginal, tipo, calidad) {
  return new Promise((resolver, rechazar) => {
    lienzo.toBlob(
      (blob) => {
        if (!blob) {
          rechazar(new ErrorImagen('No se pudo convertir la imagen en este navegador.'))
          return
        }
        // El nombre no lo usa el servidor —genera el suyo—, pero el
        // formulario necesita uno y la consola se lee mejor con él.
        const base = nombreOriginal.replace(/\.[^.]+$/, '')
        const extension = tipo === 'image/png' ? 'png' : 'jpg'
        resolver(new File([blob], `${base}.${extension}`, { type: tipo }))
      },
      tipo,
      calidad,
    )
  })
}

function megas(bytes) {
  return (bytes / 1024 / 1024).toFixed(1).replace('.0', '')
}

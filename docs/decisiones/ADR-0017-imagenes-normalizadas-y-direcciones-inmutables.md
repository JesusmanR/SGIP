# ADR-0017 · Las imágenes se normalizan al subirlas y se sirven por direcciones inmutables

Estado: aceptada · 2026

## Contexto

El catálogo necesita foto de producto (RF-63, hasta cinco por producto)
y la empresa su logo (RF-64, para encabezar el catálogo exportable y las
facturas). Las imágenes llegan de donde sea: de un teléfono, de la web
de un proveedor, de un archivo que alguien renombró.

Eso plantea tres preguntas que es tentador responder por separado, y que
en realidad están atadas:

1. **Dónde se ajusta el tamaño.** Las imágenes llegan con medidas y
   pesos dispares. Si no se unifican, la cuadrícula del catálogo se ve
   rota.
2. **Qué formatos se admiten.** Reconocer un formato por su firma y
   saber decodificarlo son dos cosas distintas, y confundirlas produce
   mensajes falsos.
3. **Por qué dirección se sirven.** Una imagen puede pedirse por lo que
   es —«la imagen 14»— o por el papel que cumple —«la principal del
   producto 6»—. La diferencia parece cosmética y gobierna la caché.

## Decisión

### 1. Normalizar al subir, no al mostrar

La imagen se transforma antes de tocar el disco. Lo que queda guardado
ya mide lo que debe medir.

Con dos reglas **opuestas a propósito**:

- **Foto de producto**: recorte al centro hasta quedar cuadrada, 800 ×
  800, JPEG. Que todas midan igual importa más que conservar los bordes.
- **Logo**: se encoge hasta caber entero en un cuadro de 400, sin
  recortar y sin deformar, PNG. Recortar un logo le corta el nombre a
  media empresa, y muchos son alargados. PNG conserva la transparencia,
  que es lo que permite ponerlo sobre cualquier fondo.

Un logo más pequeño que el cuadro se deja como está: ampliarlo solo lo
volvería borroso.

Antes de redimensionar se aplica la orientación EXIF. Al reescribir la
imagen se pierden sus metadatos, y un teléfono casi nunca gira los
píxeles: guarda la foto como salió del sensor y anota «esto va girado».
Borrar esa nota sin aplicarla pondría de lado fotos que se veían
derechas.

### 2. Los formatos admitidos se derivan de los que se pueden leer

`FormatoImagen` separa las dos preguntas: qué formato es (por los
primeros bytes) y si esta instalación sabe decodificarlo (preguntándole
a `ImageIO`, no dándolo por sabido).

De ahí salen el mensaje de error y la lista de admitidos. Si algún día
se añade la biblioteca que aporta un lector que hoy falta, ese formato
empieza a funcionar sin tocar código.

El navegador convierte a PNG lo que el servidor no sabe leer —hoy,
WebP— antes de enviarlo, y de paso reduce a 1600 de lado. El servidor
sigue siendo la autoridad: valida y normaliza igual lo que llegue.

### 3. Dos clases de dirección, y la caché sale de la clase

| Clase | Direcciones | Caché |
|---|---|---|
| Inmutable | `/imagenes/{id}`, `/imagenes/{id}/miniatura` | 7 días, privada |
| Vigente | `/productos/{id}/imagen`, `/empresa/logo` | `no-store` |

Una dirección **inmutable** nombra un archivo: esos bytes no cambian
nunca, y si la imagen se borra, la dirección deja de existir.

Una dirección **vigente** nombra un papel: cuál es la principal cambia
sin que cambie la dirección.

El catálogo recibe `imagenPrincipalId` y pide la miniatura por la
dirección inmutable de esa imagen. Cambiar de principal cambia la
dirección.

### 4. Un solo tope de tamaño, y el límite del contenedor por encima

`AlmacenArchivos.BYTES_MAXIMOS` es el único tope, y de él se derivan los
límites de multipart. Los límites del contenedor quedan **más altos**
que el nuestro, lo que parece al revés y no lo es: quien rechaza por
peso debe ser nuestro código, porque es el único que puede explicarlo.

El contenedor actúa solo como freno contra el abuso. No puede quedar sin
límite: un cuerpo sin tope se escribe en disco antes de que nuestro
código opine.

### 5. Las subidas múltiples son secuenciales

En paralelo se pisan: dos peticiones simultáneas sobre un producto sin
fotos leen las dos que no hay ninguna, las dos se declaran principal y
el índice único rechaza a la segunda; y las dos piden el mayor orden en
uso, obtienen el mismo número y chocan contra
`uq_producto_imagenes_orden`.

El esquema hace bien en impedirlo. El cliente no debe provocarlo.

## Consecuencias

**A favor**

- Todas las imágenes del catálogo miden igual, en la pantalla web, en el
  documento exportable y en cualquier cliente que venga después.
- La miniatura no puede mostrar una imagen distinta de la principal: el
  problema deja de poder ocurrir, en vez de quedar arreglado.
- El catálogo descarga ~15 KB por producto en vez de ~100 KB.
- Un mensaje de error nunca culpa al archivo de una limitación nuestra.
- Un cambio de tope llega a las demás máquinas con un `git pull`: vive
  en el código, no en el `application.yml`, que no se versiona.

**En contra**

- Normalizar cuesta CPU en cada subida, y se hace dos veces por imagen
  (la de 800 y la miniatura de 240). Es un coste por subida, no por
  lectura, de modo que se paga una vez.
- Se guardan dos archivos por imagen. El espacio sobra; la coherencia
  entre fila y archivos hay que mantenerla al borrar.
- La miniatura sale del original y no de la ya reducida —encoger dos
  veces acumula desenfoque—, lo que obliga a conservar el original en
  memoria durante la subida.
- Las imágenes anteriores a la V14 generan su miniatura al vuelo en cada
  petición. Son pocas; la alternativa era que un `GET` escribiera en la
  base y en el disco, que trae más problemas de los que ahorra.

## Nota sobre un defecto latente

La vista `v_precios_producto` resuelve `imagen_principal` con una
subconsulta que no repite el filtro de empresa. Hoy no puede devolver la
imagen de otra porque `producto_id` es único en toda la tabla, de modo
que es un defecto latente y no un agujero. `ConsultaCatalogo` no depende
de esa columna y usa su propia subconsulta, con el filtro repetido.
Conviene corregir la vista la próxima vez que se recree.

**Nota posterior (17 de octubre de 2026).** Corregido en la migración
V15, que repite el filtro de empresa en esa subconsulta y también en los
cuatro enlaces de la vista, que dependían de la misma propiedad no
enunciada. La vista devuelve exactamente lo mismo sobre datos
consistentes. `verificar_aislamiento.sh` construye en cada entrega la
situación que hoy el esquema impide —una fila de imagen de otra empresa
con el mismo `producto_id`, insertada con las claves foráneas
desactivadas— y comprueba que la vista no la devuelve: con la definición
anterior esa comprobación falla, que es lo que la hace valer.

# ADR-0035 · El PDF del recibo se genera con Apache PDFBox

Estado: aceptada · 2026-11-21

## Contexto

El recibo de una venta (RF-70) se imprime en una impresora de tirilla de 80 mm desde el
navegador, y también se puede obtener como PDF para enviarlo. El navegador ya sabe
imprimir el recibo (`ReciboImprimible.jsx`), pero guarda lo que le respondió el servidor
al registrar la venta: no sirve para reimprimir una venta de ayer ni para mandarla por
correo. Hace falta que el servidor sea la fuente del recibo, en JSON y en PDF.

El backend solo trae `poi-ooxml` (para leer hojas de cálculo), que no genera PDF. Un PDF
es una dependencia nueva, y por eso este ADR.

## Decisión

**Librería: Apache PDFBox 3.0.8** (`org.apache.pdfbox:pdfbox`), la última versión estable
de la 3.0 al día de hoy. Licencia Apache-2.0, mantenida por Apache, sin código nativo y
sin AWT. Se descartaron iText (AGPL, o de pago) y OpenPDF (más pensada para documentos
de varias páginas con maquetación automática, que aquí no hacen falta).

**Una sola clase la conoce.** `logica/ReciboPdf` es la única que importa PDFBox en
producción. Recibe el `Recibo` ya armado y devuelve los bytes; el resto del sistema no
sabe que existe una librería de PDF. Cambiarla no toca la API ni el servicio.

**Forma del documento.** Páginas de **80 mm de ancho** (226,77 pt) y de alto calculado:
primero se colocan los textos y se suma el alto, y después se crea la página con ese alto.
Es la misma tirilla de la pantalla. Casi siempre es una sola página; si el alto pasara de
14.400 pt (el máximo que los visores aceptan en una página PDF) el recibo sigue en otra
página del mismo ancho, nunca en una página gigante.

**La plantilla es sobria y está en un solo método** (`ReciboPdf.componer`): el usuario
diseñará el recibo después, y cambiarlo es tocar ese método y las constantes de tamaño.
Los importes van en pesos con punto de miles y las fechas en dd/mm/aaaa (RNF-16).

**Fuente: Liberation Sans incrustada, la que ya trae PDFBox.** El jar de `pdfbox` 3.0.8
incluye `org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf` (licencia SIL OFL).
Cada documento la carga con `PDType0Font.load(documento, flujo, true)` y la incrusta
subconjunta, de modo que el PDF se ve igual en cualquier visor y solo pesa lo que usa.
**No se usa el mapeador de fuentes de PDFBox**: ni las fuentes estándar (`PDType1Font`),
cuya creación hace que PDFBox recorra las fuentes instaladas del sistema y escriba una
caché en `~/.pdfbox.cache`, ni ninguna fuente descargada o instalada. `ReciboPdf` no toca
ninguna fuente al construirse ni al cargarse (sin bloque estático ni calentamiento), así
que arrancar la aplicación no cuesta nada y el primer recibo tarda lo mismo que el
último. Una prueba lo comprueba: tras generar un PDF, el mapeador de fuentes de PDFBox
sigue sin crearse y la ruta de caché de fuentes no existe.

Cada recibo carga su propia fuente: un `PDType0Font` guarda mapas que se llenan al usarlo y
PDFBox no es seguro entre hilos, así que no se comparte entre peticiones.

**Negrita falsa.** El jar solo trae la fuente normal. La negrita (título, total, cambio y
marcas) es la misma fuente con trazo (`FILL_STROKE`, trazo de 0,035 veces el tamaño). Se
prefirió a usar la normal un poco más grande porque el trazo no cambia el ancho de las
letras: los importes siguen alineados a la derecha y un título no pasa a otro renglón.
Si el usuario quiere una negrita de verdad, basta con empaquetar `LiberationSans-Bold.ttf`
(misma licencia) y cargarla aquí; es una decisión suya, no se descarga nada por su cuenta.

**Caracteres.** PDFBox lanza una excepción con cualquier carácter que la fuente no
tenga, y los nombres de producto los teclea el usuario, de modo que **todo texto pasa por
`ReciboPdf.limpiar`**: los espacios raros (de ancho especial, no separables) y los
controles pasan a un espacio, y cada carácter que la fuente no tiene (un emoji, un
alfabeto no latino) sale como «?». Tildes, eñe, `¿`, `¡`, `×`, `·` y la raya larga sí se
escriben. Un carácter raro nunca tumba el recibo.

**Seguridad y empresa.** El PDF y el JSON salen de la misma lectura
(`ServicioRecibos.recibo`), que filtra por la empresa de la sesión: la venta de otra
empresa es un 404, igual que en la consulta por consecutivo. No lleva costo ni utilidad.
La respuesta del PDF no se guarda en caché.

**Rendimiento.** Tres consultas por recibo, las mismas con una línea que con doscientas
(prueba de conteo con base). El PDF de 20 líneas sale en menos de 3 s en frío (prueba), y
en la práctica en unos 20 ms, incluida la carga de la fuente. La generación del PDF va
**fuera de la transacción**: el controlador lee el recibo (`ServicioRecibos.recibo`, de solo
lectura, que termina al volver) y después llama a `ReciboPdf.generar`, de modo que armar
el PDF no mantiene ocupada una conexión de la base.

## Consecuencias

- Una dependencia más en el `pom.xml` (con PDFBox llegan `fontbox`, `pdfbox-io` y
  `commons-logging`). Hay que seguirla con el resto de las versiones (ADR-0027).
- El recibo del servidor y el de la pantalla (`ReciboImprimible.jsx`) son dos plantillas
  con el mismo contenido. Cuando el usuario diseñe el recibo habrá que cambiar las dos, o
  hacer que la pantalla imprima desde el JSON del servidor.
- El PDF pesa unos 15 KB por la fuente subconjunta incrustada.
- La negrita es falsa (ver arriba); el diseño que haga el usuario puede cambiarla.

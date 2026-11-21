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

**Forma del documento.** Una sola página de **80 mm de ancho** (226,77 pt) y de alto
calculado: primero se colocan los textos y se suma el alto, y después se crea la página
con ese alto. Es la misma tirilla de la pantalla, sin cortes de página en medio de un
recibo.

**La plantilla es sobria y está en un solo método** (`ReciboPdf.componer`): el usuario
diseñará el recibo después, y cambiarlo es tocar ese método y las constantes de tamaño.
Los importes van en pesos con punto de miles y las fechas en dd/mm/aaaa (RNF-16).

**Fuentes y caracteres.** Se usan las fuentes estándar del PDF (Helvetica y Helvetica
Negrita, codificación WinAnsi): cubren las tildes, la eñe, `¿`, `¡`, `×`, `·` y la raya
larga, y no hay que incrustar ni empaquetar ningún archivo de fuente. PDFBox lanza una
excepción con cualquier carácter que la fuente no pueda escribir, y los nombres de
producto los teclea el usuario, de modo que **todo texto pasa por `ReciboPdf.limpiar`**:
los espacios raros (de ancho especial, no separables) y los controles pasan a un espacio,
y lo que la fuente no codifica (un emoji, un alfabeto no latino) sale como «?». Un
carácter raro nunca tumba el recibo.

**Seguridad y empresa.** El PDF y el JSON salen de la misma lectura
(`ServicioRecibos.recibo`), que filtra por la empresa de la sesión: la venta de otra
empresa es un 404, igual que en la consulta por consecutivo. No lleva costo ni utilidad.
La respuesta del PDF no se guarda en caché.

**Rendimiento.** Tres consultas por recibo, las mismas con una línea que con doscientas
(prueba de conteo con base). El PDF de 20 líneas sale en menos de 3 s (prueba), y en la
práctica en unas pocas decenas de milisegundos una vez arrancada la aplicación.
PDFBox, al crear su primera fuente, recorre las fuentes instaladas en el sistema para
buscar un sustituto; eso pasa una vez, al cargar `ReciboPdf` (que el servicio crea al
arrancar la aplicación), no en la primera petición.

## Consecuencias

- Una dependencia más en el `pom.xml` (con PDFBox llegan `fontbox`, `pdfbox-io` y
  `commons-logging`). Hay que seguirla con el resto de las versiones (ADR-0027).
- El recibo del servidor y el de la pantalla (`ReciboImprimible.jsx`) son dos plantillas
  con el mismo contenido. Cuando el usuario diseñe el recibo habrá que cambiar las dos, o
  hacer que la pantalla imprima desde el JSON del servidor.
- Una venta de más de unas pocas centenas de líneas haría una tirilla más larga que el
  máximo de una página PDF (14.400 pt); la venta admite 200 líneas, que caben.

SGIP - Pantallas
================

png/  Imagenes a 2x para insertar en el informe o en diapositivas.
svg/  Vectores editables. Se abren en Figma, Illustrator o Inkscape sin
      perder calidad, y el texto sigue siendo texto.

Para llevarlos a Figma: arrastra el .svg al lienzo. Cada rectangulo,
texto e icono entra como capa independiente y se puede editar.

SGIP_Prompt_Figma.md reconstruye el sistema completo en Figma desde
cero, en seis bloques que se pegan en orden. El orden importa: el
bloque 1 crea las variables de color y el resto las referencia por
nombre.

Paleta: Aurora Mist
-------------------
Los valores de referencia estan en ../../web/src/estilos/tokens.css,
que es la unica fuente de color del front. Si un color cambia, cambia
alli y estas pantallas se regeneran; no al contrario.

Superficie    lienzo #F4F7F6   tarjeta #FFFFFF
              chrome #203959   chrome-activo #2C4A66
Acento        acento #15707F   claro #5EB6C6   sutil #E3EFF1
Texto         tinta #16283E    tinta-2 #416772   tinta-3 #5C6866
Linea         borde #D5DEDB    borde-control #839492
Neutros       salvia #96ADA5   bruma #ABB7B3
Estado        exito #15624A    aviso #8A5214
              peligro #9C2F28  info #2C5A78

Tipografia: Inter (https://rsms.me/inter), pesos 400, 500, 600 y 700.
Cifras con numeracion tabular.

Los cuatro colores de estado son casi el mismo gris en escala de
grises: caben en el 7,6% del rango, y peligro e info se diferencian en
0,9 puntos sobre 255. Por eso LA INSIGNIA DE ESTADO SIEMPRE LLEVA
TEXTO. Nunca un punto de color solo. El detalle, con las luminancias
medidas, esta en tokens.css.

Correspondencia
---------------
P-01   Acceso, version de ordenador        Figura 23
P-01m  Acceso, version movil               Figura 24
P-02   Panel de indicadores                Figura 25
P-03   Catalogo de productos               Figura 26
P-04   Ficha de producto                   Figura 27
P-05   Registro de producto                Figura 28
P-06   Gestion de proveedores              Figura 29
P-07   Importacion de lista de precios     Figura 30
P-08   Configuracion de reglas de margen   Figura 31
P-09   Generacion de cotizacion            Figura 32
P-10   Lectura de codigo de barras         Figura 33
P-11   Ingresos y egresos                  Figura 34
P-12   Rotacion de inventario              Figura 35
P-13   Emision y consulta de facturas      Figura 36

Que falta por disenar
---------------------
Las catorce pantallas son de un sistema de una sola empresa. Faltan:

  - Alta de empresa                       (super administrador)
  - Listado de empresas con suscripcion   (super administrador)
  - Cobro con seleccion de medio de pago  (caja)
  - Cierre de turno                       (caja)
  - Estado de sincronizacion              (RF-56)

La de sincronizacion no es un adorno: un cajero que no ve cuantas
operaciones tiene pendientes no confia en el modo sin conexion, y si no
confia, no lo usa.

Tambien faltan los estados de error, carga y vacio de cada pantalla.
Estan especificados en el documento de mock-ups pero no disenados. Un
diseno que solo resuelve el caso feliz esta a la mitad.

Sobre SGIP_Mockups.pdf
----------------------
El documento de mock-ups que esta en ../informe/ se produjo antes del
cambio de paleta: sus figuras conservan la paleta anterior, con el rojo
Black Steel. El texto sigue siendo valido; las imagenes, no. Las
vigentes son las de estas carpetas.

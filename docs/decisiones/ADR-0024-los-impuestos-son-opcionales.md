# ADR-0024 · Los impuestos son opcionales: una empresa vende sin configurarlos

Estado: aceptada · 2026 · enmienda el ADR-0016

## Contexto

El ADR-0016 hizo que una empresa recién creada naciera con todos los
interruptores tributarios en falso y escribió esta consecuencia: «La
RN-25 impide vender hasta que alguien elija». En la práctica eso se
traducía en dos cosas:

- `v_regla_iva` solo devolvía filas para productos que tenían una regla de
  IVA. Un producto sin regla salía de `v_precios_producto` sin tratamiento,
  sin tarifa y con el total vacío, con `tributacion_sin_configurar = 1`.
- El catálogo lo mostraba como «Sin impuesto configurado» y el texto
  daba a entender que sin configurar no se podía vender.

El supuesto detrás era que todo comercio cobra impuesto sobre las ventas.
El propio ADR-0016 empezaba negando ese supuesto («muchos comercios
pequeños no son responsables de IVA») y aun así dejaba la puerta cerrada
hasta que alguien pasara por la configuración. Una tienda de barrio que no
está obligada a cobrar nada tenía que configurar impuestos para poder
vender.

## Decisión

**Los impuestos son opcionales.** Una empresa puede vender sin haber
configurado ninguno, y el sistema nunca pone un total vacío en una venta.

- Una empresa nace **no responsable de IVA** (el valor por omisión de
  `empresa_config.responsable_iva` es falso, sin cambios). Sin reglas,
  sus productos salen `NO_GRAVADO`, con tarifa 0 y el total igual al
  precio.
- `v_regla_iva` devuelve **una fila por producto**, aunque no tenga regla
  (`LEFT JOIN`), y expone `responsable_iva`. Si la empresa es responsable y
  el producto no tiene regla vigente, el tratamiento queda vacío y la
  tarifa es 0: el total sigue siendo el precio.
- `tributacion_sin_configurar` vale 1 **solo** cuando la empresa se declara
  responsable de IVA y el producto no tiene regla. Es un aviso: quien dijo
  que cobra el impuesto y no le dijo al sistema cuál se olvidó de algo. No
  impide vender.
- La RN-25, entendida como «no se vende sin configuración tributaria», se
  retira. `configuracion_completa` sigue existiendo, pero como marca para
  la pantalla —si alguien ya guardó la configuración—, no como permiso.

| Empresa | Regla | `tratamiento` | `sin_configurar` | total |
|---|---|---|---|---|
| No responsable | ninguna | `NO_GRAVADO` | 0 | igual al precio |
| Responsable | ninguna | vacío | 1 | igual al precio |
| Responsable | general `IVA_19` | `GRAVADO` | 0 | precio + 19 % |

La empresa configura sus impuestos por la API (`/api/v1/empresa/tributacion`,
solo el administrador): los interruptores, la tarifa de ICA y las reglas de
IVA, que se **crean y se cierran** pero no se editan ni se borran, porque
una venta se explica con la regla que regía el día que se hizo.

## Lo que no cambia

- La plataforma sigue siendo quien publica el catálogo de tipos de impuesto
  y sus tarifas (ADR-0016): la empresa escoge cuál le aplica, no cuánto
  vale. La tarifa de ICA sigue siendo la única excepción.
- Una regla cuyo tipo de impuesto la plataforma desactivó se sigue
  ignorando, y no tapa a la regla general válida.
- Un producto sin regla de **ganancia** sigue sin precio. Eso es otra
  pregunta y no la toca esta decisión.

## Consecuencias

**Un total nunca depende de un NULL.** Las cuatro columnas de
`v_precios_producto` que usaban `ri.tarifa` ahora usan `COALESCE(ri.tarifa,
0)`, y la vista de reglas da tarifa 0 cuando no hay regla. La V34 es la
migración.

**El aviso se calla para quien no cobra impuestos.** Antes, toda empresa
nueva veía «Sin impuesto configurado» en cada producto. Ahora lo ve solo
la que se declaró responsable y no ha creado la regla.

**Hay una pregunta que esta decisión no resuelve y deja a quien opera el
negocio:** si una empresa debería ser responsable de IVA es una obligación
legal que depende de sus ingresos y su actividad. El sistema la deja
elegir y avisa cuando la elección está incompleta; no la decide por ella.

# ADR-0004 · Los umbrales tributarios se almacenan en UVT

Estado: aceptada · 2026

## Contexto

El artículo 424 del Estatuto Tributario excluye del impuesto sobre las
ventas los computadores personales hasta 50 UVT y los dispositivos
móviles inteligentes hasta 22 UVT. La exclusión opera por debajo del
umbral: el equipo económico no causa el impuesto y el que supera el tope
sí lo causa a la tarifa general.

El valor de la unidad lo fija la DIAN por resolución cada año.

## Decisión

`reglas_iva.umbral_uvt` guarda el tope en unidades, no en pesos. La tabla
`uvt_anual` guarda el valor por año gravable y la función
`fn_valor_uvt(fecha)` hace la conversión según la fecha del documento.

## Consecuencias

El tope en pesos se actualiza cargando una fila en `uvt_anual` cada
enero, sin tocar las reglas. Un documento emitido el año pasado se
reconstruye con el valor que regía entonces.

Si el año no está cargado, la función devuelve nulo y la vista trata el
producto como gravado. Es el comportamiento conservador: cobra el
impuesto en lugar de omitirlo. Omitirlo sería un problema tributario;
cobrarlo de más es un error que el cliente reclama y se corrige.

Cargar `uvt_anual` antes de que empiece cada año es una tarea operativa
que nadie va a recordar sola. Conviene una alerta al sistema cuando falte
el año en curso.

## Nota posterior

Los umbrales siguen almacenándose en UVT, y por las mismas razones.

Lo que cambió es su alcance: dejaron de aplicarse a toda empresa. Los
topes del artículo 424 solo le sirven a quien vende computadores o
dispositivos móviles, de modo que cada empresa declara si aplica la
exclusión por umbral. Para quien no la aplica, ese tratamiento se
comporta como gravado a su tarifa. Ver ADR-0016.

Los umbrales ya no viven en `reglas_iva` sino en `tipos_impuesto`, el
catálogo que mantiene la plataforma.

Hay un efecto del umbral que tardó en verse, porque solo aparece al
mirar las dos formas de pago juntas. El tope se mide contra el **precio
de venta**, no contra el costo, y el margen a crédito es mayor que el de
contado. Existe entonces una franja de costos en la que el mismo
producto queda excluido si se vende de contado y gravado si se vende a
crédito. En la base de demostración, con la UVT de 2026 en 52.374 pesos
(50 UVT = 2.618.700):

| | contado | crédito |
|---|---|---|
| PC-001, costo 2.100.000 | 2.520.000 · tarifa 0 % | 2.625.000 · tarifa 19 % |
| total a pagar | 2.520.000 | 3.123.750 |

La diferencia entre las dos modalidades no es el 5 % del margen: son
603.750 pesos. Un vendedor que ofrezca el crédito mirando el precio sin
impuesto se queda corto en más de medio millón y lo descubre al
facturar.

La vista calculaba `total_contado` y no su equivalente a crédito, de
modo que esa cifra no estaba en ningún sitio.
`V20__la_vista_de_precios_cierra_la_modalidad_a_credito.sql` añade
`total_credito`, el aviso `cerca_umbral_credito` y la bandera
`cruza_umbral_en_credito`, que marca justamente el caso de la tabla: la
única de las tres que no se puede deducir mirando una sola columna, y la
que le permite a la pantalla explicar por qué el crédito cuesta más de
lo que el porcentaje de interés sugiere.

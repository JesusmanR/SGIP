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

# ADR-0014 · La existencia negativa es la deuda de reposición

Estado: aceptada · 2026

## Contexto

El alcance exige que el administrador pueda **vender sin existencia**: un
cliente pide diez unidades, hay seis, y el administrador decide
comprometer las cuatro que faltan porque el proveedor entrega mañana.

El esquema lo impedía activamente. `ck_productos_existencia` exigía
`existencia >= 0`, de modo que el descuento fallaba. Y la columna
generada `estado_stock` clasificaba cualquier valor no positivo de una
manera que no distinguía «agotado» de «debo unidades».

Hay dos salidas: una tabla aparte de pedidos comprometidos, o permitir
que la existencia baje de cero.

## Decisión

**La existencia puede quedar negativa.** La restricción se relaja a
`existencia >= -9999`.

La cifra negativa **es** la deuda de reposición: −4 significa que se
vendieron cuatro unidades que hay que reponer. No hace falta derivarla
de ningún sitio ni mantenerla sincronizada con otra tabla.

`estado_stock` clasifica cualquier existencia menor o igual a cero como
`AGOTADO`, nunca como `CRITICO`. Antes esto estaba mal: un valor
negativo caía en el rango de «crítico», y un producto que se debe no es
un producto con poco inventario.

Solo el **administrador** autoriza la venta sin existencia. La venta
registra quién autorizó y por qué, en `ventas.autorizada_por` y
`ventas.motivo_sin_existencia`, y queda marcada con
`reposicion_pendiente` para que aparezca en la lista de lo que hay que
reponer.

## Por qué no una tabla de comprometidos

Porque duplicaría la verdad. Con una tabla aparte, la existencia
disponible real pasa a ser `existencia - comprometido`, y entonces hay
dos cifras que pueden desalinearse: cada consulta, cada vista y cada
informe tiene que acordarse de restar. Con el negativo, la existencia
disponible es la existencia, y punto.

El límite de −9999 no es arbitrario ni es un control de negocio: es una
cota de cordura. Un producto con existencia −10000 no refleja una deuda,
refleja un defecto en el código que descuenta.

## Consecuencias

**Sirve para dos cosas distintas con el mismo mecanismo.** Además de la
venta autorizada, resuelve la reconciliación de la operación sin
conexión: si dos dispositivos venden la última unidad sin verse, al
sincronizar la existencia queda en −1 y eso es exactamente lo que pasó.
Ver ADR-0012.

**La valorización de inventario puede dar negativo** para una
referencia. `v_inventario_valorizado` lo va a mostrar, y está bien que
lo muestre: es pasivo, no error.

**Alguien tiene que mirar la lista de reposiciones pendientes.** El
mecanismo no cobra la deuda por sí solo. Si nadie revisa
`reposicion_pendiente`, el negativo se queda ahí y la tienda cree tener
existencia que no tiene.

**La pantalla debe hacer visible la autorización.** Vender sin
existencia no puede verse igual que vender normal, o el vendedor va a
pedir autorización por costumbre y el administrador va a concederla sin
leer. El motivo es obligatorio justamente para que cueste un poco.

## Nota posterior

La decisión sigue en pie: la existencia negativa es la deuda de
reposición, con el piso de −9999 como cota de cordura. Lo que falló fue
su aplicación, en dos sitios distintos.

**El permiso se dio a medias.** La migración V9 relajó
`ck_productos_existencia` en `productos` y no tocó
`movimientos_inventario`, que traía de la V2 dos restricciones con piso
en cero: `ck_movimientos_anterior` y `ck_movimientos_posterior`. El
esquema quedaba permitiendo dejar el saldo en negativo y prohibiendo
anotar por qué. Como cada cambio de existencia deja su rastro en el
kárdex, una venta sin existencia habría hecho las dos cosas en la misma
transacción y la transacción entera se habría abortado.

No se notó antes porque todavía nada escribe en esa tabla: el módulo de
ventas no está construido. Era un defecto latente, y por eso se cerró
antes de construirlo y no después, cuando el síntoma habría llegado
disfrazado de error de la capa de Java. La migración
`V18__movimientos_admiten_existencia_negativa.sql` lleva las dos
restricciones al mismo piso que la V9 puso en `productos`. Que los tres
pisos coincidan importa: si el del kárdex fuera más estrecho, habría
estados alcanzables en `productos` que el kárdex no podría explicar.

`verificar_aislamiento.sh` registra desde ahora un movimiento que deja el
saldo en negativo. Esa comprobación falla con las restricciones de la V2
y pasa con las de la V18, que es lo que la hace valer.

**La consecuencia sobre la valorización se revisó.** Esta decisión
anotaba que `v_inventario_valorizado` puede dar negativo para una
referencia y que estaba bien que lo mostrara. Preverlo fue acertado,
pero esa columna se lee sumada, y sumada no mostraba un pasivo al lado
del activo: lo restaba. Ver ADR-0019.

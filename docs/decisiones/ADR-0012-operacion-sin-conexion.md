# ADR-0012 · Operación sin conexión con cola e idempotencia

Estado: aceptada · 2026

## Contexto

La aplicación es web, y los comercios a los que atiende están en barrios
donde el internet se cae. Una tienda que vende por unidad no puede
detener la caja porque el proveedor de red tenga una avería: deja de
facturar y pierde al cliente que está en el mostrador.

De ahí salen los clientes de escritorio y móvil. No existen para dar una
experiencia distinta a la web: existen para que haya caja cuando no hay
red.

## Decisión

Los clientes de escritorio y móvil guardan localmente lo necesario para
cobrar y **encolan las operaciones** que no pudieron enviar. Al recuperar
la conexión las envían en orden.

Cada operación lleva una **clave de idempotencia** generada en el
dispositivo. La tabla `cola_sincronizacion` la declara única.

Cuando el servidor recibe una clave que ya tiene, rechaza la inserción
con error 1062. **El cliente trata ese error como éxito**, no como
fallo: significa que la operación ya se aplicó.

Si al sincronizar una venta el producto ya no tiene existencia —porque
otro turno o otro dispositivo lo vendió— la operación **se aplica
igualmente** y la existencia queda negativa. Ver ADR-0014.

## Por qué la clave la genera el dispositivo

Porque el problema que resuelve es justamente que el dispositivo no sabe
si su envío llegó. Una conexión que se corta después de que el servidor
procesó la venta, pero antes de que la respuesta vuelva, es
indistinguible desde el cliente de una que se cortó antes. Sin clave, el
reintento cobra dos veces; con clave generada por el servidor, no hay
nada que comparar.

La alternativa era que el cliente consultara antes de reintentar. Se
descartó: la consulta puede fallar por la misma razón que falló el
envío, y entonces no se sabe más que antes.

## Consecuencias

**La existencia negativa deja de ser una anomalía y pasa a ser el
mecanismo de reconciliación.** El esquema lo admite hasta −9999.

**Hace falta una pantalla de estado de sincronización.** Un cajero que
no ve cuántas operaciones tiene pendientes no confía en el modo sin
conexión, y si no confía, no lo usa: apaga la caja y espera. Esa
pantalla es parte del requisito, no un adorno. Está pendiente de
diseñar.

**El almacén local guarda datos de una sola empresa.** Si dos comercios
comparten un equipo, cambiar de sesión tiene que vaciarlo, o el
aislamiento que el servidor garantiza se rompe en el dispositivo.

**Un precio guardado localmente envejece.** La lista cambia cuando el
administrador importa la del proveedor. Al reconectar hay que refrescar
antes de seguir cobrando, y advertir si se cobró con un precio ya
cambiado. Esto no está resuelto y es la parte más incómoda de la
decisión: una venta sincronizada con precio viejo es correcta como
documento histórico —el precio se congela en la línea— pero el margen
real no es el que el informe va a mostrar.

**No se resuelve el conflicto de edición.** Si dos dispositivos editan
el mismo producto sin conexión, gana el último que sincroniza. Para
ventas, que son hechos acumulativos, eso no importa; para datos
editables, sí. Por eso la cola admite solo tres tipos de operación:
venta, cierre de caja y ajuste de existencia. La edición de catálogo
exige conexión.

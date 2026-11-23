# ADR-0033 · El saldo a favor es del cliente identificado y no vence

Estado: aceptada · 2026-11-19

## Contexto

Al devolver una venta, el administrador elige cómo se le devuelve el dinero al cliente
(diseño de la caja, apartado 10): en efectivo, por la misma forma en que pagó, o como
**saldo a favor**, y se pueden combinar. El saldo a favor es dinero que la tienda le debe
al cliente y que este gasta en una compra posterior. Es nuevo en el sistema y toca tres
cosas que ya existían: el cobro de una venta (un medio de pago más), el cierre de turno
(que cuenta el dinero que pasó por la caja) y los bloqueos que evitan que dos operaciones
se pisen (ADR-0030).

Ya estaba dicho en la V40 que habría un medio de pago de sistema, «Saldo a favor»
(`medios_pago.tipo = 'SALDO_A_FAVOR'`), que toda empresa trae desde su alta, inactivo, y
que el cajero no elige entre los medios corrientes.

## Decisión

**Qué es.** Una cifra por cliente y empresa (`saldos_cliente`, nunca negativa por
restricción de la base) y un libro de movimientos que la explica (`movimientos_saldo`).
Un **abono** nace de una devolución con un reembolso en el medio de sistema; un **uso**
ocurre al pagar una venta con ese medio. Cada movimiento dice de dónde viene (la
devolución o la venta) y la base lo exige. El saldo se paga con el medio de sistema de la
empresa; `GET /api/v1/caja/clientes/{id}/saldo` responde `{saldo, medioId}` para que la
pantalla de la caja sepa con qué medio pagar.

**Por qué no admite al Consumidor final.** El saldo es una deuda de la tienda con una
persona. El «Consumidor final» es un cliente genérico que se comparte entre todos los
compradores sin datos: un saldo a su nombre lo podría gastar cualquiera, y no habría
nadie a quien reclamarle ni a quien devolverle el dinero. Por eso un reembolso a saldo de
una venta del Consumidor final es un 400, pagar con saldo siendo Consumidor final también,
y consultar su saldo responde cero. Quien quiera el saldo tiene que identificarse.

**Por qué no vence.** Es dinero del cliente, no una promoción: la tienda ya lo cobró y lo
debe. Un vencimiento sería quedarse con ese dinero, algo que el comercio puede querer
decidir pero que el sistema no debe decidir por él en silencio. Tampoco hay una pantalla ni
una regla que fije un plazo. Si un día se pide (por política de la empresa o por norma), es
una columna y una tarea de limpieza que escribirán un movimiento de tipo nuevo, sin
cambiar lo que hay.

**Tampoco se retira en efectivo.** Una vez abonado, el saldo solo se gasta comprando. Para
devolver el dinero en efectivo hay que hacerlo en la devolución, no después.

**Lo que se pagó con saldo vuelve solo como saldo** (decisión del usuario, 2026-11-19). El saldo
nunca se convierte en dinero: si una venta se pagó en parte con saldo a favor, esa parte se
devuelve como saldo y no en efectivo, tarjeta ni transferencia. La regla es por venta y
acumulada: la suma de lo reembolsado por medios distintos del saldo a favor, contando las
devoluciones anteriores de esa venta, no puede pasar de la suma de lo pagado por esos mismos
medios (`pagos_venta`, por monto y no por efectivo recibido). Pasarse es un 400 con un texto
claro. Lo pagado en efectivo, tarjeta o transferencia sí puede volver como saldo (el cliente lo
gasta después). `GET /api/v1/caja/ventas?consecutivo=` devuelve `reembolsadoSinSaldo`, lo ya
reembolsado por esos medios, para que la pantalla acote lo que queda por devolver en ellos.

**El pago con saldo no pasa por la caja.** Un pago con el medio de sistema se guarda sin
`cierre_id` (V44): no entra al efectivo esperado de ningún turno ni a sus totales por
medio. En cambio, una devolución cuyo reembolso va a saldo sí se registra en el turno
abierto de quien la hace (como cualquier devolución con dinero): el dinero no sale de la
caja, pero el turno es el que responde por la operación. El dinero que se reembolsa en
efectivo resta del esperado de ese turno (cierre, tarea 10). *(Enmendado el 2026-11-23: el
administrador sin turno puede devolver sin efectivo; ver la enmienda al final.)*

**Solo en la venta.** El cobro posterior de un domicilio contra entrega **no** admite
saldo: no toma el bloqueo del saldo del cliente, y un pago con saldo sin descontarlo sería
dinero regalado. Se rechaza con un 400 de texto fijo. Si hace falta, se añade tomando ese
bloqueo, que ya está en el orden global.

**Bloqueos.** El saldo del cliente es el cuarto bloqueo del orden global de la caja
(turno, venta, productos, **saldo**, consecutivo, kárdex; ADR-0030).
- Gastarlo: `SELECT ... FOR UPDATE` de la fila; si no existe, no hay saldo (400).
- Abonarlo: un solo `INSERT ... ON DUPLICATE KEY UPDATE saldo = saldo + :monto`, que crea
  la fila o pide directamente el bloqueo exclusivo. Un `INSERT IGNORE` seguido de una
  lectura toma un bloqueo compartido y, con tres transacciones a la vez, produce un ciclo
  (es la misma razón de `RepositorioConsecutivos`).
- La venta que paga con saldo lo bloquea **al principio**, antes de crear nada: el saldo va
  antes que cualquier consecutivo (la caja ya no crea productos; ver ADR-0014).

**Qué no se cachea.** Nada: el saldo es un dato que cambia con cada devolución y cada
venta, y se lee siempre de la base.

## Otras decisiones de la misma entrega

- **El valor de lo devuelto.** `HALF_UP(total de la línea · k / n)` a pesos enteros, con el
  total ya descontado y con IVA; la devolución que completa la línea toma el resto, de modo
  que lo devuelto en total es exactamente lo que se pagó por la línea (una línea de 1 000
  con tres unidades devuelve 333, 333 y 334).
- **Reembolso = lo realmente pagado.** Venta pagada: los reembolsos suman el valor devuelto
  (400 si no). Venta contra entrega sin cobrar: no se pagó nada, no hay reembolso, no se
  exige turno y lo devuelto rebaja lo que queda por cobrar; si no queda nada, la venta se
  anula (`estado` y `estado_pago` ANULADA).
- **Estados de la venta.** `REGISTRADA`, `DEVUELTA_PARCIAL` y `ANULADA`. Una venta
  parcialmente devuelta sigue viva: se puede entregar, cobrar (por el saldo actualizado) y
  devolver más. Una anulada no.
- **Una devolución de una venta pagada que se anula queda con `estado_pago = PAGADA`.** El
  dinero se recibió; los reembolsos dicen cuánto se devolvió.
- **Idempotencia.** La solicitud lleva `clave` (UUID), como la venta. Se comprueba con la venta
  ya bloqueada (y antes del PIN, en la fachada): el reintento de una devolución registrada
  recibe 200 con la respuesta de entonces (el estado en que quedó la venta y lo que quedó por
  cobrar se guardan con la devolución), sin PIN ni turno. La misma clave con otra carga (la
  huella no incluye el PIN ni el orden) o en otra venta es un 409 con el tipo
  `urn:sgip:caja:clave-en-uso`; la segunda la atrapa `uq_devoluciones_idempotencia`.

## Consecuencias

- Hay cinco tablas nuevas (V46) y doce claves foráneas compuestas con `empresa_id`.
- El cierre de turno (tarea 10) cuenta lo reembolsado en efectivo por `devoluciones.cierre_id`
  y excluye los pagos con saldo (no tienen `cierre_id`). Las devoluciones sin turno de la
  enmienda tienen `cierre_id` nulo y por eso ningún cierre las cuenta.
- Si algún día el saldo debe vencer, retirarse o transferirse, se añade sin cambiar lo
  guardado: un movimiento de tipo nuevo.
- Quien consulte los reembolsos de una devolución de saldo no verá referencia: el medio de
  sistema no la lleva.

## Enmienda · 2026-11-23: el administrador devuelve sin turno, pero sin efectivo

Sustituye la regla de que toda devolución con dinero necesita el turno abierto de quien la
hace. La decisión la tomó el usuario en las mejoras posteriores a la muestra (diseño: M4 de
`docs/superpowers/specs/2026-11-21-mejoras-post-muestra-design.md`).

- **Quién y qué.** Se evalúa por el **rol del usuario en sesión** (`ContextoEmpresa.esAdministrador()`),
  nunca por quien autoriza con PIN. Un `ADMINISTRADOR` sin turno abierto puede registrar una
  devolución de una venta pagada si **ningún** reembolso es de tipo `EFECTIVO`: valen tarjeta,
  transferencia, saldo a favor o una mezcla. La devolución queda con `devoluciones.cierre_id`
  nulo.
- **Con efectivo, 409.** Si algún reembolso es en efectivo, responde 409
  `urn:sgip:caja:turno-requerido-para-efectivo` con «Abre un turno de caja para devolver
  efectivo.». El tipo de cada reembolso sale del medio de pago de la empresa que ya se cargó
  para validarlo; no hay una segunda carga. No queda nada a medias: la comprobación va antes
  de insertar y la transacción lo revierte todo.
- **El vendedor sigue igual.** Sin turno recibe el 409 de siempre (`SinTurnoAbiertoException`),
  aunque traiga el PIN de un administrador.
- **Con turno, igual que antes.** Un administrador con turno que devuelve solo con tarjeta
  asocia la devolución a ese turno. Un reembolso de 0 sigue siendo 400.
- **Orden de bloqueos (ADR-0030).** No cambia. Sin turno no hay fila de turno que tomar; el
  resto (venta, productos, saldo, consecutivo, kárdex) sigue igual. El turno que se lee al
  empezar es el que decide: si otro se abre mientras la devolución espera la venta, no se
  queda con ella.
- **Cuadre.** Esas devoluciones no entran en ningún cierre, porque ningún efectivo sale de un
  cajón. Se ven en el listado de devoluciones y en los reportes futuros. Esa brecha queda
  aceptada por escrito.

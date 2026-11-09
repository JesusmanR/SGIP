# Diseño: la caja (punto de venta), Sprint 9

Fecha: 2026-11-09. Las decisiones salen de la conversación de diseño con el usuario de ese mismo día.

## 1. Para qué y por qué

SGIP ya tiene catálogo, precios, compras e inventario, pero todavía no vende. La caja es el lugar donde el cliente final compra. Puede atenderlo un **vendedor** o el **administrador de empresa**.

La caja tiene que:
- vender rápido en el mostrador;
- controlar el dinero de cada turno;
- dejar al administrador las decisiones delicadas sin frenar la fila;
- entregar siempre un recibo.

Más adelante el recibo se reemplazará por la factura electrónica, que hoy no existe y tendrá que poder conectarse después (ADR-0005, RF-46).

**Qué sería un éxito:**
- Una venta normal, escanear, cobrar e imprimir, se hace sin pasos innecesarios.
- Al cerrar el turno, el efectivo cuadra o la diferencia queda explicada.
- Nada que mueva dinero o inventario fuera de lo normal ocurre sin que el administrador lo autorice y quede registrado quién lo hizo.

**Qué sería un fracaso, aunque funcione:**
- Que un vendedor pueda regalar, descontar, devolver dinero o vender lo que no hay sin el administrador.
- Que una empresa vea datos de otra.
- Que el vendedor vea costos.
- Que una venta quede a medias: cobrada sin inventario descontado, o al revés.

## 2. Entregas

El usuario decidió dos entregas seguidas, bajo este mismo diseño:

- **Entrega 1, la caja:** apartados 3 a 11.
- **Entrega 2, las cotizaciones:** apartado 12. Hoy no existe la pantalla para crearlas, así que se construye aquí y después se conecta a la caja.

La caja se puede usar sin esperar a la entrega 2.

## 3. Turno de caja (RF-45)

Decisión del usuario: **cada cajero abre y cierra su propio turno.**

- **Apertura.** El cajero escribe la base de efectivo que hay en el cajón. Se guardan el cajero, la fecha y la hora, y la base. Un cajero no puede tener dos turnos abiertos (409).
- **Ventas.** Solo se vende con un turno abierto (409 si no lo hay) y cada venta queda asociada a su turno.
- **Cierre.**
  - El cajero cuenta el efectivo y escribe el total.
  - La caja calcula el efectivo esperado: base + ventas en efectivo − devoluciones en efectivo. Muestra la diferencia, sobrante o faltante, y el total recibido por tarjeta y por transferencia.
  - Una diferencia distinta de cero exige una observación.
  - Un turno cerrado no acepta ventas (409).
- **El administrador** ve todos los cierres de su empresa con sus diferencias. El vendedor solo ve los suyos.

Se usa la tabla existente `cierres_caja` (V10), ampliándola con la base y los totales por medio de pago si hace falta.

## 4. Buscar y agregar productos

Decisión del usuario: **escanear con cualquiera de los dos medios; si no aparece, buscar a mano; si sigue sin aparecer, decide el administrador.**

1. **Escanear.**
   - **Lector de código de barras del computador.** Se comporta como un teclado: escribe el código y pulsa Enter. El campo de búsqueda de la caja tiene el foco siempre que no haya otro diálogo abierto.
   - **Cámara del celular.**
   - Las dos formas buscan por `codigo_barras`. RNF-03 pide menos de 500 ms en el servidor.
2. **Búsqueda manual** por nombre o código, con resultados mientras se escribe.
3. **Producto no registrado.** Solo con la autorización del administrador (apartado 9), la caja permite crearlo en ese momento con los datos mínimos: nombre, precio de venta y el código escaneado si lo hay.
   - El producto queda creado para la próxima vez.
   - Como su existencia es 0, venderlo pasa por la venta sin existencia (apartado 5).
   - Sin autorización no se vende.

**Reglas del carrito:**
- Las cantidades son enteras (ADR-0013).
- El precio lo calcula **solo el servidor**, con las vistas de V38. La caja nunca envía un precio unitario que el servidor acepte sin recalcularlo.
- Un producto sin precio, con precio cero o con un precio desbordado no se puede vender. La caja lo dice claramente: «Este producto no tiene precio de venta». Esto resuelve M8.
- El vendedor nunca ve el costo (decisión del 2026-10-31).
- Pendientes de la revisión final que esta entrega debe cerrar:
  - **M6.** Al guardar `venta_items`, el `tratamiento_iva` `EXCLUIDO_HASTA_UMBRAL` se convierte en `GRAVADO` o `EXCLUIDO` según la tarifa aplicada.
  - **M7.** La consulta de la caja trae lo que necesita para calcular la venta.
  - **M12.** El mensaje de código de barras repetido dice si el código pertenece a un producto inactivo.
  - **M13.** Hay una prueba con base del 409 por `uq_productos_barras` en carrera.

## 5. Venta sin existencia: encargo (RF-42, RN-13)

Decisión del usuario: **solo el administrador**, porque es quien sabe los tiempos de los proveedores.

- Si un producto registrado no tiene existencia suficiente, la caja lo avisa y ofrece «Vender como encargo», que exige la autorización del administrador (apartado 9).
- **Existencia parcial** (decisión del orquestador). Si se piden 5 y hay 3, las 3 se venden y entregan normal; solo las 2 que faltan son encargo. El encargo deja de estar pendiente en el reporte de forma automática cuando la existencia vuelve a 0 o más (RF-69). La entrega al cliente se marca a mano.
- El administrador escribe:
  - el **motivo** (obligatorio, como dice hoy RF-42);
  - el **plazo de entrega estimado** que el cliente aceptó. Este dato es nuevo frente a RF-42.
- La existencia queda negativa.
- El kárdex guarda quién autorizó.
- La venta aparece en el reporte de pendientes (RF-69) hasta que entre la mercancía.
- El recibo muestra «Encargo — entrega estimada: <fecha>».
- Si el vendedor intenta vender sin existencia sin autorización, recibe 403. El super administrador recibe 403 siempre.

## 6. Cliente de la venta (RF-66)

Decisión del usuario:
- **Datos mínimos:** nombre, número de identificación y correo electrónico.
- **Venta a domicilio:** además teléfono y dirección, y la ciudad cuando no sea local.
- **«Consumidor final»:** hay un botón visible para vender sin datos. Usa el registro predefinido de RF-66.
- Las ventas a domicilio **nunca** pueden ser a Consumidor final.

Más reglas:
- La caja recuerda al cliente. Al escribir una identificación que ya existe en la empresa, completa los demás datos y deja corregirlos.
- La identificación es única por empresa (409).
- Se pide la autorización de tratamiento de datos (RNF-18). Sin ella, el cliente no se guarda (400).
- Se usa la tabla existente `clientes` (V4 + V10). Hay que comprobar su unicidad por empresa y que el «Consumidor final» exista en cada empresa, incluidas las ya creadas: sembrarlo en el alta y rellenarlo con una migración.

## 7. Cobro (RF-43, RF-44)

Decisión del usuario: **efectivo, tarjeta y transferencia, combinables en una misma venta.**

- **Efectivo.** Se escribe lo que entrega el cliente y la caja calcula el cambio (las «vueltas»).
- **Tarjeta.** Se cobra en el datáfono que la tienda ya tiene, que **no** se conecta con la caja. En la caja se anota el número de aprobación.
- **Transferencia** (Nequi, Daviplata o banco). Se anota la referencia.
- **Pago mixto.** Varios pagos, cada uno con su medio, su monto y su referencia. En contado, la suma tiene que igualar el total (400 si falta). Un medio no habilitado devuelve 400.
- Cada empresa administra sus medios de pago (RF-43). No se puede desactivar el único activo (409).
  - **M17:** las empresas que no tengan medios de pago reciben efectivo, tarjeta y transferencia mediante una migración.
  - **M9:** el medio de pago se registra por pago y no en la venta. La columna `ventas.medio_id` deja de usarse o se elimina; el constructor decide y lo justifica.
- **Pago contra entrega (solo en domicilios).** Decisión del usuario: unas ventas a domicilio se pagan por adelantado y otras contra entrega; los domicilios los hace un tercero.
  - Una venta a domicilio puede quedar **por cobrar** (`estado_pago = PENDIENTE`). El inventario se descuenta al registrarla, porque la mercancía sale de la tienda.
  - Cuando llega el dinero, se registra el cobro con su medio y su referencia. Ese cobro cuenta en el turno abierto de quien lo registra en ese momento, no en el turno de la venta.
  - La lista de «por cobrar» muestra cada venta pendiente con su valor y su antigüedad.
  - Si el domicilio no se entrega, se resuelve con una anulación (apartado 10).
  - Esto fue decisión del orquestador: un cobro parcial contra entrega no se admite; se cobra el total o nada.
  - En el mostrador, toda venta se paga completa en el momento.
- **Atomicidad (RF-41).** La venta, sus líneas, sus pagos y el descuento de inventario con su kárdex van en **una sola transacción**. Si algo falla, no queda nada guardado. Doble clic o reintento no deben producir dos ventas: hace falta una clave de idempotencia por venta.
- **Numeración.** El consecutivo de venta es por empresa (V12).

## 8. Descuentos (RF-36)

Decisión del usuario: **solo el administrador da descuentos.**

- **Forma, según el usuario:** en **porcentaje o en pesos**.
  - Se aplica **sobre un producto** (una línea) o **sobre toda la compra**. Esta segunda parte la propuso el diseño y el usuario no la corrigió.
  - Un descuento sobre toda la compra se reparte entre las líneas en proporción a su valor, para calcular bien el impuesto de cada una y para que una devolución parcial devuelva la parte que le toca. Fue decisión del orquestador.
  - Un descuento en pesos no puede superar el valor de la línea o de la compra: 400.
- Si el cliente pide un descuento y atiende un vendedor, se necesita la autorización del administrador (apartado 9).
- El recibo muestra el precio, el descuento y el total.
- Queda registrado quién autorizó.
- Esto **cambia RF-36**, que hoy dice «límite por rol»; el documento de requisitos se actualiza.
- Un descuento negativo o mayor al 100 % devuelve 400.

## 9. Autorización del administrador con PIN

Decisión del usuario: **en la misma caja, con un PIN del administrador.**

Se usa en cuatro casos:
- vender sin existencia (5);
- crear un producto no registrado (4);
- dar un descuento (8);
- hacer una devolución (10).

**Cómo funciona:**
- El administrador se acerca y escribe en la pantalla del vendedor su **PIN personal de 4 a 6 dígitos**, distinto de su contraseña.
- **Esto lo decidió el orquestador; el usuario puede revisarlo.**
  - El administrador **elige su nombre** en la lista de administradores de la empresa y escribe su PIN. El servidor nunca prueba un PIN contra todos los administradores.
  - Los intentos fallidos y el bloqueo se cuentan **por administrador**.
  - Con un solo PIN se autoriza a la vez todo lo que la venta necesita (por ejemplo, un descuento y un encargo). Antes de pedir el PIN, la pantalla muestra la lista de lo que se va a autorizar.
  - El PIN viaja dentro de la petición que confirma la operación.
- La operación continúa en esa misma pantalla y queda registrado quién atendió y quién autorizó.
- Si quien atiende es el propio administrador, no se le pide el PIN.

**Seguridad:**
- El PIN se guarda derivado (como las contraseñas, ADR-0002) y nunca se muestra.
- Lo crea y lo cambia el propio administrador desde su perfil.
- Cada autorización es de un solo uso y para **una** operación concreta. El servidor la valida dentro de la misma petición de esa operación, de modo que no se puede reutilizar para otra.
- Tras 5 PIN errados, el PIN queda bloqueado 15 minutos, igual que la cuenta (ADR-0028). El bloqueo queda en la bitácora.
- La autorización a distancia (desde el celular del administrador) queda **fuera**.
- Hace falta un ADR nuevo.

## 10. Anulaciones y devoluciones (RF-68)

Decisión del usuario: **solo el administrador.**

- El vendedor puede iniciar la devolución desde la caja, pero se completa solo con la autorización del administrador (apartado 9).
- Puede ser total o parcial. El motivo es obligatorio (400 si falta). Devolver más de lo vendido da 400, y anular algo ya anulado da 409.
- El producto vuelve al inventario con su kárdex.
- **El administrador elige cómo devolver el dinero.** Es decisión del usuario.
  - Puede ser en efectivo, por la misma forma en que se pagó o como saldo a favor, y se pueden combinar.
  - Lo que se devuelve en efectivo sale del turno abierto en ese momento y se resta del efectivo esperado del cierre.
  - Una devolución por tarjeta o por transferencia se hace por fuera (en el datáfono o en el banco) y se anota con su referencia.
  - El total devuelto nunca supera lo pagado por las unidades devueltas.
  - El saldo a favor es nuevo. Queda ligado al cliente identificado, nunca a Consumidor final, y se puede usar como forma de pago en una compra posterior.
- La devolución descuenta del turno abierto de quien la registra (RF-45).
- Queda registrado quién, cuándo y por qué.

## 11. Entrega, domicilio y recibo

**Entrega en el mostrador.** Si hay existencia, el cliente se lleva el producto al confirmar el pago.

**Domicilio.** Decisión del usuario, opción A:
- La venta se marca como **domicilio** con dirección, teléfono, ciudad y quién recibe.
- Aparece una lista de **pendientes por despachar** que el administrador o el vendedor marcan como entregados.
- Los domicilios los hace **un tercero**. El pago puede ser por adelantado o **contra entrega** (apartado 7): la lista separa «por despachar» de «por cobrar».
- Transportadora, guía, costo de envío y estados intermedios quedan **fuera**; se harán en un módulo de envíos posterior.
- Una venta «virtual» (por WhatsApp, por teléfono…) se registra igual en la caja. Esto es una suposición del diseño que el usuario no corrigió.

**Recibo (RF-70).** Decisión del usuario:
- Se imprime en una **impresora de tirilla de 80 mm** desde el navegador.
- También se puede obtener como **PDF** para enviarlo.
- Contenido mínimo de RF-70: empresa e identificación, fecha y hora, cajero, cliente, líneas, descuento, impuesto discriminado cuando aplique, pagos por medio, cambio, total, y las marcas de encargo y domicilio.
- **El diseño visual del recibo lo hará el usuario más adelante.** En la entrega 1 la plantilla es sobria y fácil de cambiar, sin un diseño cerrado.
- El recibo no sustituye la factura electrónica.

**Conexión.** Decisión del usuario: **la caja necesita internet en esta versión.** Si se cae la conexión, lo dice claramente y no deja cobrar a medias. La venta sin conexión (RF-51 a RF-55) queda para la versión siguiente.

## 12. Entrega 2: cotizaciones (RF-37, RF-39, RF-40)

- **Crear una cotización** (RF-37): varias líneas, cliente y vigencia en días hábiles. Por omisión son 5 días hábiles y se descuentan los festivos de RF-05. Los precios e impuestos quedan congelados en cada línea y la cotización no toca el inventario.
- **PDF** con la identidad de la empresa (RF-39).
- **Convertirla en venta desde la caja** (RF-40). Decisión del usuario:
  - La caja busca la cotización **por su número** y la carga al carrito.
  - Si **está vigente**, se cobra con los precios congelados.
  - Si **ya venció**, se carga igual, pero con los **precios de hoy**, y la caja lo avisa. Esto **cambia RF-40**, que hoy devuelve 409 para una cotización vencida.
  - Una cotización ya convertida no se puede volver a convertir (409).
  - La venta queda ligada a la cotización (`ventas.cotizacion_id`).
- Se usan las tablas existentes `cotizaciones` y `cotizacion_items`.

## 13. Fuera de alcance

- Venta sin conexión.
- Factura electrónica y nota crédito electrónica.
- Datáfono integrado.
- Módulo de envíos.
- Autorización remota.
- Ventas a crédito y fiado. El usuario no las pidió. La única venta que se cobra después es la de domicilio contra entrega.
- Envío del recibo por correo desde el servidor, si no existe ya un servicio de correo.
- Varias bodegas.

## 14. Restricciones

- **Aislamiento.** Toda consulta filtra por `empresa_id` (ADR-0011). Una empresa en modo consulta, o con la prueba vencida, no vende (403, `FiltroEmpresaActiva`).
- **Roles.** Venden el administrador de empresa y el vendedor. El super administrador no vende (403).
- **Rendimiento** (RNF-03):
  - buscar por código y operaciones de la caja: menos de 500 ms;
  - el número de consultas por venta es constante y no crece con el número de líneas más allá de lo inevitable; se protege con pruebas;
  - PDF: menos de 3 s.
- **«Hoy» y la hora** salen del `Clock` de la aplicación, en hora de Bogotá (ADR-0030).
- **Bloqueos.** Se respeta el orden empresa → carga → productos → histórico (ADR-0030) y se bloquean los productos de una venta en orden ascendente de id.
- **Proceso.** TDD, una rama por entrega, `code-reviewer` antes de cada merge y ADR para el PIN y para el saldo a favor.
- **UI.** Skill `impeccable` en modo Operate. `tokens.css` es la única fuente de color. Morado como acento (ADR-0026). Pesos colombianos. Fechas según RNF-16. En el celular, tarjetas en vez de tablas cortadas.

## 15. Cambios al documento de requisitos (v5)

- RF-36: solo el administrador da descuentos, mediante su PIN. El descuento se da en porcentaje o en pesos, por producto o sobre toda la compra.
- RF-44: la venta a domicilio admite el pago contra entrega, con estado «por cobrar».
- RF-40: una cotización vencida se convierte con los precios de hoy y un aviso, en lugar de devolver 409.
- RF-42: el encargo añade el plazo de entrega estimado y muestra la marca en el recibo.
- RF-66: los datos mínimos son nombre, identificación y correo; para domicilio, además teléfono, dirección y ciudad; el botón de Consumidor final.
- Requisitos nuevos:
  - PIN de autorización del administrador;
  - venta a domicilio con lista de pendientes por despachar;
  - saldo a favor del cliente.

## 16. Lo que decide el constructor

- El modelo exacto de las tablas nuevas: pagos de una venta, autorizaciones, saldo a favor y datos de domicilio.
- Las migraciones desde V40, o el número siguiente libre.
- La librería para leer códigos con la cámara: preferir la API `BarcodeDetector` del navegador y, si no existe, una librería mantenida.
- Cómo se imprime en 80 mm (CSS de impresión) y cómo se genera el PDF.
- La forma de las pantallas, dentro de las reglas de `impeccable`.

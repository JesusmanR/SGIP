# SGIP — Esquema de base de datos

MySQL 9.7 LTS. La versión mínima absoluta es 8.0.16, porque las
anteriores ignoran en silencio las restricciones `CHECK`, pero MySQL 8.0
llegó a fin de vida en abril de 2026 y no conviene empezar ahí.

No sirve XAMPP ni WAMP: traen MariaDB, que no tiene la colación
`utf8mb4_0900_ai_ci` y falla en la primera tabla.

El esquema completo son **39 tablas, 6 vistas y 4 funciones**.

## Aviso: la V9 anterior al 12 de octubre de 2026 se detenía a medias

La primera versión de `V9__rol_super_admin_y_venta_sin_existencia.sql`
borraba la columna generada `estado_stock` y después creaba el índice
`ix_productos_estado`. El `CREATE INDEX` fallaba con error 1061, nombre
de clave duplicado.

La causa: `ix_productos_estado` es un índice de dos columnas,
`(estado_stock, activo)`. Al borrar la columna generada, MySQL **no
elimina el índice**: lo conserva sobre la columna que queda. El nombre
sigue ocupado.

El cliente `mysql` se detiene en el primer error, de modo que todo lo
posterior —las columnas de venta sin existencia en `ventas` y sus
parámetros— nunca se creaba. Sin mensaje visible al final del todo.

Para saber si te pasó:

```sql
SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema='sgip' AND table_name='ventas'
   AND column_name='sin_existencia';
```

Si devuelve `0`, el esquema quedó incompleto. La solución es recrear la
base desde cero con la V9 corregida, que retira el índice antes de borrar
la columna.

## Antes de aplicar V9 o V10 sobre datos que te importan

**Haz una copia.** MySQL confirma cada sentencia de definición de datos
por su cuenta: una migración no se deshace con `ROLLBACK`. Si falla a la
mitad, el esquema queda a medio camino.

```bash
mysqldump -u root -p sgip > respaldo_antes_de_la_migracion.sql
```

## Orden de ejecución

```bash
bash aplicar.sh -u root -p --recrear
```

O manualmente, respetando el orden, porque hay claves foráneas entre
migraciones:

```bash
mysql -u root -p < migrations/00__crear_base.sql
mysql -u root -p sgip < migrations/V1__seguridad.sql
mysql -u root -p sgip < migrations/V2__catalogo.sql
mysql -u root -p sgip < migrations/V3__parametrizacion.sql
mysql -u root -p sgip < migrations/V4__comercial.sql
mysql -u root -p sgip < migrations/V5__funciones.sql
mysql -u root -p sgip < migrations/V6__vistas.sql
mysql -u root -p sgip < migrations/V7__datos_iniciales.sql
mysql -u root -p sgip < migrations/V8__datos_demo.sql   # opcional
mysql -u root -p sgip < migrations/V9__rol_super_admin_y_venta_sin_existencia.sql
mysql -u root -p sgip < migrations/V10__plataforma_multiempresa.sql
mysql -u root -p sgip < migrations/V11__claves_compuestas_por_empresa.sql
```

No uses `for f in migrations/V*.sql` con comodín: el orden alfabético
pone `V10` antes de `V2` y la cadena falla por claves foráneas. `aplicar.sh`
lleva la lista explícita por eso mismo.

V5 usa la directiva `DELIMITER`, que interpreta el cliente `mysql` pero
no todos los conectores. Si lo ejecutas desde una herramienta gráfica y
falla, verifica que soporte esa directiva.

## Contenido

| Script | Contenido |
|---|---|
| `00__crear_base.sql` | Base de datos y juego de caracteres |
| `V1__seguridad.sql` | roles, usuarios, sesiones, auditoria_accesos |
| `V2__catalogo.sql` | categorias, proveedores, productos, mapeos_proveedor, cargas_precios, detalle_carga_precios, historico_costos, movimientos_inventario |
| `V3__parametrizacion.sql` | parametros, uvt_anual, reglas_iva, reglas_margen, reglas_envio, calendario_habil |
| `V4__comercial.sql` | clientes, cotizaciones, cotizacion_items, ventas, venta_items, facturas_electronicas |
| `V5__funciones.sql` | fn_valor_uvt, fn_es_habil, fn_sumar_dias_habiles, fn_envio_sugerido |
| `V6__vistas.sql` | v_regla_margen, v_regla_iva, v_precios_producto, v_existencias_criticas, v_inventario_valorizado, v_rotacion_inventario |
| `V7__datos_iniciales.sql` | Roles, categorías, UVT, reglas vigentes, parámetros, festivos 2026 y 2027 |
| `V8__datos_demo.sql` | Datos de prueba y consultas de verificación |
| `V9__rol_super_admin_y_venta_sin_existencia.sql` | Rol SUPER_ADMIN; existencia negativa permitida; columnas de autorización en ventas |
| `V10__plataforma_multiempresa.sql` | empresas, suscripciones, producto_proveedor, ordenes_compra, orden_compra_items, recepciones, medios_pago, cierres_caja, cola_sincronizacion, descuadres_existencia; `empresa_id` en doce tablas |
| `V11__claves_compuestas_por_empresa.sql` | Las veinte claves foráneas que podían cruzar empresas pasan a incluir `empresa_id` |
| `V12__configuracion_tributaria_por_empresa.sql` | tipos_impuesto, tipos_retencion, empresa_config, producto_imagenes, venta_retenciones; `reglas_iva` gana `empresa_id`; las seis vistas exponen `empresa_id` |
| `V13__logo_de_empresa.sql` | `empresas.logo_ruta` |
| `V14__miniatura_de_imagen.sql` | `producto_imagenes.miniatura_ruta` |
| `V15__vista_de_precios_acotada_por_empresa.sql` | `v_precios_producto` repite `empresa_id` en la subconsulta de imagen y en sus cuatro enlaces |
| `V16__retirar_parametros_de_imagen.sql` | Retira los dos parámetros de imagen, que nadie leía y no podían ser por empresa |
| `V17__vista_de_precios_expone_los_identificadores.sql` | `v_precios_producto` expone `categoria_id` y `proveedor_id`, que los filtros y la edición necesitan |
| `V18__movimientos_admiten_existencia_negativa.sql` | `movimientos_inventario` admite el saldo negativo que la V9 autorizó en `productos`; el kárdex ya puede explicar una venta sin existencia (RF-42) |
| `V19__la_valorizacion_no_resta_lo_que_se_debe.sql` | `v_inventario_valorizado` y `v_rotacion_inventario` acotan la existencia a cero antes de multiplicar, y declaran aparte lo pendiente de reponer |
| `V20__la_vista_de_precios_cierra_la_modalidad_a_credito.sql` | `v_precios_producto` expone `total_credito`, `cerca_umbral_credito` y `cruza_umbral_en_credito` |
| `V21__auditoria_de_la_plataforma.sql` | `auditoria_plataforma`: bitácora inmutable de altas, activaciones, suspensiones y cambios tributarios nacionales, con responsable y fecha (RF-02, RF-05) |
| `V22__parametros_de_la_plataforma.sql` | `parametros_plataforma`: los ajustes de alcance de plataforma salen de la tabla por empresa, donde el periodo de prueba era un parámetro de la empresa 1 (RN-14, RN-17) |

`extras/restriccion_ambito_regla_iva.sql` queda **fuera** de la cadena a
propósito. Es una restricción correcta que no se pudo verificar, y el
propio archivo explica por qué está aparte en lugar de dentro de la V12.

V9 no crea tablas: cambia restricciones y agrega columnas. Las diez
tablas nuevas de V10 llevan el esquema de 24 a 34, las cinco de V12 lo
llevan a 39, la de la V21 a 40 y la de la V22 a 41. V11 no crea tablas: solo cambia claves foráneas e índices.

### Lo que la V18 cierra, y que la V9 dejó a medias

La V9 autorizó la existencia negativa en `productos` y no en
`movimientos_inventario`, que traía de la V2 dos restricciones con piso
en cero. El esquema quedaba permitiendo dejar el saldo en negativo y
prohibiendo anotar por qué: la venta sin existencia del RF-42, escrita
contra ese esquema, habría abortado su propia transacción.

No se manifestó antes porque todavía nada escribe en esa tabla —el
módulo de ventas no está construido—, de modo que era un defecto latente
y no una avería. Se reprodujo sobre una base armada con la cadena V1 a
V17 antes de escribir la migración, y `verificar_aislamiento.sh` registra
desde ahora un movimiento que deja el saldo en negativo: esa
comprobación falla con las restricciones de la V2 y pasa con las de la
V18.

La V19 y la V20 vienen del mismo repaso. La primera corrige una
aritmética que la existencia negativa volvió engañosa: con una
referencia en −2 y costo de 2.100.000, el valor de la bodega de la
empresa de demostración se reportaba en 59.150.000 en lugar de
63.350.000, y el tablero no mostraba un error sino una cifra plausible y
más baja. La segunda cierra la modalidad a crédito, que tenía precio y
tarifa pero no total: en PC-001 la diferencia entre vender de contado y
a crédito no es el 5 % del margen, son 603.750 pesos, porque el precio a
crédito cruza las 50 UVT del Art. 424 y se grava al 19 % mientras el de
contado queda excluido.

### Lo que la V12 corrige, y que la V10 dejó pasar

Cuatro defectos de aislamiento. Los cuatro se reprodujeron en una base
real antes de escribir la migración: no son revisión de código, son
comportamiento medido.

**`reglas_iva` nunca recibió `empresa_id`.** La V10 se lo agregó a
`reglas_margen` y a `reglas_envio`, pero no a esta. Su regla general —la
fila con `categoria_id` nulo— era **una sola para toda la plataforma**.
Al cambiar la empresa 1 su regla general a `EXCLUIDO`, el producto
«Arroz Diana 500 g» de la empresa 2 pasó de gravado al 19 % a excluido.
Un comercio le cambió el impuesto a otro, y en la pantalla no se nota:
la cifra simplemente aparece distinta.

**`v_regla_margen` no filtraba por empresa.** `reglas_margen` sí tiene
`empresa_id`, pero el join de la vista no lo usaba. El arroz de la
empresa 2 se valoraba con el factor 1,2000 de la empresa 1, que es el
margen de un distribuidor de tecnología aplicado a una tienda de barrio.

**Ninguna de las seis vistas exponía `empresa_id`.** La aplicación no
podía filtrarlas por empresa, que es justo lo que exige la RN-16, y
tenía que volver a las tablas: exactamente lo que las vistas existían
para evitar.

**Los consecutivos eran únicos globales.** `uq_ventas_consecutivo` y
`uq_cotizaciones_consecutivo` estaban sobre la columna sola, de modo que
la segunda empresa que numerara su primera venta como `V-0001` chocaba
con la primera. Cada comercio numera desde uno; que los consecutivos de
comercios distintos no se repitan entre sí no es ninguna regla de
negocio.

### Por qué hizo falta la V11

La V10 agregó `empresa_id`, pero dejó las claves foráneas sobre una sola
columna. Una clave de una columna comprueba que la fila referida
**exista**, no que pertenezca a la misma empresa. El esquema admitía
esto sin error:

```sql
INSERT INTO productos (empresa_id, categoria_id, ...)
VALUES (2, <categoría de la empresa 1>, ...);
```

Un producto de una tienda colgado de la categoría de otra. Y como la
lectura filtra por la empresa del producto, la categoría ajena aparece
como si fuera propia: el defecto no se manifiesta mirando la pantalla.

Eran veintiuna claves en esa situación. V11 convierte veinte en claves
compuestas sobre `(empresa_id, columna)`. La que queda fuera es
`auditoria_accesos.usuario_id`, y a propósito: su `empresa_id` admite
nulo porque tiene que registrar los accesos del super administrador de
la plataforma, que no pertenece a ninguna empresa.

Las claves hacia `usuarios` ganan un efecto extra. Como
`usuarios.empresa_id` es nulo para el super administrador, y la
`empresa_id` de ventas, cotizaciones, movimientos y cierres es
obligatoria, la clave compuesta **impide que un usuario de plataforma
figure como autor de una operación de una empresa**. BSP crea, activa y
desactiva comercios, pero no opera dentro de ellos. Eso era una
convención y ahora está en el esquema.

## Decisiones que conviene conocer antes de tocar el esquema

**El aislamiento entre empresas vive en `empresa_id`, no en el código.**
Doce tablas lo llevan como columna obligatoria y con clave foránea. Las
restricciones de unicidad que antes eran globales ahora son por empresa:
dos comercios distintos pueden tener ambos un producto con código
`PC-001`. Los usuarios de plataforma —el super administrador de BSP— son
la excepción: su `empresa_id` es nulo, porque no pertenecen a ninguna.

**La existencia negativa es un estado válido.** `ck_productos_existencia`
admite hasta −9999. La columna generada `estado_stock` clasifica
cualquier existencia menor o igual a cero como `AGOTADO`, nunca como
`CRITICO`. Una venta autorizada sin existencia registra quién la
autorizó y por qué.

**Las cantidades son enteras.** No hay `DECIMAL` en cantidades de
producto. Un comercio que vende arroz por peso lo modela por
presentación: «Arroz Diana 500 g», diez unidades. Introducir fracciones
obligaría a revisar todos los cálculos de subtotal y no resuelve ningún
caso real del alcance.

**Los precios de venta no son columnas generadas.** Dependen de
`reglas_margen` y `reglas_iva`, que son tablas distintas, y MySQL solo
admite columnas generadas que dependan de columnas de la misma fila. Se
resuelven en la vista `v_precios_producto`. Sí son generadas
`productos.estado_stock`, `cierres_caja.diferencia`, los subtotales de
las líneas y los totales de cotización y venta, porque cada una depende
únicamente de su propia fila.

**Los umbrales tributarios se guardan en UVT, no en pesos.** La unidad se
actualiza cada año por resolución. Guardar el tope en pesos obligaría a
editar la regla cada enero y dejaría inconsistentes los documentos ya
emitidos. `fn_valor_uvt(fecha)` hace la conversión según la fecha.

**La exclusión de IVA opera por debajo del umbral.** Artículo 424 del
Estatuto Tributario, numerales 5 y 6: computadores hasta 50 UVT y
dispositivos móviles hasta 22 UVT no causan el impuesto. Por encima del
tope se aplica la tarifa general. La columna `cerca_umbral` de
`v_precios_producto` marca las referencias que quedan dentro del diez por
ciento inferior al tope.

**Excluido y exento son tratamientos distintos** y por eso son valores
diferentes de la enumeración. El exento se grava a tarifa cero y da
derecho a devolución del impuesto pagado en la compra; el excluido no
causa el impuesto y no otorga ese derecho.

**Los precios se congelan en las líneas de cotización y de venta.** Es
dato histórico, no derivado. Un documento emitido ayer conserva lo que se
ofreció, aunque el costo haya cambiado hoy.

**`cola_sincronizacion.clave_idempotencia` es única.** Es lo que impide
que una operación registrada sin conexión se aplique dos veces cuando el
cliente reintenta el envío. El segundo intento falla con error 1062 y el
cliente lo trata como éxito, no como error.

**Nada se elimina.** Productos, proveedores, usuarios y empresas se
desactivan. Las claves foráneas usan `RESTRICT` donde borrar rompería la
trazabilidad y `CASCADE` solo en las líneas de detalle, que no tienen
sentido sin su encabezado.

**Las columnas booleanas se declaran `BOOL`, no `TINYINT(1)`.** MySQL las
almacena igual, pero escribir el ancho de visualización produce el aviso
1681: los anchos en tipos enteros están obsoletos y se van a retirar.

**`sesiones.token_hash` guarda el hash del token, no el token.** Si la
base se filtra, las sesiones vigentes no quedan expuestas.

## Lo que el esquema no resuelve y la aplicación sí

**El filtro de lectura por empresa.** Desde la V11 el esquema impide
*escribir* filas que cruzan empresas, y desde la V12 las vistas exponen
`empresa_id` para que se pueda filtrar. Pero nada obliga a filtrar: una
consulta que olvide `WHERE empresa_id = ?` devuelve datos de otros
comercios sin fallar. En desarrollo, con una sola empresa cargada, el
defecto es invisible. Debe resolverse en un único punto de la capa de
datos, no consulta por consulta.

**Que la configuración esté completa antes de vender.** `empresa_config`
guarda el interruptor `configuracion_completa`, pero no impide que se
registre una venta con él en falso. Eso es la RN-25 y se resuelve en la
autorización, igual que el estado desactivado de la empresa. Lo que el
esquema sí hace es dejarlo visible: un producto de una empresa sin
configurar sale de `v_precios_producto` con
`tributacion_sin_configurar = 1` y sin precio, en lugar de con un total
vacío sin explicación.

**La atomicidad entre tablas.** El descuento de existencias y el registro
de la venta deben ocurrir en una sola transacción.

**El calendario.** `calendario_habil` trae 2026 y 2027. Hay que cargar
cada año nuevo antes de que empiece, o `fn_sumar_dias_habiles` contará
los festivos como hábiles. La Ley 51 de 1983 traslada varias festividades
al lunes siguiente, de modo que las fechas no se derivan con una fórmula
fija.

**El valor de la UVT.** `uvt_anual` trae 2025 y 2026. Sin el año cargado,
`fn_valor_uvt` devuelve nulo y la vista trata el producto como gravado,
que es el comportamiento conservador: cobra el impuesto en lugar de
omitirlo.

`reglas_envio.costo_envio` viene en cero, lo que equivale a no cobrar
envío en ningún caso: la regla queda inerte hasta que se fije una tarifa
real. El umbral se mide sobre el subtotal, antes del impuesto, y la
comparación es estricta, de modo que una compra de exactamente un millón
no alcanza el beneficio.

## Verificación

La cadena V1 a V12 se aplicó desde cero, con y sin datos de
demostración, y produjo **39 tablas, 6 vistas, 4 funciones y 24 claves
foráneas por empresa**.

Las comprobaciones están en dos scripts que puedes correr tú mismo sobre
una base de desarrollo. Los argumentos se pasan tal cual al cliente
`mysql`:

```bash
cd database
bash verificar_aislamiento.sh -u root -p
bash verificar_tributacion.sh -u root -p
```

El segundo cubre lo que introduce la V12 y los cuatro defectos que
corrige: dieciocho comprobaciones, entre ellas que la regla de IVA de
una empresa no alcance a otra, que el margen de una no fije el precio de
la otra, que quien no es responsable de IVA no cause impuesto, y que el
neto a recibir sea el total menos la retención.

Comprueba ocho cosas, y en los casos de rechazo verifica además **por
qué** restricción se rechaza: una prueba que pasa porque la sentencia
falló por otro motivo no está probando nada.

| Comprobación | Resultado esperado |
|---|---|
| Producto con categoría y proveedor propios | Admitido |
| El mismo código de producto en dos empresas | Admitido |
| `existencia = -3` | `estado_stock = 'AGOTADO'` |
| Producto con categoría de otra empresa | Error 1452, `fk_productos_categoria_emp` |
| Venta cuyo vendedor es de otra empresa | Error 1452, `fk_ventas_usuario_emp` |
| Venta cuyo cliente es de otra empresa | Error 1452, `fk_ventas_cliente_emp` |
| Primera operación en la cola de sincronización | Admitida |
| La misma `clave_idempotencia` repetida | Error 1062, `uq_cola_idempotencia` |

El script deja en la base una empresa de prueba con NIT `900000000-1`.
Es idempotente: limpia sus propias filas antes de empezar, de modo que
puede ejecutarse varias veces seguidas.

Fuera del script se comprobó también que `cantidad_recibida` mayor que
`cantidad` falla con error 4025 por `ck_oci_recibida`, y que un usuario
de plataforma admite `empresa_id` nulo mientras un usuario de empresa lo
exige.

Estas pruebas corrieron sobre **MariaDB 10.11**, que fue el motor
disponible en el entorno de verificación, no sobre MySQL 9.7. Dos cosas
se adaptaron solo para esa prueba y en el repositorio están escritas para
MySQL:

- La colación `utf8mb4_0900_ai_ci`, que MariaDB no tiene.
- `DROP CHECK`, que en MariaDB se escribe `DROP CONSTRAINT`.

El resto del lenguaje de definición de datos es común a los dos motores.
Aun así, aplícala en tu entorno antes de darla por buena.

## Anotado para el módulo de ventas

Una pregunta que el esquema deja abierta a propósito y que la lógica de
Java tendrá que responder cuando se construyan las ventas.

En `cotizaciones` y `ventas`, el total es una columna generada:
`subtotal - descuento + iva + envio`. En `cotizacion_items` y
`venta_items`, el `valor_iva` de cada línea se calcula sobre
`cantidad * precio_unitario * tarifa_iva`, sin restar el descuento de
cabecera.

En Colombia eso puede estar bien o mal según qué sea ese descuento. Los
descuentos condicionados no afectan la base del impuesto; los
descuentos comerciales a pie de factura sí la reducen, y de forma
proporcional. Con la aritmética actual, un descuento comercial deja el
impuesto calculado sobre una base mayor que la que la factura declara.

El esquema no decide cuál de los dos es, y no debería: son dos figuras
distintas y el usuario que registra la venta es quien lo sabe. Lo que
hay que resolver al construir el módulo es una de estas dos vías, y
dejarla escrita:

1. Distinguir las dos clases de descuento en la cabecera y, para el
   comercial, recalcular el impuesto sobre la base ya rebajada.
2. Documentar que el campo `descuento` es siempre condicionado —menor
   valor a pagar, sin efecto sobre la base— y ofrecer el descuento
   comercial únicamente en la línea, donde el `precio_unitario` ya lo
   incorpora.

La segunda es más simple y hay que decirla en la pantalla, o alguien
registrará un descuento comercial en la cabecera y la factura saldrá con
el impuesto de más.

## Anotado para el módulo de plataforma

`tipos_impuesto` tiene `vigente_desde` y `vigente_hasta`, y no puede
guardar historia. La clave `uq_tipos_impuesto_codigo` es única por
código, de modo que una segunda vigencia del mismo tipo se rechaza con
un 1062, y `v_regla_iva` filtra por vigencia la tabla `reglas_iva` pero
no esta. Comprobado: el `INSERT` de una vigencia nueva para
`EXCL_UMBRAL_PC` no entra.

Eso significa que el RF-05 modifica la tarifa y el umbral **en el
sitio**, y que la historia del cambio la guarda `auditoria_plataforma`
con responsable, fecha y los valores anterior y nuevo. No es un apaño:
las ventas congelan su propia `tarifa_iva` por línea, de modo que
recalcular una factura vieja no necesita la tarifa histórica del
catálogo —la tiene el documento—. Lo que el catálogo responde es «qué se
cobra hoy», y para eso una fila por tipo es lo correcto.

Queda dicho porque las dos columnas invitan a suponer lo contrario.
Quien algún día necesite de verdad la vigencia en esta tabla tiene que
cambiar la clave única a `(codigo, vigente_desde)` —como la tiene
`parametros`— y hacer que `v_regla_iva` filtre también `ti`. Mientras
las ventas congelen su tarifa, no hace falta.

Lo que sí se comprobó, porque es la promesa del RF-05: un `UPDATE` de
`uvt_anual` o de `tipos_impuesto` surte efecto en el cálculo de todas
las empresas sin recompilar ni desplegar. Bajando la UVT de 2026 de
52.374 a 48.000, los tres computadores de la demostración pasan a
gravados de contado y `cruza_umbral_en_credito` cae a cero en los tres;
cambiando la tarifa del 19 % al 5 %, se propaga a todas las tarifas y
totales. Las vistas recalculan en cada lectura.

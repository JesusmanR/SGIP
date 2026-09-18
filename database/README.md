# SGIP — Esquema de base de datos

MySQL 8.0.16 o superior. La versión mínima la imponen las restricciones
`CHECK`, que MySQL ignoraba silenciosamente en versiones anteriores.

Probado contra MySQL 8.0.46: los ocho scripts se ejecutan sin errores y
producen 24 tablas, 6 vistas y 3 funciones, con el registro binario
activado y sin privilegios especiales.

MySQL 8.0 llegó a fin de vida en abril de 2026. Para una instalación
nueva conviene 8.4 LTS, que mantiene la colación, las restricciones
`CHECK`, las columnas generadas y las funciones de ventana que usa este
esquema. Esa versión no ha sido verificada directamente.

## Orden de ejecución

```bash
mysql -u root -p < 00__crear_base.sql
mysql -u root -p sgip < V1__seguridad.sql
mysql -u root -p sgip < V2__catalogo.sql
mysql -u root -p sgip < V3__parametrizacion.sql
mysql -u root -p sgip < V4__comercial.sql
mysql -u root -p sgip < V5__funciones.sql
mysql -u root -p sgip < V6__vistas.sql
mysql -u root -p sgip < V7__datos_iniciales.sql
mysql -u root -p sgip < V8__datos_demo.sql   # opcional, solo para probar
```

El orden importa: hay claves foráneas entre migraciones.

Las funciones de V5 declaran `READS SQL DATA`, de modo que se crean sin
problema con el registro binario activado. No hace falta tocar
`log_bin_trust_function_creators`.

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
| `V5__funciones.sql` | fn_valor_uvt, fn_es_habil, fn_sumar_dias_habiles |
| `V6__vistas.sql` | v_regla_margen, v_regla_iva, v_precios_producto, v_existencias_criticas, v_inventario_valorizado, v_rotacion_inventario |
| `V7__datos_iniciales.sql` | Roles, categorías, UVT, reglas vigentes, parámetros, festivos 2026 y 2027 |
| `V8__datos_demo.sql` | Datos de prueba y consultas de verificación |

## Decisiones que conviene conocer antes de tocar el esquema

**Los precios de venta no son columnas generadas.** Dependen de
`reglas_margen` y `reglas_iva`, que son tablas distintas, y MySQL solo
admite columnas generadas que dependan de columnas de la misma fila. Se
resuelven en la vista `v_precios_producto`. Sí son generadas
`productos.estado_stock`, los subtotales de las líneas y los totales de
cotización y venta, porque cada una depende únicamente de su propia fila.

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

**Nada se elimina.** Productos, proveedores y usuarios se desactivan. Las
claves foráneas usan `RESTRICT` donde borrar rompería la trazabilidad y
`CASCADE` solo en las líneas de detalle, que no tienen sentido sin su
encabezado.

**Las columnas booleanas se declaran `BOOL`, no `TINYINT(1)`.** MySQL las
almacena igual, pero escribir el ancho de visualización produce el aviso
1681: los anchos en tipos enteros están obsoletos y se van a retirar.
Declararlas como `BOOL` deja los ocho scripts sin un solo aviso.

**`sesiones.token_hash` guarda el hash del token, no el token.** Si la
base se filtra, las sesiones vigentes no quedan expuestas.

## Lo que el esquema no resuelve y la aplicación sí

El descuento de existencias y el registro de la venta deben ocurrir en
una sola transacción. El esquema impide que la existencia quede negativa
mediante `ck_productos_existencia`, pero la atomicidad entre tablas es
responsabilidad de la capa de lógica.

`calendario_habil` trae 2026 y 2027. Hay que cargar cada año nuevo antes
de que empiece, o `fn_sumar_dias_habiles` contará los festivos como
hábiles. La Ley 51 de 1983 traslada varias festividades al lunes
siguiente, de modo que las fechas no se derivan con una fórmula fija.

`reglas_envio.costo_envio` viene en cero, lo que equivale a no cobrar
envío en ningún caso: la regla queda inerte hasta que se fije una tarifa
real. El umbral se mide sobre el subtotal, antes del impuesto, y la
comparación es estricta, de modo que una compra de exactamente un millón
no alcanza el beneficio. Por debajo del umbral el vendedor puede regalar
el envío marcando `origen_envio = 'MANUAL'`, lo que obliga a registrar
motivo y responsable: es un descuento y debe poder auditarse.

`uvt_anual` trae 2025 y 2026. Sin el año cargado, `fn_valor_uvt` devuelve
nulo y la vista trata el producto como gravado, que es el comportamiento
conservador: cobra el impuesto en lugar de omitirlo.

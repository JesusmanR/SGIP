# ADR-0029 · «Hoy» lo pone la aplicación, en la hora de Bogotá

Estado: aceptada · 2026-11-07

## Contexto

Las decisiones de vigencia (qué regla de ganancia o de IVA rige, qué periodo de costo
cerrar, cuánto dura el mes de prueba, si una regla «aún no empieza» y se puede retirar)
usaban dos relojes a la vez. Unas leían el `Clock` de la aplicación, que es
`Clock.systemDefaultZone()`, es decir, la zona de la JVM; otras dejaban que el motor
decidiera con `CURDATE()`. En la CI la JVM corre en UTC y la conexión usa
`serverTimezone=America/Bogota`, y un servidor en la nube tiene la misma costumbre: de
las 7 de la tarde a la medianoche de Colombia, la JVM y un MySQL en UTC ya están en el
día siguiente. El hallazgo M5 de la revisión final lo señaló: con la base en otra zona,
las vigencias y las ventas caerían en días distintos.

## Decisión

**Una sola fuente: el día de Bogotá según el `Clock` de la aplicación.** `util/Hoy.de(reloj)`
devuelve `LocalDate.ofInstant(reloj.instant(), America/Bogota)`. No cambia el bean `Clock`
(la sesión y la autenticación guardan `LocalDateTime` con él y se mueven con otras
ramas); lo que cambia es que toda decisión de vigencia calcula su fecha aquí.

**El servicio la calcula y la consulta la recibe como parámetro `:hoy`.** Ninguna
sentencia de Java usa `CURDATE()` para decidir una vigencia. Pasan a parámetro:
`RepositorioPrecios` (marca `vigente`, regla propia vigente, corregir/cerrar/abrir la
regla propia, alcance), `RepositorioConfiguracionTributaria` (marca `vigente`, retirar
una regla que aún no empieza), `RepositorioImportacion` (los tres pasos del histórico de
costos), `RepositorioPlataforma.mesesDePrueba` y el nuevo `RepositorioCostos`. Dentro de
una operación se calcula un único `hoy` y se pasa a todas sus sentencias.

**Lo que se queda como estaba, a propósito:**

- Las marcas de tiempo con `NOW()` (por ejemplo `fecha_aplicacion` de una carga): son un
  instante de auditoría, no una decisión de vigencia.
- La ventana de 30 días del panel (`movimientosPorDia`, `CURDATE() - 29 DAY`): es solo una
  ventana de lectura de un gráfico; a lo sumo recorta o añade un día en el borde.

## Lo que esta decisión no resuelve: las vistas

`v_precios_producto`, `v_regla_margen`, `v_regla_iva` y las demás vistas del esquema deciden
la vigencia con `CURDATE()`, y una vista de MySQL no admite parámetros ni variables de
usuario o de sistema. No se pueden reescribir aquí sin pisar la V36, que es de otra rama.

Mientras la sesión de la base esté en un día distinto al de Bogotá, las dos fuentes discrepan:

- Con la base **por delante** (MySQL en UTC, de 7 de la tarde a medianoche): una regla que la
  aplicación fecha «hoy» ya rige para la vista, y una que cierra «ayer» ya estaba cerrada.
  No pierde precios: la vista va un día adelantada.
- Con la base **por detrás** (no ocurre con UTC, que nunca va detrás de Bogotá, pero sí con
  una zona al oeste): una regla fechada «hoy» por la aplicación todavía no rige para la
  vista, y el producto se quedaría sin precio hasta la madrugada siguiente.

**Propuesta, que requiere configuración del despliegue y no se aplica desde el código:**
fijar la zona de la sesión de MySQL en la hora de Bogotá, de modo que `CURDATE()` sea el
mismo día que `Hoy.de(reloj)`. Colombia no tiene horario de verano, de modo que el
desplazamiento fijo `-05:00` basta y no exige tener cargadas las tablas de zonas horarias:

- `spring.datasource.hikari.connection-init-sql: SET time_zone = '-05:00'` en el
  `application.yml` de cada máquina (ese archivo no se versiona), o
- el parámetro `time_zone` / `default-time-zone=-05:00` del servidor MySQL.

Con eso las vistas y la aplicación comparten día y la discrepancia desaparece. Si se
prefiere no depender de configuración, la alternativa es que una migración posterior reescriba
las vistas para leer «hoy» de una función que la sesión fija al abrirse, pero eso es un
cambio de esquema que debe coordinar la rama dueña de las vistas.

## Consecuencias

- Las pruebas de servicio fijan el reloj en cualquier instante y comprueban el día de
  Bogotá sin depender de la zona de la máquina. Las pruebas con base fijan el reloj en un
  año distinto al de la base para que los dos días difieran siempre, sin depender de la
  hora a la que corra la CI.
- Las pruebas con base que mueven fechas de reglas propias usan el día de Bogotá (no
  `CURDATE()`), porque esas reglas ahora se fechan con él.
- Quien añada una consulta de vigencia debe recibir `hoy` por parámetro. Un `CURDATE()` nuevo
  en el Java es, desde esta decisión, un defecto.

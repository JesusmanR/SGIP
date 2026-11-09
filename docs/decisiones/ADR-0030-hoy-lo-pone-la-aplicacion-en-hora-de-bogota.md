# ADR-0030 · «Hoy» lo pone la aplicación, en la hora de Bogotá

Estado: aceptada · 2026-11-09

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
costos), `RepositorioPlataforma.mesesDePrueba` y `RepositorioCostos`. Dentro de una
operación se calcula un único `hoy` y se pasa a todas sus sentencias.

**Lo que se queda como estaba, a propósito:**

- Las marcas de tiempo con `NOW()` (por ejemplo `fecha_aplicacion` de una carga): son un
  instante de auditoría, no una decisión de vigencia.
- La ventana de 30 días del panel (`movimientosPorDia`, `CURDATE() - 29 DAY`): es solo una
  ventana de lectura de un gráfico; a lo sumo recorta o añade un día en el borde.

## Las vistas: la sesión de la base va en la hora de Bogotá

`v_precios_producto`, `v_regla_margen` y `v_regla_iva` deciden la vigencia con
`CURDATE()` y una vista de MySQL no admite parámetros ni variables de usuario o de
sistema. Las definiciones vigentes de `v_regla_margen` y `v_regla_iva` son las de la
**V38** (la V36 las definió antes y la V38 las reemplaza); no se reescriben aquí.

Para que su «hoy» sea el de la aplicación, **cada conexión del pool arranca con
`SET time_zone = '-05:00'`**. Lo hace `config/ConfiguracionZonaDeLaBase`, un
post-procesador que pone `connectionInitSql` al `HikariDataSource`. Está en código y no
en el `application.yml` por la misma razón de `ConfiguracionMigraciones`: ese archivo no
se versiona, así que la CI no probaría lo mismo que producción. Colombia no tiene horario
de verano: el desplazamiento fijo basta y no exige tener cargadas las tablas de zonas
horarias del servidor. `ZonaDeLaSesionConBaseTest` comprueba la zona de la sesión y que
`SELECT CURDATE()` sea `Hoy.de(Clock.systemUTC())`.

**Doble guarda al retirar una regla de IVA.** El `DELETE` de `borrarRegla` exige
`vigente_desde > :hoy` y, además, `vigente_desde > CURDATE()`. Con la sesión en Bogotá son
el mismo día; la segunda es un cinturón por si alguna vez no lo fueran: una regla que el
motor ya tiene vigente (la que aplican las vistas) no se borra aunque la aplicación crea que
aún no empieza, y el servicio responde que ya empezó.

### Lo que cambia para lo ya guardado

La sesión pasa de la zona del servidor (UTC en la CI y en un servidor típico) a `-05:00`.
Eso afecta a lo que el motor calcula con la hora: `NOW()` y `CURRENT_TIMESTAMP`, incluidos
los valores por omisión de columnas `DATETIME` y `TIMESTAMP`. Las filas ya existentes se
escribieron cinco horas «adelantadas» (en UTC) y las nuevas se escriben en hora de Bogotá,
de modo que un `ORDER BY` o una comparación entre unas y otras tiene un salto de 5 h una
sola vez. **Todavía no hay datos de producción**, solo bases de desarrollo y demostración,
por eso se acepta sin migrar. Los instantes que escribe la aplicación (`LocalDateTime`
con el `Clock`) no cambian.

**Desfase permanente entre Java y los valores por omisión.** El `Clock` es el de la JVM
(UTC en la CI y en un servidor típico, `Clock.systemDefaultZone()`), así que los
`LocalDateTime` que escribe Java (sesiones, bloqueo de cuentas, instantes de auditoría
de la aplicación) van en esa zona, y los `DEFAULT CURRENT_TIMESTAMP`/`NOW()` que pone la
base ahora van en hora de Bogotá, cinco horas por detrás. El desfase es permanente, no solo
de las filas viejas: dos columnas de la misma tabla pueden estar en horas distintas.
Hoy ninguna consulta las compara entre sí ni con el reloj de la aplicación (el bloqueo de
cuentas y las sesiones comparan siempre instantes escritos y leídos por Java), de modo que
no tiene efecto visible. Quien escriba una consulta que cruce una columna escrita por Java
con una por omisión de la base debe convertir una de las dos. Igualar el `Clock` a Bogotá
lo resolvería, pero mueve la autenticación y se deja para otra decisión.

## Consecuencias

- Las pruebas de servicio fijan el reloj en cualquier instante y comprueban el día de
  Bogotá sin depender de la zona de la máquina. Las pruebas con base fijan el reloj en un
  año distinto al de la base para que los dos días difieran siempre, sin depender de la
  hora a la que corra la CI.
- Las pruebas con base que mueven fechas de reglas propias usan el día de Bogotá (no
  `CURDATE()`), porque esas reglas se fechan con él.
- Quien añada una consulta de vigencia debe recibir `hoy` por parámetro. Un `CURDATE()` nuevo
  en el Java es, desde esta decisión, un defecto (salvo la segunda guarda de arriba).
- Si se despliega con otra forma de crear el pool que no sea Hikari, hay que repetir
  `SET time_zone = '-05:00'` allí; el test de la sesión lo delata.

## Histórico de costos al editar un producto (M15): lo que debe saberse

- El origen de una fila escrita por una edición es `carga_id` nulo; la importación la
  llena. No hay columna de origen y no se añadió una migración.
- Editar el costo el mismo día en que se aplicó una carga **reemplaza** la fila de hoy
  (también la de la carga), igual que dos cargas el mismo día: una vigencia de cero días no
  explica nada. Esa referencia deja de salir en `cruzaronEnLaCarga` de esa carga.
- El alta de un producto no escribe el histórico: la primera edición de un producto sin
  historia no tiene periodo anterior que cerrar y deja un único periodo desde hoy. Si debe
  sembrarse en el alta es una decisión de producto, no se tomó aquí.

## Orden de bloqueos (N1)

Orden único: empresa (reglas de ámbito) → producto → `historico_costos` / reglas propias
del producto. `ServicioProducto.actualizar` bloquea la fila del producto antes de escribir
`historico_costos` (escribirlo toma sobre el producto, por la clave foránea, un bloqueo
compartido y el UPDATE pide después el exclusivo: dos ediciones a la vez se interbloqueaban),
y `ServicioImportacion.aplicar` bloquea los productos de la carga (por identificador, con
`FOR UPDATE OF p`) antes de cerrar el periodo vigente. Así la edición y la importación toman
producto antes que histórico y no forman ciclo. Como red de seguridad, `ManejadorErrores`
traduce un interbloqueo (1213) o una espera agotada (1205) a 409 con un texto fijo, sin eco
de datos.

## Bloqueos de reglas de ganancia (M14)

`crear`, `actualizar` y `reemplazar` bloquean primero la fila de la empresa y luego la
regla, siempre en ese orden. Riesgos que quedan: la empresa se bloquea durante toda la
transacción, así que dos escrituras de reglas de la misma empresa se serializan (es
aceptable: son raras y manuales); y `escribirPrecios` bloquea el producto y no la empresa,
de modo que las reglas propias y las de ámbito no se esperan entre sí (no comparten filas).
`bloquearEmpresa` existe también en `RepositorioConfiguracionTributaria` con el mismo SQL;
no se unificó porque obligaría a tocar el constructor de dos servicios por una línea.

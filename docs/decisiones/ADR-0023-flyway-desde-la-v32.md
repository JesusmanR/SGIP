# ADR-0023 · Flyway gobierna las migraciones desde la V32

Estado: aceptada · 2026 · la cadena V1 a V31 queda como está

## Contexto

Las migraciones se aplicaban con `database/aplicar.sh` y, en la CI, con
un bucle que repite la misma lista a mano. Cada migración nueva obligaba
a acordarse de añadirla en tres sitios, y la V31 ya se subió en uno y se
olvidó en otro. Además, `aplicar.sh` solo trabaja sobre una base vacía:
no hay forma de llevar una base existente de la V31 a la V32 sin
recrearla y perder sus datos.

Flyway resuelve las dos cosas: guarda qué se aplicó y aplica solo lo que
falta. Pero no sabe expresar el orden de la cadena actual, donde la V8
(datos de demostración) es opcional y va en medio, porque V9 y V10
migran las filas que carga.

## Decisión

Flyway se adopta **desde la V32**. Las V1 a V31 se quedan donde están,
congeladas, y las sigue aplicando `aplicar.sh`.

- Las migraciones nuevas van en `backend/src/main/resources/db/migration/`
  y las aplica el backend al arrancar.
- Flyway se configura con `baselineOnMigrate` y línea base en la versión
  31. Sobre una base que tiene tablas pero ningún historial, registra la
  V31 como ya aplicada y ejecuta solo lo posterior. Los tres valores
  viven en código (`ConfiguracionMigraciones`) y no en el
  `application.yml`, que no se versiona.
- Una base nueva se arranca en dos pasos: `aplicar.sh` y después el
  backend. Sobre una base completamente vacía, Flyway no toma línea base:
  intentaría correr todas las migraciones desde cero y la V32 fallaría
  por falta de esquema. Por eso `aplicar.sh` va primero.
- La CI aplica las V32+ con el cliente `mysql` en el trabajo del esquema,
  para que los análisis vean el esquema final, y prueba el camino real de
  Flyway en el trabajo del backend, que comprueba que
  `flyway_schema_history` tiene la línea base en la V31.

## Consecuencias

**Las V1 a V31 no se editan, y tampoco se les reordena la lista.**
Cambiarlas no tendría efecto en ninguna base ya creada, y Flyway no las
vigila: no llevan suma de verificación.

**Si una migración de Flyway falla, el backend no arranca.** MySQL
confirma cada sentencia de definición de datos por su cuenta, así que el
esquema puede quedar a medias aunque Flyway marque la migración como
fallida. Antes de desplegar sobre datos que importan, se hace copia; para
recuperarse, se restaura la copia o se deja el esquema como estaba antes
de esa migración a mano, y se borra la fila fallida de
`flyway_schema_history` (o se repara con `flyway repair`) antes de
volver a arrancar. No se corrige editando una migración ya aplicada.

**`flyway_schema_history` es una tabla más.** Los conteos de tablas de
`aplicar.sh` y de la CI (41) se toman antes de que Flyway exista, y se
suben a mano si una V32+ crea tablas.

**Cada migración nueva ya no se añade a una lista:** basta el archivo en
`db/migration`. Se ordena por número de versión, no alfabéticamente.

## Alternativas descartadas

- *Pasar también las V1 a V31 a Flyway.* Obligaría a decidir qué hacer
  con la V8 opcional y a reescribir una cadena que funciona, para un
  beneficio que solo importa de la V32 en adelante.
- *Seguir con la lista a mano.* Es lo que ya falló una vez, y no resuelve
  llevar una base existente a una versión nueva.

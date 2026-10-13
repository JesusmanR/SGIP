# ADR-0011 · Aislamiento multiempresa por columna, en una sola base

Estado: aceptada · 2026

## Contexto

El SGIP deja de ser un sistema para Black Steel Parts y pasa a ser una
plataforma que BSP opera como servicio para pequeños comercios. Cada
comercio tiene su catálogo, sus clientes, sus precios y sus usuarios, y
no debe ver los de ningún otro.

Hay tres maneras conocidas de separar inquilinos:

1. **Una base de datos por empresa.** Aislamiento máximo. Cada
   migración hay que aplicarla tantas veces como comercios haya, y una
   consulta que abarque la plataforma —cuántas tiendas están activas—
   obliga a recorrer todas las bases.
2. **Un esquema por empresa** dentro de la misma base. Intermedio, con
   los mismos problemas de migración en menor escala.
3. **Una columna `empresa_id`** en las tablas compartidas.

## Decisión

Se adopta la tercera: **una sola base, con `empresa_id` en las tablas de
datos de empresa**. Doce tablas la llevan como columna obligatoria con
clave foránea a `empresas`.

Las restricciones de unicidad que eran globales pasan a ser por empresa.
Dos comercios distintos pueden tener ambos un producto con código
`PC-001`, y deben poder.

Los roles ganan un ámbito: `PLATAFORMA` o `EMPRESA`. El super
administrador de BSP tiene ámbito de plataforma y su `empresa_id` es
**nulo**, porque no pertenece a ninguna empresa.

## Por qué

El alcance son dieciocho semanas y un desarrollador. Una base por
comercio multiplica el trabajo de operación por el número de clientes,
que es exactamente la dimensión que una plataforma necesita que no
crezca. Y la plataforma necesita consultas que abarquen a todos los
inquilinos —qué suscripciones vencen este mes— que con bases separadas
se vuelven un recorrido.

El costo es que el aislamiento depende de que las consultas filtren.

## Consecuencias

**El filtro no puede quedar al criterio de cada consulta.** Una consulta
que olvide `WHERE empresa_id = ?` devuelve datos de otros comercios sin
fallar, y en desarrollo, con una sola empresa cargada, el defecto es
invisible. Debe resolverse en **un único punto** de la capa de datos.
Esto es la consecuencia más seria de esta decisión y la que hay que
vigilar en revisión de código.

**Las claves foráneas de una sola columna no bastan.** Comprueban que la
fila referida exista, no que pertenezca a la misma empresa. Eso se
descubrió al probar el esquema y se corrigió en la migración V11, que
convierte veinte claves en compuestas sobre `(empresa_id, columna)`. Ver
`../../database/README.md`.

**Una fuga de datos afecta a todos los inquilinos**, no a uno. Con bases
separadas el daño quedaría contenido. Es el precio de esta decisión y
conviene no olvidarlo al decidir qué se registra en bitácoras.

**Si la plataforma creciera** hasta donde el volumen de un comercio
grande moleste a los demás, la salida es mover ese inquilino a su propia
base. La columna `empresa_id` no lo impide: lo hace trabajoso pero
posible.

## Lo que decide el estado de la empresa

Una empresa desactivada **conserva acceso de consulta**. No se le
bloquea su propia información: es suya. Lo que pierde es la capacidad de
registrar operaciones nuevas. Eso es regla de negocio y se resuelve en
la autorización, no en el esquema.

# ADR-0020 · La plataforma administra las empresas desde fuera, y no entra en ellas

Estado: aceptada · 2026

## Contexto

La RN-17 dice dos cosas sobre el super administrador, y la segunda se
lee menos que la primera:

> El super administrador administra el ciclo de vida de las empresas y
> los datos tributarios nacionales, **y no accede a los datos operativos
> de ningún negocio**.

El resto del sistema está construido sobre la idea contraria. Todo
servicio pide la empresa a `ContextoEmpresa.empresaActual()`, que la
saca de la sesión, y toda consulta filtra por ella (RN-16). Ese patrón
no sirve para el módulo de plataforma: quien pregunta no tiene empresa,
y administrar el ciclo de vida de un comercio consiste precisamente en
nombrarlo desde fuera.

Al escribir el módulo aparecieron cuatro preguntas que parecían
independientes y resultaron ser la misma pregunta cuatro veces: dónde
acaba la plataforma y dónde empieza el comercio.

## Decisión

### 1. Los ajustes de la plataforma no son ajustes de la empresa 1

La duración del periodo de prueba —una política que le aplica a todas
las empresas— estaba guardada en `parametros`, cuya columna `empresa_id`
es `NOT NULL`, de modo que vivía como un ajuste de la empresa 1. Leerla
desde el módulo de plataforma habría incumplido la RN-17 en la primera
operación escrita, y mientras tanto el administrador de la empresa 1
podía cambiar el periodo de prueba de la competencia desde su propia
pantalla de parámetros.

La V22 crea `parametros_plataforma`. No es una tabla más: es el mismo
reparto que el ADR-0016 hizo con lo tributario —catálogo nacional en
`tipos_impuesto`, declaración de cada comercio en `empresa_config`—
aplicado a los ajustes. Un `CHECK` exige que toda clave empiece por
`sgip.plataforma.`, de modo que el alcance se lee en el nombre y se
puede revisar de un vistazo que nadie metió ahí un ajuste de comercio.

Se descartó admitir nulo en `parametros.empresa_id` con el significado
«plataforma». La clave única es `(empresa_id, clave, vigente_desde)` y
los dos motores consideran distintos dos nulos en un índice único: la
tabla aceptaría dos valores del mismo parámetro para la misma fecha, y
entonces «el» periodo de prueba deja de existir.

### 2. Las decisiones de la plataforma se registran

El RF-02 y el RF-05 piden que el cambio quede «registrado con
responsable y fecha». No había dónde: `empresas.estado` es una columna
sin historia, y aunque `uvt_anual` y `tipos_impuesto` conservan el valor
viejo por su vigencia, ninguna de las dos guarda autoría.

La V21 crea `auditoria_plataforma`, una bitácora inmutable. Tres tablas
necesitaban lo mismo, y las decisiones que registra son pocas y caras
—dar de alta un comercio, suspenderlo, cambiar la tarifa que aplica a
todos—, de modo que una bitácora cuesta menos que tres columnas
repetidas y responde mejor la pregunta que alguien va a hacer: qué le
pasó a este comercio y quién lo decidió.

`empresa_id` admite nulo porque un cambio de unidad de valor tributario
no pertenece a ningún comercio; `usuario_id` no admite nulo porque un
cambio sin responsable es exactamente lo que el requisito prohíbe. Dos
restricciones obligan a que cada acción traiga lo que le corresponde, y
enumeran las acciones en lugar de reconocerlas por su prefijo para que
una acción nueva sin clasificar no pueda insertarse.

### 3. La tabla `empresas` tiene dos vistas y ninguna se mezcla

`modelo.Empresa` ya existía y su comentario dice por qué trae tan poco:
mapea «la empresa vista desde dentro de ella» y deja fuera el estado y
la suscripción «para que ninguna pantalla de empresa los modifique».

El módulo de plataforma necesita esas columnas, y la salida fácil era
añadírselas. Se descartó: a partir de ahí habría dos caminos para
escribir la misma fila, y el que ninguna pantalla debía tener lo tendría
cualquiera que inyectara el repositorio equivocado. En lugar de eso,
`RepositorioPlataforma` escribe con sentencias explícitas sobre
`JdbcClient`, de modo que la única forma de cambiar el estado de una
empresa es llamar al método que lo cambia.

Es la segunda vez que el proyecto sale de JPA por un motivo propio: en
`ConsultaCatalogo` fue que una vista no es un agregado; aquí es que una
tabla puede tener dos dueños con permisos distintos, y el mapeo objeto-
relacional no sabe expresar eso.

### 4. El modo de consulta se decide en un solo punto

El RF-03 dice que un usuario de empresa desactivada obtiene 200 en
**toda** operación de lectura y 403 en **toda** operación de escritura.
La palabra que manda es «toda»: una comprobación repartida por los
servicios se cumple en los que alguien se acordó de tocar y se incumple
en el siguiente que se escriba —y se incumple en silencio, porque una
empresa desactivada que puede escribir no produce un error, produce
datos.

`FiltroEmpresaActiva` decide antes de cualquier controlador, y decide
por **método HTTP** y no por una lista de rutas. Una lista de rutas hay
que mantenerla; el método ya distingue leer de escribir en cada endpoint
que exista o se añada. Se enumera lo que no escribe —GET, HEAD, OPTIONS,
TRACE— en lugar de lo que escribe, para que un método que nadie previó
caiga del lado que rechaza.

Quedan fuera las peticiones sin autenticar, porque el ingreso es un POST
y un usuario de empresa desactivada tiene que poder entrar —si no
entrara, no podría consultar nada, que es justo lo que la regla le
conserva—, y el super administrador, que no tiene empresa y además es
quien reactiva.

## Por qué no guardar el estado en la sesión

Costaría una consulta menos por escritura. Se descartó porque entonces
una desactivación no surtiría efecto hasta que el usuario volviera a
entrar, y quien suspende un comercio por falta de pago espera que deje
de facturar ahora, no mañana. Las lecturas no pagan nada: el método se
mira antes de consultar, de modo que el catálogo —que es lo que más se
pide— no toca la tabla de empresas.

## Consecuencias

**Una escritura cuesta una consulta de una fila más.** Es el precio de
que la regla valga en todas partes, y es el tipo de consulta que un
índice primario resuelve sin esfuerzo.

**El cambio de contraseña de un usuario de empresa desactivada también
queda bloqueado**, porque es una escritura. Es correcto según la letra
del requisito y conviene anotarlo: si alguna vez molesta, la excepción
se hace en el filtro y no en el servicio, que es el sitio donde se puede
ver que existe.

**El super administrador no puede entrar en los comercios ni queriendo.**
Los servicios de catálogo, inventario y ventas piden la empresa al
contexto, que para él está vacío y rechaza con 403 por
`SinEmpresaException`. La segunda mitad de la RN-17 no depende de que
nadie exponga un endpoint descuidado: depende de que no haya empresa que
poner en la consulta.

**Hay dos nombres para el tercer estado.** El informe lo llama CONSULTA
y el esquema DESACTIVADA. Se conserva el del esquema, que es el que está
escrito en la base, y la diferencia queda dicha en `EstadoEmpresa` para
que nadie la descubra buscando un estado que no existe.

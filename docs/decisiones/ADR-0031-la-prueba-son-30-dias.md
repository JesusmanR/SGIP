# ADR-0031 · La prueba son 30 días, vencen sola y el contador no se fía del reloj del equipo

Estado: aceptada · 2026-11-09 · sustituye a la ADR-0022

## Contexto

La ADR-0022 resolvió una incoherencia de los requisitos a favor del mes de
calendario: «hasta el mismo día del mes siguiente». La v4 de los requisitos,
decisión del usuario del 2026-11-07, la resuelve al revés y sin ambigüedad:

- **RF-04.** La prueba dura 30 días calendario contados a partir del día
  siguiente al alta y vence a las 23:59:59 (America/Bogota) del trigésimo día.
  Trae sus casos: el alta del 29 de enero de 2026 vence el 28 de febrero; la
  del 31 de enero, el 2 de marzo; la del 30 de enero de 2028 (bisiesto), el 29
  de febrero. Al vencer sin pago, la empresa pasa a modo consulta sin
  intervención manual.
- **RF-73.** El administrador y el vendedor de una empresa en PRUEBA ven en el
  encabezado cuánto les queda. El servidor lo calcula con la hora de Bogotá:
  adelantar el reloj del equipo no cambia el contador.

Hasta ahora, una prueba vencida seguía escribiendo hasta que el super
administrador la desactivara a mano.

## Decisión

**El periodo se mide en días.** El alta escribe
`fin_prueba = hoy + N días`, con `hoy` de Bogotá (`Hoy.de(reloj)`, ADR-0030) y
`N` leído del parámetro `sgip.plataforma.prueba.dias`, que la V39 vuelve a
sembrar con 30. `fin_prueba` sigue siendo un DATE y significa el último día de
prueba, completo.

El parámetro de meses no se borra: la V39 le cierra la vigencia, como la V27
hizo con el de días. En una base nueva las dos migraciones corren el mismo día
y la fila de meses no se puede cerrar «ayer» sin romper la restricción de
rango, de modo que se cierra hoy (ver la cabecera de la V39); el alta solo lee
el de días. Las suscripciones existentes no se recalculan.

**El vencimiento es efectivo sin tarea programada.** No se cambia el ENUM de
`empresas.estado` ni se escribe nada al vencer: el estado en la base sigue
siendo PRUEBA hasta que la plataforma active o desactive. Lo que cambia es la
pregunta «¿puede escribir?»: una PRUEBA cuyo `fin_prueba` es anterior al día de
Bogotá según el `Clock` de la aplicación se trata como sin escritura.

- La regla vive en un solo sitio, `logica/VigenciaDePrueba`: el último día,
  las 23:59:59 aún escriben; desde las 00:00:00 del día siguiente, no.
- `FiltroEmpresaActiva` la aplica a toda escritura y responde lo mismo que a
  una empresa DESACTIVADA (403, ProblemDetail), con su propio detalle: «La
  prueba gratuita terminó. Puedes consultar toda tu información, pero no
  registrar cambios.». Solo lee `fin_prueba` cuando la empresa está en PRUEBA.
- Una PRUEBA sin suscripción no escribe: el alta siempre la crea, así que su
  falta es un dato roto y se falla cerrado.

Una tarea programada habría sido otra pieza que vigilar: si no corre (el
servidor estaba apagado a medianoche, o falló), la empresa sigue escribiendo
sin que nadie lo note. Decidir en cada petición con el reloj no tiene ese modo
de fallo, y no hay que deshacer nada si la plataforma activa la cuenta.

**El contador.** `GET /api/v1/empresa/prueba`, solo para `ADMINISTRADOR` y
`VENDEDOR` (el «administrador de empresa» del RF-73), responde
`{estado, venceEn, segundosRestantes}`:

- `estado`: PRUEBA, PRUEBA_VENCIDA, ACTIVA o DESACTIVADA. PRUEBA_VENCIDA no
  existe en la base: es lo que el reloj deduce, y vive en un tipo de respuesta,
  no en `EstadoEmpresa`.
- `venceEn`: las 23:59:59 del último día con su desplazamiento, por ejemplo
  `2026-02-28T23:59:59-05:00`. Se formatea a mano y no se deja a Jackson.
- `segundosRestantes`: `max(0, venceEn + 1 s − ahora)` en segundos enteros
  hacia abajo, de modo que a las 23:59:59 todavía queda 1 s.
- En ACTIVA y DESACTIVADA los dos últimos son nulos. Es un GET, así que
  responde también en modo consulta.

**El front cuenta con un reloj monótono.** `ContadorPrueba` pide el endpoint
al cargar y cada 5 minutos, y entre una petición y otra descuenta con
`performance.now()`, nunca con `Date.now()`. `Date.now()` es la hora del
equipo: si el usuario la adelanta, el contador saltaría; si la atrasa, ganaría
tiempo en pantalla. `performance.now()` solo mide cuánto tiempo pasó desde que
se abrió la página y no cambia cuando alguien toca la hora del sistema. Lo
único que el front sabe del vencimiento es lo que dijo el servidor, y la
escritura la decide el filtro, no la pantalla. Al llegar a cero vuelve a pedir
el endpoint (con un pequeño margen, porque en el último segundo el servidor
puede contestar todavía PRUEBA con 0).

## Consecuencias

- **Al desplegar, las empresas en PRUEBA con `fin_prueba` pasado quedan en
  solo lectura en ese instante.** Hasta ahora seguían escribiendo. Antes de
  desplegar conviene listarlas y activarlas o avisarles.
- Una empresa que la plataforma activa vuelve a escribir en la petición
  siguiente; no hay nada que limpiar.
- La segunda consulta (`fin_prueba`) solo la pagan las escrituras de empresas
  en PRUEBA.
- Las pruebas fijan el reloj y comprueban los siete casos del RF-04, el borde
  de las 23:59:59 y el endpoint en tres instantes. La prueba con base siembra
  su propio parámetro con un valor distinto de 30, para que leer el parámetro y
  usar la constante no den lo mismo, la lección de la ADR-0022.
- El informe de inicio queda desactualizado en lo que la ADR-0022 cambió; con
  esta vuelve a coincidir la columna del parámetro de la RN-14
  (`sgip.plataforma.prueba.dias · 30 días`).

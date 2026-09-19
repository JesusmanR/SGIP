# ADR-0008 · MySQL 9.7 LTS como motor de base de datos

Estado: aceptada · 2026

## Contexto

MySQL 8.0 llegó a fin de vida en abril de 2026, con la versión 8.0.46.
Oracle publica desde entonces dos vías paralelas: **Innovation**, con
entregas trimestrales y soporte solo hasta que sale la siguiente, y
**LTS**, con soporte de varios años.

La primera instalación fue un servidor 26.7.0, que pertenece a la vía de
innovación. El esquema funcionó, pero la versión habría dejado de ser la
vigente antes de terminar el proyecto.

## Decisión

Se adopta MySQL 9.7 LTS. Entre las dos versiones de soporte extendido
disponibles, 8.4 tiene mayor certificación con herramientas de terceros
y 9.7 tiene una ventana de soporte más larga. Para un desarrollo que
empieza ahora pesa más lo segundo.

## Consecuencias

La versión del motor no va a cambiar durante las dieciocho semanas del
proyecto. Esto importa más de lo que parece: un cambio de motor a mitad
del desarrollo obliga a revalidar el esquema completo.

El esquema fue verificado en dos entornos: MySQL 8.0.46 sobre Linux y
MySQL 9.7.2 sobre Windows. En ambos produce veinticuatro tablas y seis
vistas sin errores ni avisos.

Queda un punto a vigilar en el Sprint 2. Connector/J e Hibernate eligen
el dialecto según la versión que reporta el servidor, y una versión
reciente puede caer fuera de lo que reconoce una biblioteca antigua. El
síntoma es un error confuso al arrancar la aplicación, no un mensaje
claro. La prevención es fijar versiones recientes de ambas dependencias
desde el primer momento.

## Hallazgo asociado

Al verificar en 26.7.0 apareció el aviso 1681: los anchos de
visualización en tipos enteros están obsoletos y se van a retirar. Lo
disparaba `TINYINT(1)`, que el esquema usaba catorce veces. Se
reemplazaron por `BOOL`, que MySQL almacena de forma idéntica pero sin
declarar el ancho. Los ocho scripts corren ahora sin un solo aviso.

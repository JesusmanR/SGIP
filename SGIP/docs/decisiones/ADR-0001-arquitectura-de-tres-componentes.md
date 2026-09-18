# ADR-0001 · Arquitectura de tres componentes sobre una API REST

Estado: aceptada · 2026

## Contexto

La fase previa del proyecto produjo una aplicación de escritorio en Java
con la lógica de inventario ya validada. El alcance nuevo exige que la
misma información se consulte desde el teléfono de un vendedor en visita
a cliente, desde el escritorio administrativo y, eventualmente, desde
otros clientes.

## Decisión

El servicio backend concentra el dominio, las reglas y el acceso a datos,
y expone sus capacidades mediante una API REST. La aplicación móvil y el
módulo de escritorio la consumen. Ninguno de los dos implementa lógica de
negocio propia.

## Consecuencias

Nada de lo construido en la fase previa se descarta: el dominio, las
reglas y el esquema se reutilizan sin reescritura, y el escritorio pasa a
ser un cliente más.

Aparece un contrato que mantener. Cualquier cambio que rompa la
compatibilidad obliga a versionar el recurso, porque las aplicaciones ya
instaladas en los dispositivos no se actualizan al mismo tiempo que el
servidor.

La alternativa descartada era replicar la lógica en cada cliente. Dos
implementaciones de la misma regla divergen con el tiempo, y la
divergencia se manifiesta como comportamientos distintos según el canal
por el que entra el usuario.

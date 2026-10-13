# Cliente móvil

React Native. Consume la API REST del backend.

Sustituye el plan original de Android nativo con Kotlin. El motivo es
compartir lenguaje y modelo de componentes con el front web y con el
cliente de escritorio: tres frentes en el mismo lenguaje cuestan menos
de mantener que tres en tres. Ver
`../docs/decisiones/ADR-0010-stack-del-front.md`.

## Para quién

**Solo vendedor y cliente.** El super administrador y el administrador
de empresa no tienen acceso desde el teléfono: sus pantallas son densas
—tablas de muchas columnas, formularios largos— y un teléfono no las
presenta bien.

La pantalla de acceso lo declara en su cabecera, para que un
administrador que abra la aplicación entienda de inmediato que su acceso
está en el ordenador y no crea que hay un fallo. El diseño está en
`../docs/mockups/svg/P-01m.svg`.

## Alcance previsto

- Consulta de catálogo con filtros
- Lectura de códigos de barras y QR con la cámara
- Generación de cotizaciones
- Registro de ventas en punto de pago
- **Operación sin conexión** con cola de operaciones y sincronización al
  recuperar la red

Versión mínima: Android 10, nivel de API 29.

Corresponde al Sprint 6 del plan de trabajo.

## Lo que hace difícil este cliente

No es la interfaz. Es la operación sin conexión.

Una venta registrada sin red se encola localmente con una **clave de
idempotencia** y se envía al reconectar. Si el envío se corta a mitad y
el dispositivo reintenta, el servidor debe reconocer que es la misma
operación y no cobrarla dos veces: la tabla `cola_sincronizacion` tiene
esa clave como única, y el segundo intento falla con error 1062, que el
cliente debe tratar como **éxito**, no como error.

Si al sincronizar el producto ya está agotado porque otro turno lo
vendió, la existencia queda negativa. Eso no es un fallo: es la deuda de
reposición, y es el mismo mecanismo que usa la venta sin existencia
autorizada por el administrador. Ver
`../docs/decisiones/ADR-0012-operacion-sin-conexion.md` y
`../docs/decisiones/ADR-0014-venta-sin-existencia.md`.

El cajero necesita ver en qué estado está la sincronización, o no va a
confiar en el modo sin conexión. Esa pantalla está pendiente de diseño.

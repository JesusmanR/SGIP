# Registro de decisiones de arquitectura

Cada archivo documenta una decisión técnica que costó pensarla y que
alguien podría revertir por desconocimiento. El formato es corto a
propósito: contexto, decisión, consecuencias y estado.

Una decisión no se edita cuando cambia de opinión. Se marca como
superada y se escribe una nueva que la reemplace, con su número
siguiente. El historial es justamente lo que da valor al registro.

Cuando una decisión posterior afecta a una anterior sin reemplazarla,
la anterior recibe una **nota posterior** al final que apunta a la
nueva. La decisión original se deja tal como se escribió.

## Índice

| | Decisión | Estado |
|---|---|---|
| 0001 | Arquitectura de tres componentes sobre una API REST | vigente · ver nota |
| 0002 | PBKDF2 para derivación de contraseñas | vigente |
| 0003 | Los precios de venta se calculan en una vista | vigente · ver nota |
| 0004 | Los umbrales tributarios se almacenan en UVT | vigente · ver nota |
| 0005 | La factura electrónica se emite fuera de la transacción de venta | vigente |
| 0006 | El umbral de envío se mide sobre el subtotal | vigente |
| 0007 | La lectura de listas de precios se abstrae tras una interfaz | vigente |
| 0008 | MySQL 9.7 LTS como motor | vigente · ver nota |
| 0009 | Sistema de diseño de la interfaz | vigente · ver nota |
| 0010 | React y React Native en lugar de Kotlin y Swing | vigente |
| 0011 | Aislamiento multiempresa por columna, en una sola base | vigente |
| 0012 | Operación sin conexión con cola e idempotencia | vigente |
| 0013 | Las cantidades son enteras: la presentación es la unidad | vigente |
| 0014 | La existencia negativa es la deuda de reposición | vigente |
| 0015 | Suscripción: un mes gratis y tres estados de empresa | vigente |
| 0016 | La tributación se reparte en dos capas | vigente |

## Las cuatro que conviene leer antes de escribir código

**0011** y **0015** describen dos reglas que el esquema guarda pero no
hace cumplir: filtrar por empresa en toda lectura, y no admitir
escrituras de una empresa desactivada. Las dos se resuelven en un único
punto, y las dos son defectos que no se manifiestan en desarrollo.

**0016** explica por qué los datos tributarios están partidos entre la
plataforma y la empresa, y por qué la tarifa de ICA es la única
excepción. Alguien que no lo sepa va a querer «ordenarlo» moviéndola al
catálogo.

**0004** explica por qué el impuesto sobre las ventas no es una tarifa
única en este dominio. Es la regla de negocio que más fácil se
implementa mal.

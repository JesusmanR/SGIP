# ADR-0015 · Suscripción: un mes gratis y tres estados de empresa

Estado: aceptada · 2026

## Contexto

BSP opera la plataforma y cobra por ella. El modelo comercial acordado es
**un mes gratis y el resto a costo**. Hace falta decidir qué pasa cuando
una empresa no paga.

La respuesta fácil —bloquear el acceso— es la equivocada. Los datos del
comercio son del comercio: su catálogo, sus clientes, su histórico de
ventas. Negarle el acceso a su propia información para cobrarle es
retenerle algo que no es de BSP, y además deja al tendero sin poder
consultar lo que ya facturó.

## Decisión

La tabla `empresas` tiene tres estados: `PRUEBA`, `ACTIVA` y
`DESACTIVADA`. `suscripciones` guarda el periodo y su vigencia.

| Estado | Puede consultar | Puede registrar operaciones |
|---|---|---|
| `PRUEBA` | sí | sí |
| `ACTIVA` | sí | sí |
| `DESACTIVADA` | **sí** | no |

Una empresa desactivada queda en **solo lectura**. Conserva el acceso a
todo lo suyo y pierde la capacidad de registrar operaciones nuevas:
ventas, cotizaciones, movimientos de inventario, importaciones.

El super administrador de BSP **crea, activa y desactiva**. No elimina:
nada se elimina en este sistema.

## Por qué solo lectura y no bloqueo

Porque es lo correcto y porque conviene. Lo correcto está arriba. Lo
conveniente es que un comercio que puede seguir consultando su histórico
tiene una razón para volver, y uno al que se le cerró la puerta exporta
sus datos —si puede— y no vuelve.

También evita un problema legal incómodo: una factura electrónica
emitida debe poder consultarse. Bloquear el acceso a documentos ya
emitidos ante la DIAN pondría al comercio en falta por una decisión
comercial de su proveedor de software.

## Consecuencias

**El estado no basta: hay que hacerlo cumplir.** El esquema guarda el
estado, pero nada impide que una consulta de escritura ignore que la
empresa está desactivada. Igual que el filtro por empresa (ADR-0011),
esto se resuelve en **un único punto** de la capa de autorización, antes
de llegar a los datos. Dos reglas distintas que dependen de lo mismo:
conviene que las aplique el mismo filtro.

**La interfaz tiene que explicarlo.** Un administrador que entra y no
puede vender necesita saber por qué, y qué hacer. Un mensaje genérico de
permiso denegado convierte un problema de cobro en una llamada de
soporte.

**El mes gratis se cuenta desde el alta**, y eso es una fecha que hay
que guardar y vigilar. Si nadie revisa qué suscripciones vencen, el mes
gratis se vuelve indefinido. La consulta que las lista es de plataforma,
y es una de las razones por las que el aislamiento se hizo con una sola
base y no con una por comercio.

## Lo que queda abierto

**Cómo se cobra.** Esta decisión cubre el estado, no el medio de pago ni
la facturación de BSP a sus clientes. Mientras eso no exista, activar y
desactivar es manual, y está bien que lo sea al principio.

**La factura electrónica es prioridad, pero no inmediata.** Mientras no
esté, conviene verificar ante la DIAN qué comprobante puede emitir
legalmente un comercio pequeño —las condiciones del documento
equivalente POS— porque eso condiciona lo que la plataforma debe generar
en el entretanto. Está sin verificar.

# ADR-0006 · El umbral de envío se mide sobre el subtotal

Estado: aceptada · 2026

## Contexto

La empresa ofrece envío sin costo por compras superiores a un millón de
pesos. Quedaba por definir si el umbral se evalúa sobre el subtotal o
sobre el total con impuesto incluido.

Con computadores y celulares excluidos del impuesto en el catálogo, la
diferencia no es cosmética: dos compras con la misma mercancía caen en
lados distintos del umbral solo por su tratamiento tributario.

## Decisión

El umbral se evalúa sobre el subtotal, antes del impuesto, y la
comparación es estricta: un subtotal de exactamente un millón no alcanza
el beneficio.

Por debajo del umbral el vendedor puede otorgar el envío sin costo. Al
hacerlo, el documento registra `origen_envio = 'MANUAL'`, el motivo y el
usuario que lo autorizó. Una restricción de integridad rechaza el envío
manual sin esos dos datos.

## Consecuencias

El cliente ve una regla predecible: depende de lo que compra, no del
impuesto que causa.

El obsequio manual es un descuento, y queda auditable. Hoy cualquier
usuario con sesión puede otorgarlo; si la empresa quiere restringirlo a
un rol, `reglas_envio.permite_obsequio` permite desactivar la práctica y
la verificación por rol se añade en la capa de lógica.

`reglas_envio.costo_envio` viene en cero, lo que hace la regla inerte:
nadie paga envío hasta que se fije una tarifa real.

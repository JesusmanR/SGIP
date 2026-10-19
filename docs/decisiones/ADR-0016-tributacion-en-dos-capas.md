# ADR-0016 · La tributación se reparte en dos capas

Estado: aceptada · 2026

## Contexto

La plataforma deja de atender solo a un distribuidor de tecnología y
pasa a atender tiendas de barrio, papelerías y panaderías. Eso rompe dos
supuestos que el esquema daba por universales.

El primero es que toda empresa cobra impuesto sobre las ventas. Muchos
comercios pequeños no son responsables de IVA.

El segundo es que los umbrales del artículo 424 del Estatuto Tributario
—computadores hasta 50 UVT, móviles hasta 22 UVT— aplican siempre. Solo
le sirven a quien vende esos bienes. A una panadería no le dicen nada.

Y aparece una pregunta de propiedad: ¿quién carga los datos tributarios?
La respuesta inicial fue «el administrador de cada empresa, porque es su
negocio».

## La objeción

Esa respuesta mezcla dos cosas que no se parecen.

El valor de la UVT es un solo número que la DIAN fija cada año por
resolución: en 2026 son $52.374, idénticos para la panadería, la
papelería y Black Steel Parts. El calendario de festivos lo fija la Ley
51 de 1983. Las tarifas de retención en la fuente las fija un decreto.
Nada de eso lo elige una empresa: lo cumple.

Si cada administrador lo transcribe, cincuenta comercios escriben el
mismo número y un dedazo en uno produce impuesto mal calculado en todas
sus ventas. Y en enero de 2027, cuando cambie la UVT, hay que esperar
que cincuenta tenderos la actualicen; el que no lo haga obtiene nulo, y
`v_precios_producto` trata el producto como gravado.

## Decisión

La tributación se reparte en dos capas.

**La plataforma mantiene el dato legal.** Valor anual de la UVT,
calendario de festivos nacionales, catálogo de tipos de impuesto y
tarifas de retención en la fuente. Las tablas `tipos_impuesto` y
`tipos_retencion` **no llevan `empresa_id`**, y es deliberado: guardan
ley publicada, no configuración.

**La empresa decide qué aplica.** En `empresa_config`: si es responsable
de IVA, si aplica la exclusión por umbral, si practica retenciones, y la
tarifa de ICA de su municipio. En `reglas_iva`: qué tipo del catálogo
corresponde a cada categoría, con anulación por producto.

Lo que el administrador elige es **cuál de esas filas le aplica**, no
cuánto vale la tarifa.

## La excepción

La tarifa de industria y comercio la carga el administrador, no la
plataforma. El ICA lo fija cada municipio por actividad económica, de
modo que ningún valor central sirve. Es la única tarifa que vive en la
configuración de la empresa, y vale nombrarla como excepción para que
nadie la «corrija» moviéndola al catálogo.

## Consecuencias

**Los interruptores arrancan en falso.** Una empresa recién creada no es
responsable de IVA, no aplica umbrales y no practica retenciones.
Arrancar en falso no es timidez: es que nadie eligió todavía. La RN-25
impide vender hasta que alguien elija, y la vista lo hace visible con
`tributacion_sin_configurar`.

**Las vistas cargan con los interruptores.** `v_regla_iva` resuelve
«responsable de IVA en falso» como `NO_GRAVADO` y «no aplica umbral»
como `GRAVADO`, de modo que ninguna consulta posterior tiene que
acordarse de los dos. Si esto se hubiera dejado a la aplicación, cada
pantalla habría tenido que recordarlo y alguna no lo habría hecho.

**`reglas_iva` cambió de forma.** Antes guardaba el tratamiento, el
umbral y la tarifa en sus propias columnas; ahora apunta a un tipo del
catálogo. Conservar ambas habría sido tener dos fuentes del mismo dato,
que es como empiezan a divergir. La migración traduce las reglas
existentes antes de retirar las columnas viejas.

**La lista desplegable se mantuvo corta a propósito.** Siete tipos. La
va a leer un tendero, no un contador, y una lista de treinta opciones
legalmente exhaustivas es una lista que nadie escoge bien.

## Lo que esta decisión no resuelve

Las tarifas de retención quedaron sembradas en cero y marcadas como
inactivas, con el fundamento «pendiente de cargar desde la tabla de
retención vigente de la DIAN». Es deliberado: sembrar cifras
tributarias de memoria es la clase de error que nadie nota hasta la
declaración. Hay que cargarlas de la fuente antes de activarlas.

Tampoco resuelve el documento equivalente POS. Mientras la factura
electrónica no esté implementada, hay que confirmar ante la DIAN qué
comprobante puede emitir legalmente un comercio pequeño. Con el énfasis
en empresas unipersonales esto gana peso, porque son exactamente las que
operan con documento equivalente.

## Nota posterior

El reparto en dos capas resultó valer para más que la tributación.

Al escribir el módulo de plataforma apareció el mismo problema con los
ajustes: la duración del periodo de prueba —una política de la
plataforma— estaba guardada en `parametros`, cuya columna `empresa_id`
es `NOT NULL`, de modo que vivía como un ajuste de la empresa 1. La
V22 lo resuelve con la misma figura que esta decisión aplicó a los
impuestos: `parametros_plataforma` para lo que decide la plataforma,
`parametros` para lo que decide cada comercio. Ver ADR-0020.

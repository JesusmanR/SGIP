# ADR-0013 · Las cantidades son enteras: la presentación es la unidad

Estado: aceptada · 2026

## Contexto

La plataforma atiende tiendas de barrio y minimercados, que venden
arroz, azúcar o queso. La lectura inmediata es que hace falta vender
fracciones: medio kilo de arroz son 0,5 unidades de algo.

Eso llevaría a cambiar `existencia`, `cantidad` y todas las columnas de
cantidad de `INT` a `DECIMAL`, y con ellas los subtotales, los totales
generados, las vistas de valorización y de rotación, y las reglas de
margen.

## Decisión

**Las cantidades siguen siendo enteras.** Un producto que se vende por
peso o volumen se modela por su **presentación**, que es la unidad de
venta real:

> Arroz Diana 500 g · existencia 10 unidades

No «arroz, 5 kilos». La tabla `productos` tiene las columnas
`presentacion` y `sku` precisamente para eso.

## Por qué

Porque es como el comercio ya trabaja. Una tienda de barrio no pesa
arroz: vende la bolsa de 500 gramos y la de 1 kilo, que son dos
referencias distintas, con dos códigos de barras distintos y dos precios
que no guardan proporción exacta entre sí. Modelarlas como una sola
referencia con cantidad fraccionaria pierde justamente la información
que el negocio usa.

El caso que realmente exige fracciones —cortar queso al peso sobre una
balanza— no está en el alcance de dieciocho semanas, y cuando esté
pedirá más que un tipo de dato: pedirá integración con balanza, tara, y
precio por unidad de peso.

La decisión también evita una clase entera de defectos. Con cantidades
decimales hay que decidir cuántos decimales, cómo se redondea cada
subtotal y qué pasa cuando la suma de las líneas no coincide con el
total por redondeo. Con enteros ese problema no existe.

## Consecuencias

**El catálogo crece.** Cada presentación es una referencia. Una tienda
con veinte productos a tres presentaciones tiene sesenta referencias. La
importación de listas de proveedor tiene que manejarlo, y la búsqueda
por nombre tiene que distinguirlas bien en pantalla: «Arroz Diana» a
secas no basta, y por eso `presentacion` se muestra junto al nombre.

**No se puede vender media unidad.** Si el tendero quiere vender media
bolsa, el sistema no lo admite. Eso es deliberado: media bolsa abierta
no es inventario, es pérdida, y registrarla como media unidad la
convertiría en existencia que no se puede vender.

**Si el alcance cambia**, la migración es real: afecta columnas, columnas
generadas, vistas y cálculos. Conviene decidirlo antes de tener datos de
producción, no después. Esta decisión se revisa si entra un comercio que
venda al peso sobre balanza, y entonces lo que se escribe es una ADR
nueva que supere a esta, no una edición de este archivo.

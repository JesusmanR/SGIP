# ADR-0007 · La lectura de listas de precios se abstrae tras una interfaz

Estado: aceptada · 2026

## Contexto

Cada proveedor remite sus precios en un formato distinto: hojas de
cálculo con estructuras que no coinciden entre sí, portales de consulta
y, en algunos casos, servicios web. El número de proveedores crecerá.

## Decisión

El contrato de lectura se declara en la interfaz `LectorListaPrecios`,
con implementaciones separadas para hoja de cálculo y para servicio web.
La correspondencia entre columnas de origen y campos del sistema se
almacena por proveedor en `mapeos_proveedor`, no se escribe en código.

## Consecuencias

Incorporar un proveedor con estructura conocida no requiere programar:
basta configurar la correspondencia desde la interfaz. Incorporar un
origen de tipo nuevo añade una implementación y no modifica el
importador.

El precio de esa flexibilidad es que los errores de configuración se
manifiestan en tiempo de ejecución, no de compilación. Por eso la
validación previa a la aplicación es obligatoria y la carga se presenta
para revisión antes de tocar el catálogo.

## Enmienda (2026): POI también para escribir la plantilla

La acotación de la biblioteca es esta: **Apache POI solo se importa en las
clases de lectura y escritura de hojas de cálculo.** Eran una, la que lee
(`LectorHojaDeCalculo`); desde la plantilla de precios son dos, con
`GeneradorPlantillaPrecios`, que escribe el .xlsx que el administrador llena y
vuelve a subir. Lo que importa no ha cambiado: el importador y el resto del
backend hablan con `LectorListaPrecios` y con datos de la plantilla, y no saben
qué hay detrás. No hay una prueba de arquitectura que lo haga cumplir; se
sostiene por revisión.

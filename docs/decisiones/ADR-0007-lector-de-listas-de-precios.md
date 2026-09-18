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

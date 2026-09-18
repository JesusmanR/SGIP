# SGIP — Sistema de Gestión de Inventario de Productos

Plataforma de gestión de inventario y comercialización para Black Steel
Parts, distribuidora colombiana de equipos de tecnología.

Proyecto final del programa de Tecnología en Análisis y Desarrollo de
Software del SENA, Centro Industrial de Mantenimiento Integral, ficha
3234206.

## Qué resuelve

El catálogo opera hoy sobre hojas de cálculo desconectadas del proceso de
venta. Se compromete mercancía agotada, los precios quedan desalineados
del costo del proveedor y no hay forma de reconstruir qué pasó con una
referencia faltante.

El componente que da sentido al proyecto es el módulo de importación de
listas de precios: lee los archivos que envían los proveedores, absorbe
sus formatos heterogéneos mediante configuración, y recalcula los precios
de venta aplicando las reglas de margen. Sobre esa base el sistema genera
cotizaciones y emite factura electrónica.

## Arquitectura

Tres componentes sobre una sola fuente de lógica de negocio:

| Componente | Tecnología | Estado |
|---|---|---|
| `backend/` | Java 17 · Spring Boot 3 · API REST | En construcción |
| `movil/` | Android nativo · Kotlin | Pendiente |
| `escritorio/` | Java Swing · módulo administrativo | Heredado de la fase previa |
| `database/` | MySQL 8.0 · 24 tablas en tercera forma normal | Esquema completo |

Las dependencias internas del backend apuntan siempre hacia el modelo de
dominio. La lógica de negocio se prueba sin levantar un servidor ni
instanciar una interfaz gráfica.

El detalle está en `docs/informe/` y las decisiones técnicas, con su
justificación, en `docs/decisiones/`.

## Puesta en marcha de la base de datos

Requiere MySQL 8.0.16 o superior: las versiones anteriores ignoran las
restricciones `CHECK` en silencio.

```bash
cd database
./aplicar.sh                 # usa root sin contraseña, entorno local
./aplicar.sh -u sgip -p      # o indica usuario y pide la contraseña
```

O manualmente, respetando el orden, porque hay claves foráneas entre
migraciones:

```bash
mysql -u root -p < migrations/00__crear_base.sql
for f in migrations/V*.sql; do mysql -u root -p sgip < "$f"; done
```

Detalles y decisiones del esquema en `database/README.md`.

## Configuración

Ningún secreto se versiona. Copiar el archivo de ejemplo y completarlo:

```bash
cp backend/src/main/resources/application.yml.ejemplo \
   backend/src/main/resources/application.yml
```

`application.yml` está en `.gitignore`. Las credenciales se resuelven con
variables de entorno.

## Estructura

```
backend/      Servicio Java: dominio, lógica, datos, API REST
movil/        Aplicación Android
escritorio/   Módulo administrativo Swing
database/     Migraciones, datos iniciales y de demostración
docs/
  informe/    Informe de inicio del proyecto y anexos
  diagramas/  UML en PNG y fuente PlantUML editable
  decisiones/ Registro de decisiones de arquitectura
```

## Flujo de trabajo

Scrum con sprints de dos semanas. Nueve iteraciones previstas: una de
preparación y ocho de desarrollo.

Ramas:

- `main` — lo que está terminado y probado
- `develop` — integración del sprint en curso
- `feature/<sprint>-<descripcion>` — trabajo individual

Mensajes de commit en imperativo y en español, con el ámbito por delante:

```
bd: agregar tabla de reglas de envío
api: exponer consulta paginada de catálogo
docs: corregir umbral de exclusión tributaria
```

Una historia se considera terminada cuando está integrada en la rama
principal, tiene pruebas sobre la lógica introducida, la suite completa
pasa, el flujo de integración continua termina en verde, la
documentación afectada está actualizada y no se introdujo ninguna
credencial en el código.

## Reglas de negocio que conviene conocer antes de tocar código

El impuesto sobre las ventas **no es una tarifa única**. Los computadores
hasta 50 UVT y los celulares y tabletas hasta 22 UVT están **excluidos**,
no exentos, y la exclusión opera **por debajo** del umbral. Ver
`docs/decisiones/ADR-0004-umbrales-en-uvt.md`.

Cruzar ese umbral produce un salto de precio considerable: cien mil pesos
más de costo en un portátil pueden significar más de seiscientos mil
pesos más para el cliente. El sistema debe advertirlo, no absorberlo en
silencio.

Los precios se **congelan** en las líneas de cotización y de venta. Son
dato histórico, no derivado.

Nada se elimina. Productos, proveedores y usuarios se desactivan, porque
borrarlos rompería la trazabilidad.

## Licencia y autoría

Jesús Manuel Durán Muñoz · SENA CIMI · Ficha 3234206
Instructora: Yeimi Barrera

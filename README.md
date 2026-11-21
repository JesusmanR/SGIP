# SGIP — Sistema de Gestión de Inventario de Productos

Plataforma de gestión de inventario, precios y comercialización que
**Black Steel Parts opera como servicio** para pequeños comercios:
tiendas de barrio, minimercados y negocios que administran productos
físicos.

Proyecto final del programa de Tecnología en Análisis y Desarrollo de
Software del SENA, Centro Industrial de Mantenimiento Integral, ficha
3234206.

## Qué resuelve

Un comercio pequeño lleva su catálogo en hojas de cálculo desconectadas
del proceso de venta. Se compromete mercancía agotada, los precios quedan
desalineados del costo del proveedor y no hay forma de reconstruir qué
pasó con una referencia faltante.

El componente que da sentido al proyecto es el módulo de importación de
listas de precios: lee los archivos que envían los proveedores, absorbe
sus formatos heterogéneos mediante configuración, y recalcula los precios
de venta aplicando las reglas de margen. Sobre esa base el sistema genera
cotizaciones, registra ventas en punto de pago y emite factura
electrónica.

## Qué significa "plataforma"

Esto no es un sistema para una empresa. Es un sistema que BSP administra
y al que cada comercio entra como inquilino.

- **BSP** es el super administrador de la plataforma. Crea, activa y
  desactiva empresas. Una empresa desactivada conserva acceso de
  consulta: no se le bloquea su propia información.
- **Cada empresa** genera sus propios usuarios y roles, y gestiona su
  catálogo, sus clientes y sus precios sin ver los de ninguna otra.
- El aislamiento no es por convención: está en el esquema. Ver
  `docs/decisiones/ADR-0011-aislamiento-multiempresa.md`.

## Arquitectura

| Componente | Tecnología | Estado |
|---|---|---|
| `backend/` | Java 25 · Spring Boot 4.1.2 · API REST | En construcción: seguridad, catálogo, proveedores, imágenes y caja |
| `web/` | React 18 sobre HTML, CSS y JavaScript · Vite · Node 24 en local, 26 en la CI | En construcción: acceso, catálogo y datos de empresa |
| `escritorio/` | React Native · cliente con respaldo sin conexión | Sin iniciar |
| `movil/` | React Native · vendedor y cliente | Sin iniciar |
| `database/` | MySQL 9.7 LTS · 39 tablas en tercera forma normal | Esquema verificado en cada entrega |

El backend concentra el dominio, las reglas y el acceso a datos.
**Ningún cliente implementa lógica de negocio propia**: web, escritorio
y móvil consumen la misma API. Duplicar una regla en cada canal produce
dos implementaciones que divergen con el tiempo, y la divergencia se ve
como comportamientos distintos según por dónde entró el usuario.

La lógica de negocio se prueba sin levantar un servidor ni instanciar una
interfaz gráfica.

El detalle está en `docs/informe/` y las decisiones técnicas, con su
justificación, en `docs/decisiones/`.

### Qué canal usa cada rol

| Rol | Web | Escritorio | Móvil |
|---|---|---|---|
| Super administrador (BSP) | sí | sí | no |
| Administrador de empresa | sí | sí | no |
| Vendedor | sí | sí | sí |
| Cliente | sí | sí | sí |

El reparto no es arbitrario. Configurar la plataforma y administrar un
inventario exige pantallas densas —tablas de muchas columnas,
formularios largos— que un teléfono no presenta bien.

### La caja

El módulo de caja vive en el backend, bajo `/api/v1/caja/**` (más
`/api/v1/medios-pago` y `/api/v1/perfil/pin`), y reparte su trabajo así:

| Pieza | Qué hace | Quién la usa |
|---|---|---|
| Turno | abrir con una base de efectivo, ver el resumen, cerrar contando el efectivo y explicar la diferencia | administrador y vendedor |
| Venta | registrar la venta del mostrador, con pagos mixtos y clave de idempotencia; consultarla por su consecutivo; su recibo en JSON y en PDF de 80 mm | administrador y vendedor |
| Productos | buscar por código o por texto | administrador y vendedor |
| Encargos | lo que se vendió sin existencia o sin estar en el catálogo, hasta que llega y se entrega | solo el administrador |
| Domicilios | contra entrega: despachar y cobrar después | administrador y vendedor |
| Devoluciones | devolver parte de una venta o anularla, con saldo a favor | administrador y vendedor (este con el PIN de un administrador) |
| Clientes y medios de pago | el cliente identificado, y los medios con que se cobra; administrar los medios es solo del administrador | según la ruta |

Lo que un vendedor no puede hacer por sí solo (un descuento, un encargo,
un producto nuevo, una devolución) lo autoriza un administrador con su
PIN, que viaja dentro de la misma petición. El cliente y el super
administrador reciben 403 en todo el módulo.

Las decisiones están en `ADR-0014` (la existencia negativa y el encargo),
`ADR-0032` (el PIN), `ADR-0033` (el saldo a favor) y `ADR-0035` (el PDF
del recibo).

Dos comprobaciones cuidan el módulo de punta a punta:

- `CajaRolesTest` lee las rutas de los controladores y exige que cada una
  tenga su fila de roles esperados. Una ruta nueva sin fila hace fallar la
  prueba.
- `database/verificar_caja.sh` arranca el backend desde el JAR y recorre un
  turno completo por la API contra una base desechable: venta mixta,
  encargo, producto nuevo, domicilio, cobro, devolución con saldo a favor,
  cierre con diferencia y kárdex. También mide «buscar por código» y
  «registrar una venta» con 20.000 referencias. Se niega a correr si no se
  declara la base desechable (`SGIP_CAJA_BD_DESECHABLE=si`), si el servidor
  no es de esta máquina o si la base tiene usuarios ajenos a la
  demostración; ver `database/README.md`.

## Puesta en marcha de la base de datos

Requiere MySQL 9.7 LTS. El mínimo absoluto es 8.0.16, porque las
versiones anteriores ignoran las restricciones `CHECK` en silencio, pero
esa serie ya está fuera de soporte.

```bash
cd database
bash aplicar.sh -u root -p              # pide la contraseña una sola vez
bash aplicar.sh -u root -p --recrear    # borra la base y la reconstruye
bash aplicar.sh -u root -p --demo       # carga además datos de prueba
```

Las migraciones no son idempotentes: se aplican sobre una base vacía. Si
ya tiene tablas, el script avisa y se detiene en vez de fallar a medias.
Usa `--recrear` para rehacerla desde cero, que es lo normal en
desarrollo.

> **Si ya aplicaste una V9 anterior al 12 de octubre de 2026**, revisa
> `database/README.md`. Esa versión se detenía a mitad de camino y dejaba
> el esquema incompleto sin avisar.

Detalles y decisiones del esquema en `database/README.md`.

## Configuración

Ningún secreto se versiona. Copiar el archivo de ejemplo y completarlo:

```bash
cp backend/src/main/resources/application.yml.ejemplo \
   backend/src/main/resources/application.yml
```

`application.yml` está en `.gitignore`. Las credenciales se resuelven con
variables de entorno.

**El secreto de firma de los tokens no es la contraseña de la base de
datos.** Son dos secretos distintos y deben tener valores distintos:
quien conozca el de firma puede fabricarse un token de administrador sin
pasar por el login.

## Estructura

```
backend/      Servicio Java: dominio, lógica, datos, API REST
web/          Front web en React
escritorio/   Cliente de escritorio en React Native, con respaldo sin conexión
movil/        Cliente móvil en React Native
database/     Migraciones, datos iniciales y de demostración
docs/
  informe/    Informe de inicio del proyecto y anexos
  diagramas/  UML en PNG y fuente PlantUML editable
  mockups/    Pantallas en SVG y PNG, y el prompt para reconstruirlas en Figma
  decisiones/ Registro de decisiones de arquitectura
```

## Flujo de trabajo

Scrum adaptado a un equipo unipersonal. Trece iteraciones: una de
preparación y doce de desarrollo, veinticinco semanas en total. El plan
vigente y el avance están en `docs/informe/`.

Ramas:

- `main` — lo que está terminado y probado
- `develop` — integración del sprint en curso
- `feature/<sprint>-<descripcion>` — trabajo individual
- `fix/<descripcion>` — corrección de un defecto detectado al usar el sistema
- `chore/<descripcion>` — mantenimiento que no cambia comportamiento

La integración continua corre sobre las cuatro, y sobre toda solicitud
de incorporación hacia `main` o `develop`.

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

**El impuesto sobre las ventas no es una tarifa única.** Los computadores
hasta 50 UVT y los celulares y tabletas hasta 22 UVT están **excluidos**,
no exentos, y la exclusión opera **por debajo** del umbral. Ver
`docs/decisiones/ADR-0004-umbrales-en-uvt.md`.

Cruzar ese umbral produce un salto de precio considerable: cien mil pesos
más de costo en un portátil pueden significar más de seiscientos mil
pesos más para el cliente. El sistema debe advertirlo, no absorberlo en
silencio.

**Las cantidades son enteras.** Un producto que se vende por peso o
volumen se modela por su presentación: «Arroz Diana 500 g» con existencia
de 10 unidades, no 5 kilos. El esquema no admite fracciones de unidad y
eso es deliberado. Ver
`docs/decisiones/ADR-0013-cantidades-enteras.md`.

**Los precios se congelan** en las líneas de cotización y de venta. Son
dato histórico, no derivado.

**La existencia puede quedar negativa, y eso no es un error.** Solo el
administrador autoriza vender sin existencia, y la cifra negativa es
exactamente la deuda de reposición. El mismo mecanismo resuelve la venta
registrada sin conexión que al sincronizar encuentra el producto agotado.
Ver `docs/decisiones/ADR-0014-venta-sin-existencia.md`.

**No todas las empresas cobran impuesto.** La plataforma atiende
papelerías y panaderías además de distribuidores de tecnología. Cada
empresa declara si es responsable de IVA y qué tratamiento aplica a cada
categoría, escogiéndolo del catálogo legal que mantiene la plataforma.
Quien no es responsable no causa impuesto en ninguna venta. Ver
`docs/decisiones/ADR-0016-tributacion-en-dos-capas.md`.

**Los umbrales en UVT del artículo 424 no son universales.** Solo le
sirven a quien vende computadores o móviles, y por eso la empresa declara
aparte si aplica la exclusión por umbral. Para quien no la aplica, ese
tratamiento se comporta como gravado.

**Nada se elimina.** Productos, proveedores, usuarios y empresas se
desactivan, porque borrarlos rompería la trazabilidad.

**Toda consulta de datos de empresa filtra por `empresa_id`.** Una
consulta que lo olvide no falla: devuelve datos de otros comercios. Es la
clase de defecto que no se manifiesta en desarrollo con una sola empresa
cargada.

## Licencia y autoría

Jesús Manuel Durán Muñoz · SENA CIMI · Ficha 3234206
Instructora: Yeimi Barrera

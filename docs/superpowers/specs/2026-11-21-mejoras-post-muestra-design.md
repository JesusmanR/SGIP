# Mejoras posteriores a la muestra (2026-11-21)

**Estado:** el usuario aprobó las decisiones de producto el 2026-11-21. Esta versión 2 incorpora la revisión EGM (`.superpowers/sdd/mejoras-post-muestra/egm-review-design.md`) y las respuestas del usuario a lo que esa revisión dejó abierto.

**Origen:** las observaciones del usuario sobre la muestra del MVP, y una decisión sobre devoluciones.

**Depende de:** la entrega 1 de la caja (`feature/caja` y `feature/caja-pantallas`) unida a `develop`. Cada mejora nace de `develop` **después** de esa unión. Se re-verifica cada afirmación sobre el código contra ese `develop`, porque este análisis se hizo contra `feature/caja`.

**Roles:** se usan con los nombres del código: `ADMINISTRADOR`, `VENDEDOR`, `CLIENTE` y `SUPER_ADMIN`.

Hay tres arreglos que ya se hicieron el día de la muestra y no forman parte de este documento: el recibo de 80 mm, los grupos del menú y los descuentos en una fila.

## Por qué

En la muestra, el dueño del producto encontró huecos que una tienda nota el primer día:
- no hay dónde administrar los clientes;
- los datos de la empresa del recibo no se pueden corregir;
- importar precios sin plantilla invita a errores de referencia.

Además decidió que el administrador debe poder devolver sin abrir caja.

**Éxito:** el dueño de una tienda corrige sus datos, gestiona sus clientes y actualiza costos solo, sin dañar el cuadre de caja ni la identidad fiscal de la empresa.

**Anti-objetivos:**
- que la tienda cambie su NIT;
- que salga efectivo sin un turno que lo respalde;
- que una lista de precios cambie costos de productos de otro proveedor;
- que la reimpresión de un recibo viejo muestre datos de la empresa distintos de los del día de la venta.

## Decisiones del usuario (2026-11-21)

| Tema | Decisión |
|---|---|
| Devolución sin turno | El ADMINISTRADOR puede devolver por tarjeta, transferencia o saldo a favor. El efectivo exige un turno abierto. El VENDEDOR siempre necesita turno. |
| Mi empresa | El ADMINISTRADOR edita el nombre comercial, la dirección, la ciudad, el teléfono, el correo de contacto y el logo. El NIT y la razón social solo los cambia el SUPER_ADMIN, con auditoría. |
| Plantilla de precios | Trae los productos del proveedor ya puestos. |
| Lista con productos de otro proveedor | Se rechazan solo esas filas; el resto se aplica. |
| Recibo reimpreso | Muestra los datos de la empresa del día de la venta. |
| Documento del cliente | Se puede cambiar siempre, aunque el cliente tenga compras. Los recibos viejos mostrarán el documento nuevo; el usuario lo aceptó. |

## M1. Pantalla de clientes

**Qué:** una entrada «Clientes» en el menú, dentro de Operación, para ADMINISTRADOR y VENDEDOR.
- Lista paginada con búsqueda por nombre o número de identificación. Cada fila trae el tipo y número de documento, el nombre, el teléfono, el correo y el saldo a favor.
- Crear y editar un cliente, todos los campos incluido el documento.
- El saldo solo se muestra. No hay botón de borrar, porque los clientes tienen ventas.

**Backend (lo que hay hoy):** `/api/v1/caja/clientes` tiene un GET que busca por tipo y número exactos (404 si no existe; lo usa la caja y **no se toca**), POST y PUT `/{id}`. El saldo vive en `/caja/clientes/{id}/saldo`.

**Backend (nuevo):** `GET /api/v1/caja/clientes/lista?buscar=&pagina=&tamano=` para ADMINISTRADOR y VENDEDOR.
- `buscar` es opcional, se recorta, y busca por prefijo en el número de documento o por contenido en el nombre, sin distinguir mayúsculas.
- `tamano` vale 20 por defecto, con un máximo de 100.
- Devuelve `{elementos, pagina, tamano, total}`. El saldo sale en la **misma consulta**, agregado, sin N+1.
- Siempre filtra por la empresa del contexto.
- El consumidor final (CC 222222222222) aparece, pero no se puede editar: el PUT responde 409, como hoy.
- Lleva un índice si la búsqueda lo necesita, verificado con `EXPLAIN`.

**Pruebas:**
- La lista filtra por empresa.
- Una empresa no ve los clientes de otra.
- La búsqueda por nombre y por documento funciona.
- La paginación devuelve el total correcto.
- El saldo sale igual que en `/saldo`.
- El número de consultas es constante con 1 y con 50 clientes.
- CLIENTE y SUPER_ADMIN reciben 403. Agregar la ruta a `CajaRolesTest`.
- Web: buscar, crear, editar, y la autorización Ley 1581 obligatoria al crear, igual que en la caja.

## M2. Editar «Mi empresa» y los datos de la empresa en el recibo

**Esquema:** `empresas` ya tiene `correo_contacto`, `telefono`, `ciudad` y `logo_ruta`. Faltan `nombre_comercial` y `direccion`, que van en una migración nueva. La entidad `Empresa` se amplía con los campos que use.

**Endpoint del administrador:** `PUT /api/v1/empresa`, en un `EmpresaControlador` propio, para ADMINISTRADOR.
- El DTO solo acepta `nombreComercial`, `direccion`, `ciudad`, `telefono` y `correoContacto`.
- Un cuerpo que traiga `nit` o `razonSocial`, o cualquier campo desconocido, recibe 400.
- La empresa sale del contexto de seguridad, nunca del cuerpo.
- El logo sigue con el `POST /api/v1/empresa/logo` que ya existe.
- `EmpresaResumen` se amplía con los campos nuevos.

**Endpoint de plataforma:** `PUT /api/v1/plataforma/empresas/{id}/identidad`, para SUPER_ADMIN, con `{nit, razonSocial, motivo}`. El motivo es obligatorio y tiene entre 10 y 300 caracteres.
- **NIT:** solo dígitos, de 6 a 15, más un dígito de verificación calculado con el módulo 11 de la DIAN (pesos 3, 7, 13, 17, 19, 23, 29, 37, 41, 43, 47, 53, 59, 67, 71). Se guarda en el formato que ya usa la tabla.
  - Un NIT con mal formato o mal dígito de verificación da 400 `urn:sgip:empresa:nit-invalido`.
  - Un NIT que ya tiene otra empresa da 409.
- **Auditoría:** en `auditoria_plataforma`, una fila por cada campo que cambie, con el valor anterior, el nuevo, el motivo, quién y cuándo, en la misma transacción. Hace falta una migración que amplíe el ENUM y los CHECK de esa tabla, y un valor nuevo en `AccionPlataforma`.

**Copia en la venta** (la decisión «datos del día de la venta»):
- Se añaden a `ventas` las columnas `empresa_nombre`, `empresa_nit`, `empresa_direccion`, `empresa_ciudad` y `empresa_telefono`.
- `empresa_nombre` es el nombre comercial, o la razón social si no hay nombre comercial.
- Se llenan al registrar la venta, en la misma transacción. Leer la empresa no altera el orden de bloqueos del ADR-0030: es una lectura sin bloqueo.
- El recibo (`ServicioRecibos`) lee esa copia. Las ventas anteriores a la migración, con la copia nula, usan los datos actuales: queda escrito como límite aceptado.

**Web:**
- «Mi empresa» pasa a ser un formulario editable para el ADMINISTRADOR. El NIT y la razón social se ven como solo lectura, con la nota «Para cambiarlos, contacta a soporte».
- La ficha de empresa en plataforma gana «Cambiar NIT o razón social», con el motivo obligatorio.

**Pruebas:**
- Cruce de roles en los dos endpoints.
- Un ADMINISTRADOR que manda `nit` recibe 400.
- Una empresa no puede editar otra.
- El dígito de verificación: casos válidos e inválidos.
- NIT repetido: 409.
- La auditoría guarda una fila por campo.
- El recibo de una venta vieja sigue mostrando los datos viejos después de un cambio.
- Una venta anterior a la migración usa los datos actuales.

## M3. Plantilla de precios y rechazo de productos de otro proveedor

**Producto del proveedor:** es un producto cuyo `productos.proveedor_id` es el proveedor elegido. Antes de planificar, se confirma que esa columna es la fuente; si existe una relación varios a varios, la usa en su lugar.

**Rechazo nuevo en la importación:**
- Una fila cuya referencia existe en la empresa, pero cuyo producto es de otro proveedor, se rechaza con la causa `PRODUCTO_DE_OTRO_PROVEEDOR`.
- El resto del archivo se aplica.
- La web la muestra como «De otro proveedor», con la indicación «Esta referencia pertenece a otro proveedor; súbela en la lista de ese proveedor».

Esto **cambia** el comportamiento actual, que buscaba la referencia en todo el catálogo. El usuario lo decidió.

**Plantilla:** `GET /api/v1/importaciones/plantilla?proveedorId=`, solo para ADMINISTRADOR, porque lleva el costo.
- Un proveedor de otra empresa, o el proveedor reservado, da 404. Un `proveedorId` ausente o inválido da 400.
- Responde con `Content-Disposition: attachment; filename="plantilla-precios-<proveedor-sin-tildes>.xlsx"`.
- **Hoja 1, «Precios»:** es la primera hoja, porque el lector lee la hoja 0. Columnas `Referencia | Descripción | Costo`. Trae una fila por cada producto activo del proveedor, con su costo actual; si el costo es nulo o cero, la celda va vacía, y si se sube sin llenar, esa fila se rechaza como hoy.
- **Hoja 2, «Instrucciones».**
- Un proveedor sin productos da una plantilla con los encabezados y las instrucciones.
- Todas las celdas de texto se escriben como texto. Un valor que empiece por `=`, `+`, `-` o `@` se antepone con `'` para que Excel no lo ejecute como fórmula.
- Se genera en streaming (`SXSSFWorkbook`). El generador vive en una clase propia que importa POI, junto al lector; el ADR-0007 se enmienda: «POI solo en las clases de lectura y escritura de hojas de cálculo».
- **Presupuestos medidos en pruebas:**
  - el número de consultas es constante, con 1 y con 1000 productos;
  - con 20 000 productos el montón crece menos de 64 MB;
  - una ida y vuelta (descargar y subir sin tocar) deja 0 rechazos y 0 cambios de costo.

**Web:** en «Importar precios», al elegir el proveedor aparece «Descargar plantilla con mis productos».

## M4. Devolución del ADMINISTRADOR sin turno abierto

**Regla:** se evalúa por el **rol del usuario en sesión**, no por quien autoriza con PIN.
- Un ADMINISTRADOR sin turno abierto puede registrar una devolución si **ningún** reembolso es de tipo EFECTIVO. Valen tarjeta, transferencia, saldo a favor o una mezcla de ellos.
- La devolución queda con `devoluciones.cierre_id` nulo.
- Si hay algún reembolso en efectivo, responde 409 `urn:sgip:caja:turno-requerido-para-efectivo` con el mensaje «Abre un turno de caja para devolver efectivo.».
- El tipo de cada reembolso se determina cargando el medio de pago de la empresa.
- Un VENDEDOR sin turno sigue recibiendo el 409 actual, aunque traiga el PIN de un administrador.
- Las demás reglas de la devolución no cambian: el medio con que se pagó, el tope de lo no pagado con saldo, la idempotencia y el orden de bloqueos (ADR-0030). Sin turno no se toma la fila del turno; el resto del orden sigue igual.

**Cuadre:** esas devoluciones no entran en ningún cierre, porque ningún efectivo sale de un cajón. Se ven en el listado de devoluciones y en los reportes futuros. Esa brecha queda aceptada por escrito.

**Documentos:**
- Se enmiendan los ADR-0032 y ADR-0033, que exigían turno para toda devolución, con una nota que remite a este documento.
- Se actualiza el javadoc de `RegistradorDeDevolucion`.

**Web:** en «Devolución», si el administrador no tiene turno, el campo «Efectivo» queda deshabilitado con la ayuda «Para devolver efectivo, abre un turno de caja».

**Pruebas:**
- ADMINISTRADOR sin turno:
  - con tarjeta: queda registrada;
  - con saldo: queda registrada;
  - con tarjeta y saldo: queda registrada;
  - con tarjeta y efectivo: 409, y no queda nada a medias.
- VENDEDOR sin turno con el PIN de un administrador y tarjeta: 409.
- Venta pendiente de pago (contra entrega): se respetan las reglas actuales.
- Idempotencia: la misma clave sin turno se repite y devuelve 200.
- Orden de los errores: una venta de otra empresa da 404 antes que el 409 de turno.
- Carrera: si se abre un turno a la vez, la devolución sin turno no queda asociada a ese turno.
- El cierre de un turno no cuenta esas devoluciones.

## Orden, ramas y migraciones

1. Unir la entrega 1 de la caja a `develop`.
2. **M4** (`feat/devolucion-sin-turno`).
3. **M1** (`feat/pantalla-clientes`).
4. **M2** (`feat/editar-empresa`). Toca el recibo y el registro de la venta, así que va después de la caja.
5. **M3** (`feat/plantilla-precios`).

M1 y M3 pueden ir en paralelo con M4 o M2, cada una en su worktree. **Migraciones:** cada rama toma el siguiente número libre al nacer. La que se une segunda renumera la suya antes del merge y vuelve a correr la suite. Ninguna migración ya unida se edita.

## Lo que queda al constructor

Los nombres internos y la forma exacta de los DTO, siguiendo los patrones del proyecto. El diseño visual sigue el Figma nuevo y la skill `impeccable` en modo Operate.

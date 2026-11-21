# Mejoras posteriores a la muestra (2026-11-21)

**Estado:** el usuario aprobó las decisiones de producto el 2026-11-21. Este documento registra esas decisiones y cómo se construyen.
**Origen:** las observaciones que hizo el usuario al revisar la muestra del MVP, y una decisión sobre devoluciones.
**Depende de:** la entrega 1 de la caja (`feature/caja` y `feature/caja-pantallas`) unida a `develop`. Cada mejora nace de `develop` cuando esa unión ya está hecha.

Hay tres arreglos que ya se hicieron el día de la muestra: el recibo en tirilla de 80 mm, la separación de los grupos del menú y los descuentos en una sola fila. Están en `feature/caja-pantallas` y no forman parte de este documento.

## Por qué

En la muestra, el usuario, que es el dueño del producto, encontró huecos que una tienda real nota el primer día:
- no hay dónde administrar los clientes;
- los datos de la empresa que salen en el recibo no se pueden corregir;
- importar precios sin una plantilla invita a errores de referencia.

Además decidió que el administrador debe poder devolver sin abrir caja.

**Éxito:** el dueño de una tienda corrige sus datos, gestiona sus clientes y actualiza costos sin tener que llamar a nadie, y sin poder dañar el cuadre de caja ni la identidad fiscal de la empresa.

**Anti-objetivos:**
- que la tienda pueda cambiar su NIT;
- que salga efectivo sin un turno que lo respalde;
- que una plantilla mal descargada sobrescriba costos de productos ajenos.

## M1. Pantalla de clientes

**Qué:** una sección «Clientes» en el menú, dentro de Operación, para ADMIN y VENDEDOR. La pantalla permite:
- listar los clientes con búsqueda por nombre o identificación, paginada;
- crear un cliente;
- editar un cliente;
- ver el saldo a favor de cada cliente, sin poder editarlo.

**Backend:** ya existe `/api/v1/caja/clientes` (GET con búsqueda, POST y PUT `/{id}`, tarea 2 de la caja). La mejora usa ese contrato. Si a la búsqueda le falta paginación o el saldo, se añade con prueba.

**Reglas:**
- Las validaciones y la autorización del tratamiento de datos son las mismas que ya pide la caja (Ley 1581): la casilla «El cliente autoriza el tratamiento de sus datos».
- El consumidor final (CC 222222222222) no se edita.
- No se borran clientes, porque tienen ventas.

**Decisiones del orquestador** (el usuario puede corregirlas):
- No hay botón de borrar.
- El saldo solo se muestra; no se ajusta a mano.

## M2. Editar «Mi empresa»

**Decisión del usuario:**
- El ADMIN de la empresa edita el **nombre comercial, la dirección, el teléfono, el correo de contacto y el logo**. Esos datos alimentan el recibo.
- El **NIT y la razón social** solo los cambia el SUPER_ADMIN desde plataforma, y queda registro de quién, cuándo, el valor anterior y el nuevo.

**Construcción:**
- **Backend:** `PUT /api/v1/empresa` para el ADMIN, que acepta solo los campos permitidos e ignora o rechaza NIT y razón social con un 400 explícito. `PUT /api/v1/plataforma/empresas/{id}/identidad` para el SUPER_ADMIN, que cambia el NIT y la razón social, valida el dígito de verificación y escribe la auditoría.
- **Columnas:** las que falten (nombre comercial, dirección, teléfono, correo) van en una migración nueva. El logo ya existe (`/api/v1/empresa/logo`).
- **Recibo:** el recibo (tarea 11) muestra el nombre comercial (o la razón social si no hay nombre comercial), el NIT, la dirección y el teléfono.
- **Web:** «Mi empresa» pasa a ser un formulario editable para el ADMIN, donde el NIT y la razón social se muestran como solo lectura con la nota «Para cambiarlos, contacta a soporte». La ficha de empresa en plataforma gana la edición de identidad.

## M3. Plantilla para importar precios, con los productos ya puestos

**Decisión del usuario:** la plantilla trae los productos del proveedor ya escritos.

**Construcción:**
- **Backend:** `GET /api/v1/importaciones/plantilla?proveedorId=` (ADMIN) devuelve un `.xlsx` con las columnas `Referencia | Descripción | Costo`, con encabezados que `LectorHojaDeCalculo` ya reconoce. Trae una fila por cada producto activo de ese proveedor, con su costo actual. Al final hay una hoja «Instrucciones».
  - Se genera en streaming (`SXSSFWorkbook`) para no cargar el catálogo entero en memoria. Hay prueba de memoria acotada y de número de consultas constante.
  - El proveedor debe ser de la empresa; si no, 404.
  - Esta ruta es del ADMIN, porque el costo es solo del administrador (decisión de la puesta a punto).
- **Web:** en «Importar precios», al elegir el proveedor aparece el botón «Descargar plantilla con mis productos», junto a la zona de carga.
- **Protección:** si el archivo subido trae referencias de otro proveedor, se rechazan como hoy (`REFERENCIA_INEXISTENTE` u otra causa equivalente). La plantilla no cambia esa regla.

## M4. Devolución del administrador sin turno abierto

**Decisión del usuario:** sin turno, el administrador devuelve solo por medios que no son efectivo: tarjeta, transferencia o saldo a favor. Para devolver efectivo hace falta un turno abierto. El vendedor siempre necesita turno.

**Construcción:**
- **Backend:** `RegistradorDeDevolucion` permite a un ADMIN sin turno registrar la devolución si ningún reembolso es de tipo EFECTIVO. Esos reembolsos quedan con `cierre_id` nulo. Si hay efectivo, responde 409 «Abre un turno de caja para devolver efectivo.». No se altera el orden de bloqueos del ADR-0030; sin turno no se toma la fila del turno.
- **Web:** en «Devolución», si el administrador no tiene turno, el campo «Efectivo» queda deshabilitado con la ayuda «Para devolver efectivo, abre un turno de caja».
- **Pruebas:**
  - un admin sin turno devuelve con tarjeta y la operación queda registrada;
  - un admin sin turno con efectivo recibe 409;
  - un vendedor sin turno recibe 409, igual que hoy;
  - el cierre de un turno no cuenta esos reembolsos.

## Orden y ramas

1. **M4.** Es la más pequeña y toca la caja, que es lo más reciente.
2. **M1.** Solo pantalla.
3. **M2.**
4. **M3.**

Cada una va en su rama `feat/<slug>` desde `develop`, con TDD, `code-reviewer` y merge `--no-ff`. Ninguna depende de otra, salvo M2 → recibo: el recibo usa los campos nuevos de la empresa. Pueden ir en paralelo en worktrees separados.

## Lo que queda al constructor

Los nombres internos, la forma de los DTO y la paginación exacta, siguiendo los patrones del proyecto. El diseño visual sigue el Figma nuevo y la skill `impeccable` en modo Operate.

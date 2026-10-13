# Cliente de escritorio

React Native. Consume la API REST del backend.

## Esta carpeta ya no es lo que era

Aquí vivía el módulo administrativo en Java Swing heredado de la fase
previa. **Ese módulo no se migra y deja de ser parte de la solución**:
su función la asume el front web. Ver
`../docs/decisiones/ADR-0010-stack-del-front.md`.

De la fase previa se conservan el modelo de dominio, las reglas de
negocio validadas, el esquema de base de datos y la suite de pruebas
unitarias. Lo que se descarta es su interfaz gráfica, no su lógica.

## Para qué existe entonces un cliente de escritorio

Para que el comercio pueda seguir vendiendo cuando se cae el internet.

La aplicación es web. Un comercio que vende por unidad no puede detener
la caja porque el proveedor de red tenga una avería, y en los barrios
donde opera la plataforma eso pasa. El cliente de escritorio guarda
localmente lo necesario para cobrar, encola las operaciones y sincroniza
al recuperar la conexión.

Disponible para los cuatro roles. A diferencia del cliente móvil, aquí
sí entran el super administrador y el administrador, porque la pantalla
da para las tablas y los formularios que su trabajo necesita.

## Lo delicado

Es el mismo problema del cliente móvil y se resuelve igual: clave de
idempotencia por operación, cola local, y existencia negativa como
mecanismo de reconciliación cuando dos turnos venden la misma unidad sin
verse. Ver `../docs/decisiones/ADR-0012-operacion-sin-conexion.md`.

Dos cosas que conviene no olvidar al construirlo:

- **El almacén local guarda datos de una sola empresa.** Si el mismo
  equipo lo usan dos comercios distintos, cambiar de sesión tiene que
  vaciar el almacén, o el aislamiento que el esquema garantiza en el
  servidor se rompe en el dispositivo.
- **Un precio guardado localmente envejece.** La lista de precios cambia
  cuando el administrador importa la del proveedor. Al reconectar hay
  que refrescar antes de seguir cobrando, y advertir si se cobró con un
  precio que ya cambió.

Pendiente de iniciar.

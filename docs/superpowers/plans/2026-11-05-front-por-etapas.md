# Front por etapas: plan de implementación

> **Para agentes:** se ejecuta con `superpowers:subagent-driven-development` y, en la etapa 1, `superpowers:dispatching-parallel-agents`, cada agente en su propio worktree (`../SGIP-<slug>`).

**Objetivo:** construir las pantallas que ya tienen backend, usando el diseño de Figma como referencia visual y aplicando las decisiones del usuario que el diseño aún contradice.

**Arquitectura:** React 18, JavaScript y CSS propio. La etapa 0 deja una base en la que cada sección vive en sus propios archivos, para que varios agentes trabajen a la vez sin tocar lo mismo.

**Diseño de referencia:** `C:/Users/sxepc/Downloads/Nueva carpeta (5)/public/artifacts/sgip-pantallas/{escritorio,movil}/NN-*.png`. Son imágenes de consulta: no se copia ni se ejecuta código de esa descarga.

## Restricciones globales

- Moneda: pesos colombianos en todo (`web/src/servicios/formato.js`). Nunca US$.
- Prohibido en la interfaz: «Alex Morgan», «Vista actual», «Perfil de demostración», «Prototipo interactivo», «Imágenes de pantallas», «Estados del sistema» como entrada de menú, datos inventados, cifras decorativas.
- El margen no es automático. Una regla general de ganancia es opcional y nunca se presenta como obligatoria; el % y el precio se editan por producto (ADR-0025).
- Los impuestos son opcionales: debe poder usarse sin configurarlos y decir «no cobro IVA» (ADR-0024). Nada de IVA 19 % por omisión.
- Solo el administrador ve costo, proveedores, kárdex y márgenes. El vendedor y el cliente, nunca.
- «Accesos» solo restablece; jamás muestra una contraseña.
- Solo llamadas a rutas que existen hoy. Si falta una ruta, la pantalla no finge tenerla: se informa y se omite la acción.
- En celular (390 px), tarjetas o lista en vez de tablas cortadas, sin desplazamiento horizontal de la página.
- `web/src/estilos/tokens.css` es la única fuente de color. Interfaz con la skill `impeccable`, modo Operate.
- Cada pantalla cubre carga, vacío, error con reintento y sin permiso. Etiquetas visibles, errores junto al campo y no solo por color, y teclado.
- Payloads pequeños y listados paginados donde la ruta lo permita, sin peticiones redundantes.
- TDD con Vitest. Texto de interfaz, código y comentarios en español.

## Etapa 0: base común (un agente, antes que las demás)

**Rama:** `feature/web-base`, desde `develop`, cuando T12 y T13 estén integradas.

- **Armazón:** menú lateral y barra superior por rol, en `web/src/componentes/`. En celular, menú desplegable (pantalla 09).
- **Rutas:** todas las secciones de la etapa 1 quedan registradas una sola vez, con guarda de rol, y cada una apunta a una página propia que de momento dice que está en construcción. Quien no tiene permiso va a su inicio y no ve el enlace. Esto cierra el hallazgo de `/importar` abierto por URL.
- **API:** `pedir` se exporta; cada sección añade su módulo en `web/src/servicios/api/<seccion>.js`. El `api.js` actual se conserva.
- **Piezas compartidas:** encabezado de página, tabla que pasa a tarjetas en celular, estados de carga, vacío y error, y aviso. Con pruebas.
- **Estilos:** cada sección importa su `web/src/estilos/<seccion>.css`.

Se adaptan solo al armazón las pantallas actuales (panel, catálogo, empresa, importación, cambio de clave). Su rediseño interno va en la etapa 2.

**Criterio:** suite web en verde; un vendedor no alcanza ninguna ruta de administrador, y un administrador no alcanza las de plataforma, probado por ruta.

## Etapa 1: secciones en paralelo

Cada una en su worktree y su rama desde `develop` tras la etapa 0. Solo escribe en sus archivos: su página o páginas, `servicios/api/<seccion>.js` y `estilos/<seccion>.css`. Si necesita tocar algo compartido, lo informa en vez de hacerlo. Máximo tres agentes a la vez.

| Rama | Pantallas de referencia | Rutas del backend |
|---|---|---|
| `feature/web-usuarios` | 20 | `/usuarios`: listar, ver, crear, editar, activar, desactivar, desbloquear |
| `feature/web-compras` | 17, 36–41 | `/proveedores` (listar, ver, crear) y `/compras/ordenes` (listar, ver, crear, recepciones) |
| `feature/web-inventario` | 18, 42–45, 78 | `/inventario`: movimientos, cuadre, ingresos, ajustes |
| `feature/web-precios-impuestos` | 46–51 | `/empresa/tributacion`, `/precios/reglas`, con las restricciones de impuestos y márgenes |
| `feature/web-plataforma` | 70, 71, 74, 75 | `/plataforma/empresas`, `/plataforma/tributario`, alerta de UVT |

Cada rama sigue el flujo del manual: pruebas en rojo y en verde, verificación del orquestador, `code-reviewer`, CI verde y merge `--no-ff` a `develop`, de una en una.

## Etapa 2: rediseño de lo existente

Acceso, cambio de clave, panel, catálogo y formulario de producto, empresa e importación, según el diseño (pantallas 03, 06–08, 10–12, 22, 23, 52, 53–59, 60–62). Se reparte cuando termine la etapa 1.

## Fuera de este plan

Necesitan backend que no existe: caja, ventas, recibo, clientes, cotizaciones, reportes, registro público, página de inicio, recuperar clave, planes y suscripciones. La caja y el recibo van en el Sprint 9, después de resolver la lentitud anotada en ADR-0025. Decisión del usuario que el diseño aún contradice: todos los planes venden e imprimen recibo.

# Front web

React sobre HTML, CSS y JavaScript. Consume la API REST del backend y no
implementa lógica de negocio propia.

Es el canal principal: los cuatro roles entran por aquí. El módulo
administrativo en Java Swing de la fase previa **no se migra**; su
función la asume este front. Ver
`../docs/decisiones/ADR-0010-stack-del-front.md`.

## Alcance

| Área | Rol | Pantallas diseñadas |
|---|---|---|
| Plataforma | Super administrador (BSP) | Por diseñar |
| Configuración y usuarios | Administrador | P-02, P-12, P-13 |
| Catálogo y precios | Administrador | P-03 a P-07 |
| Cotización y venta | Vendedor | P-08 a P-11 |
| Consulta | Cliente | P-03 (restringida) |

Las pantallas están en `../docs/mockups/` en SVG y PNG, con los tokens
de color ya aplicados. Faltan por diseñar las de plataforma —alta de
empresa, listado con estado de suscripción—, las de caja —cobro con
medio de pago, cierre de turno— y la de estado de sincronización.

## Estructura prevista

```
src/
  componentes/   Botón, campo, insignia de estado, tarjeta, medidor de umbral
  paginas/       Una por pantalla del catálogo de mock-ups
  servicios/     Cliente de la API; el único lugar que conoce rutas y tokens
  hooks/         Estado compartido y sesión
  estilos/       tokens.css — la paleta y las medidas como variables CSS
publico/
```

`estilos/tokens.css` ya está escrito y es la única fuente de color del
front. **Ningún componente escribe un valor hexadecimal suelto**: si un
color hace falta y no está en los tokens, se agrega al token y se
discute, porque los colores de estado están calculados para cumplir el
contraste que exige el RNF-13.

## Reglas que este front debe respetar

**Las insignias de estado siempre llevan texto.** Nunca un punto o un
círculo de color a secas. Los cuatro colores de estado de la paleta
Aurora Mist se vuelven indistinguibles en escala de grises, de modo que
un usuario con dificultad para distinguir colores depende del texto.
Ver `../docs/decisiones/ADR-0009-sistema-de-diseno.md`.

**El medidor de umbral tributario es el único componente inventado.**
Sitúa el precio respecto del tope de exclusión de IVA, con la zona
excluida en verde y la gravada en rojo. Aparece en la ficha de producto,
en el registro y en la importación de precios. El resto son patrones
establecidos, y conviene que lo sean: en una herramienta de uso diario
la originalidad en los controles se paga en confusión.

**Las cifras usan numeración tabular** (`font-variant-numeric:
tabular-nums`), o las columnas de dinero no alinean.

**El front no calcula precios.** El precio de venta, el tratamiento de
IVA y el umbral vienen resueltos del backend, en la vista
`v_precios_producto`. Replicar ese cálculo en JavaScript crea una
segunda implementación de la regla que divergirá de la primera.

## Pendiente de iniciar

El flujo de integración continua tiene el trabajo de compilación
desactivado con `if: false` hasta que exista `package.json` con código
real.

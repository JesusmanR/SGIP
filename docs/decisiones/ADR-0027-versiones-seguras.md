# ADR-0027 · Versiones seguras: LTS en Java y Node, último parche estable en las librerías

Estado: aceptada · 2026-11-03

## Contexto

El usuario pidió pasar a las versiones LTS vigentes por fallas de
seguridad. «LTS» (soporte largo) solo existe para Java y Node. Las
librerías no la tienen: su equivalente es el último parche estable de la
versión mayor que ya se usa.

Estado de partida: `npm audit` daba 14 avisos (2 críticos, 4 altos,
7 moderados, 1 bajo) y OSV daba, sobre las versiones resueltas del
backend, 4 críticas en `tomcat-embed-core` 11.0.24, 1 moderada en
`poi-ooxml` 5.3.0 y, además, 7 en Jackson 3.1.5 (4 altas, 3 moderadas
entre `jackson-core` y `jackson-databind`).

## Decisión

**Política.** Java y Node van en la LTS más nueva. Las librerías van en
el último parche estable de su versión mayor; solo se cambia de mayor
cuando un aviso alto o crítico no tiene otra salida. Las versiones se
siguen fijando exactas (`--save-exact`).

**Backend.** Java 25 (LTS más nueva; no se pasa a 27, que no es LTS).
Spring Boot 4.1.1 pasa a **4.1.2**, solo en el `parent`: arrastra
Tomcat 11.0.27, Hibernate 7.4.13, Jackson 3.1.7 y Spring Security 7.1.2,
sin fijar versiones sueltas de lo que Boot gestiona. Apache POI pasa de
5.3.0 a **5.5.1**, el último 5.x. `commons-lang3` ya resuelve a 3.20.0
(por encima de 3.18.0), así que no hace falta fijarlo.

**Web.** `react-router-dom` 6.30.6, `eslint` y `@eslint/js` 9.39.5,
`eslint-plugin-react` 7.37.5, sin salir de la versión mayor. Esto cerró
los avisos altos de producción, pero `vite`, `vitest` y `tinypool`
seguían con un aviso alto y dos críticos que solo se resuelven en la
mayor siguiente, de modo que `vite` 8.3.5, `@vitejs/plugin-react` 6.2.1,
`vitest` 5.1.0 y `jsdom` 30.1.3 suben juntos, en un commit aparte. Las
pruebas no necesitaron cambios.

**Node.** La integración continua usa **Node 26** (LTS desde el
2026-10-28). El equipo sigue en Node 24.22.2 en local por decisión
propia, y el proyecto debe funcionar en las dos: `vitest` 5 y `jsdom` 30
admiten ambas. Las acciones de GitHub suben a `checkout` v7,
`setup-java` v6 y `setup-node` v7.

**Defensa permanente.** El trabajo web ejecuta
`npm audit --omit=dev --audit-level=high`: lo que llega al navegador no
puede tener avisos altos ni críticos.

## Qué no se movió y por qué

- **React y React DOM 18.3.1:** sin avisos; el paso a 19 va con el
  rediseño.
- **Apache POI 6:** salto de mayor sin aviso que lo exija.
- **Spring Boot 4.2.0-RC1:** no es una versión estable.
- **React Router 7:** ver abajo; los avisos que lo piden son moderados y
  no aplican.

## Avisos que quedan (`npm audit`: 8 moderados, 0 altos, 0 críticos)

| Paquete | Gravedad | Dónde | Aplica a SGIP |
|---|---|---|---|
| `react-router` / `react-router-dom` (GHSA-wrjc-x8rr-h8h6, GHSA-337j-9hxr-rhxg) | moderada | navegador | No. El primero exige que el destino de `<Link>`/`navigate` venga de una entrada externa, y aquí los destinos son rutas fijas o salen del rol; el segundo es de hidratación con SSR, y SGIP es una SPA sin SSR. Se corrige solo en 7.18.0 (cambio de mayor). |
| `minimatch` < 11.0.1 (GHSA-g3hv-75wg-m249) y, a través de él, `eslint`, `eslint-plugin-react`, `@eslint/config-array`, `@eslint/eslintrc`, `@eslint-community/eslint-utils` | moderada | solo desarrollo | No. Denegación de servicio con patrones glob hostiles; el lint solo lee patrones del propio repositorio. Se corrige con minimatch 11, que ESLint 9 no admite. |

Se revisarán al pasar a React Router 7 y a la siguiente mayor de ESLint.

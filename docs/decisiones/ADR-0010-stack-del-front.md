# ADR-0010 · React y React Native en lugar de Kotlin y Swing

Estado: aceptada · 2026

## Contexto

El plan original tenía tres interfaces en tres tecnologías: Android
nativo con Kotlin para el móvil, Java Swing para el módulo
administrativo heredado de la fase previa, y ninguna para la web.

Al reencuadrar el producto como plataforma multiempresa (ADR-0011) el
reparto deja de funcionar. Una plataforma a la que entran comercios
distintos necesita que el alta sea inmediata: un tendero no instala un
programa de escritorio para probar un servicio, y BSP no puede asistir
una instalación por cada tienda que se registra. El canal de entrada
tiene que ser el navegador.

Eso convierte el módulo de escritorio en el tercer front de una
aplicación cuyo front principal todavía no existía.

## Decisión

El front web se construye en **React** sobre HTML, CSS y JavaScript. Los
clientes de escritorio y móvil se construyen en **React Native**.

El módulo administrativo en Java Swing **no se migra y deja de ser parte
de la solución**. Su función la asume el front web.

De la fase previa se conservan el modelo de dominio, las reglas de
negocio validadas, el esquema de base de datos y la suite de pruebas
unitarias. Lo que se descarta es su interfaz gráfica.

El backend no cambia: Java 17 con Spring Boot 3 y API REST.

## Por qué

Tres frentes en un mismo lenguaje y un mismo modelo de componentes
cuestan menos de mantener que tres en tres. El componente que muestra el
medidor de umbral tributario se escribe una vez y vale para los tres; en
Kotlin, Swing y JavaScript habría que escribirlo tres veces, y las tres
divergirían.

La alternativa era conservar Swing para administración y hacer la web
solo para vendedor y cliente. Se descartó porque obliga a BSP a
distribuir e instalar software en cada comercio nuevo, que es justo el
costo que una plataforma como servicio existe para evitar.

## Consecuencias

**El trabajo de interfaz de la fase previa se pierde.** Es un costo
real, y conviene nombrarlo: la pantalla de acceso en Swing, con su
derivación de contraseñas y su auditoría, se reimplementa. La lógica que
estaba detrás no, porque vive en el dominio y en el esquema.

**Aparece una dependencia del ecosistema de JavaScript**, con su ritmo
de actualizaciones. Las versiones se fijan y no se actualizan a mitad de
sprint.

**El equipo necesita React.** Para un proyecto de formación eso es
también el argumento a favor: es la tecnología de front más pedida en el
mercado al que apunta el programa.

**No reemplaza la ADR-0001.** El reparto de responsabilidades sigue
siendo el mismo —el backend concentra la lógica, los clientes la
consumen— y por eso esa decisión sigue vigente. Lo que cambia aquí es
con qué se construyen los clientes, no qué hacen.

## Lo que esta decisión no resuelve

React Native para escritorio no es la opción más transitada; lo habitual
es Electron o Tauri. Se elige por el lenguaje compartido con el móvil,
pero conviene validarlo temprano con un prototipo que cobre una venta
sin conexión. Si el soporte de escritorio resulta frágil, la decisión a
revisar es esta y no la arquitectura.

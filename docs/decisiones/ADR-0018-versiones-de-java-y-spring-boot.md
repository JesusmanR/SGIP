# ADR-0018 · Java 25 y Spring Boot 4.1.1 en el backend

Estado: aceptada · 2026

## Contexto

El ADR-0010 decidió el stack de los clientes —React para el front web y
React Native para escritorio y móvil— y de paso dejó escrito que «el
backend no cambia: Java 17 con Spring Boot 3». Esa frase no era la
decisión que el documento venía a tomar: era una nota al margen sobre lo
que entonces se daba por hecho.

El esqueleto del backend se generó el 18 de septiembre de 2026 con
Spring Boot 4.1 y Java 25, que eran las versiones vigentes ese día. De
modo que el proyecto lleva desde su primer commit corriendo sobre unas
versiones distintas de las que su propio registro de decisiones declara.
Nadie decidió el cambio; simplemente nadie escribió la decisión.

El desajuste se detectó al redactar la versión 5 del informe, que
contrasta lo documentado con lo que el repositorio hace. Es menor en
consecuencias y no lo es en principio: un registro de decisiones vale
por ser fiable, y una entrada que describe un stack que no se usa
envenena las que vienen después, porque quien la lee decide sobre una
base falsa.

## Decisión

El backend corre sobre **Java 25** y **Spring Boot 4.1.1**.

Las versiones se fijan y no se mueven a mitad de sprint, que es el mismo
criterio que el ADR-0010 estableció para las dependencias del front y
que la cadena de integración continua ya aplica al fijar Node 22.

El ADR-0010 **no queda superado**: su decisión —React y React Native en
lugar de Kotlin y Swing— sigue vigente íntegra. Lo que esta entrada
corrige es la frase sobre el backend que aquella incluía de pasada.

## Por qué

**No se eligieron: vinieron con la fecha.** Conviene decirlo así y no
construir una justificación a posteriori. Spring Boot 4.1 y Java 25 eran
lo vigente cuando se generó el esqueleto, y nada obligaba a bajar a una
versión anterior.

**Bajar ahora costaría más que documentarlo.** El proyecto lleva sesenta
commits sobre estas versiones, con la cadena de integración continua
compilando y probando contra ellas. Revertir a Java 17 y Boot 3 sería
trabajo de migración sin beneficio.

**Java 25 es una versión con soporte prolongado**, de modo que la
elección no obliga a actualizar a mitad de proyecto para seguir
recibiendo correcciones.

## Consecuencias

**A favor**

- El registro de decisiones vuelve a describir el sistema que existe.
- La tabla de marco tecnológico del informe, el README y este registro
  dicen lo mismo, que es la condición para que cualquiera de los tres
  sirva de referencia.

**En contra**

- El material de formación del programa se apoya en versiones
  anteriores, de modo que algún ejemplo del curso no se traslada tal
  cual. Es un costo de aprendizaje, no un costo técnico.
- Spring Boot 4 trae cambios respecto de la 3 que invalidan parte de lo
  que se encuentra escrito en línea sobre la 3. Ya se pagó una vez: el
  usuario en memoria que la configuración por omisión crea se tuvo que
  apagar de forma explícita.

## Lo que esta decisión no resuelve

No fija una política de actualización. Cuándo pasar a la siguiente
versión mayor, y con qué criterio, queda sin decidir hasta que haya un
motivo concreto para plantearlo.

# ADR-0002 · PBKDF2 para derivación de contraseñas

Estado: aceptada · 2026

## Contexto

La especificación de la fase previa exigía bcrypt, pero la
implementación empleaba PBKDF2. Documento e implementación se
contradecían, y esa clase de discrepancia es la que produce auditorías
fallidas.

## Decisión

Se documenta PBKDF2 sobre SHA-256, con 120.000 iteraciones y sal
aleatoria de dieciséis bytes por usuario. Formato almacenado:

    pbkdf2$iteraciones$sal$hash

La derivación se aísla en la clase de utilidad `Seguridad`.

## Consecuencias

PBKDF2 resiste peor el ataque con hardware especializado que Argon2id,
que es el algoritmo recomendado hoy. Se acepta la diferencia porque
PBKDF2 está en la biblioteca estándar de Java, no añade dependencias y
supera con holgura el almacenamiento en claro o con hash simple.

La migración a Argon2id queda como trabajo futuro. El aislamiento en una
sola clase la vuelve barata: el formato almacenado lleva prefijo, de modo
que ambos algoritmos pueden convivir mientras los usuarios renuevan su
contraseña.

El número de iteraciones es un parámetro, no una constante: debe
revisarse al alza cada dos o tres años.

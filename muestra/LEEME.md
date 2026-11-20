# Muestra local de SGIP

Un doble clic en `arrancar-muestra.cmd` levanta todo en tu computador: su propia
base de datos (aparte de la tuya), el sistema y la pantalla, y abre el navegador.
Para apagar todo, doble clic en `detener-muestra.cmd`.

- La primera vez tarda varios minutos (prepara la base y el programa). Espera.
- Los datos viven en la carpeta `.muestra-datos/` (no se sube a Git). Para
  empezar de cero: apaga la muestra y borra esa carpeta.
- Si lo abres dos veces, la segunda solo abre el navegador.
- Si algo falla, el mensaje dice qué mirar en `.muestra-datos/registros/`.

Necesita Java 25, Node 24, MySQL Server 9.7 (solo el programa; no usa tu base)
y Git para Windows.

## Usuarios (claves de prueba, solo locales)

| Quién | Correo | Clave |
|---|---|---|
| Administrador | admin@muestra.com | Admin-Muestra-26 |
| Vendedor | vendedor@muestra.com | Vende-Muestra-26 |
| Plataforma | plataforma@muestra.com | Plata-Muestra-26 |

PIN del administrador (cuando la caja exista en esta rama): 4829.

Ninguno obliga a cambiar la clave al entrar. La empresa de la muestra queda
en estado ACTIVA, así que la prueba gratuita no la afecta.

## Para quien mantiene esto

- Lógica: `lib/muestra.sh`. Usuarios: `datos/usuarios_muestra.sql`.
- Prueba de punta a punta: `bash muestra/verificar_muestra.sh`.
- Esas mismas claves están como valores por defecto en `lib/muestra.sh`
  (la prueba las necesita para entrar).

#!/usr/bin/env bash
# Comprueba que `verificar_caja.sh` SE NIEGA a ejecutarse donde no debe, y que
# lo dice en claro. El guion de la caja crea ventas, turnos y movimientos de
# kárdex que no se pueden deshacer, y deja la empresa 1 activa: apuntarlo por
# error a una base de verdad sería destruir datos. Aquí se prueban sus cinco
# negativas, sin arrancar el backend ni construir nada:
#
#   1. sin SGIP_CAJA_BD_DESECHABLE=si;
#   2. con un servidor que no es de esta máquina;
#   3. con usuarios que no son los de la demostración (V8) ni los que el
#      propio guion crea;
#   4. con -p suelto (la contraseña va en MYSQL_PWD o pegada: -p<clave>);
#   5. con -P, -h o -u sin valor.
#
# Se usa con las mismas opciones de conexión que los demás guiones:
#
#   bash verificar_caja_guardas.sh -h 127.0.0.1 -P 3306 -u root
#   MYSQL_PWD=secreto bash verificar_caja_guardas.sh -h 127.0.0.1 -u root
#
# Para el caso 3 inserta un usuario ajeno y lo borra al salir, también si algo
# falla. Necesita la base `sgip` con la cadena V1 a V31 (usuarios y roles).
set -uo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CAJA="$AQUI/verificar_caja.sh"
CONEXION=("$@")

if ! command -v mysql >/dev/null 2>&1; then
  for d in "/c/Program Files/MySQL"/*/bin; do
    [[ -x "$d/mysql.exe" ]] && { PATH="$PATH:$d"; break; }
  done
fi
command -v mysql >/dev/null 2>&1 || { echo "Falta 'mysql' en el PATH."; exit 1; }

sin_aviso () { grep -v "Using a password on the command line interface" || true; }
ejecutar () { mysql --no-defaults "${CONEXION[@]}" sgip -e "$1" 2>&1 | sin_aviso; }

fallos=0
ok ()    { echo "  ok    · $1"; }
falla () { echo "  FALLA · $1"; fallos=$((fallos + 1)); }

AJENO="guardas-ajeno@verificar.local"
limpiar () { ejecutar "DELETE FROM usuarios WHERE correo = '$AJENO';" >/dev/null; }
trap limpiar EXIT
trap 'exit 130' INT TERM
limpiar

# Con un JAR que no existe: si una guarda no frena, el guion se detiene de
# todos modos en el primer paso y NO llega a tocar la base. Lo que se mira es el
# mensaje, no solo que falle.
export SGIP_CAJA_JAR="/no/existe/backend.jar"

# $1 descripción · $2 fragmento que debe decir el mensaje · el resto: el
# entorno extra y los argumentos, tal como se pasarían al guion.
niega () {
  local descripcion="$1" fragmento="$2"; shift 2
  local salida codigo
  salida=$(env "$@" 2>&1); codigo=$?
  if [[ "$codigo" -eq 0 ]]; then
    falla "$descripcion — el guion terminó con éxito"
  elif [[ "$salida" != *"$fragmento"* ]]; then
    falla "$descripcion — falló, pero sin decir «$fragmento»: $(head -c 300 <<<"$salida" | tr '\n' ' ')"
  else
    ok "$descripcion"
    echo "          » $(grep -m1 -F "$fragmento" <<<"$salida")"
  fi
}

echo "── las negativas de verificar_caja.sh ──"

niega "sin SGIP_CAJA_BD_DESECHABLE=si no corre" "SGIP_CAJA_BD_DESECHABLE=si" \
  -u SGIP_CAJA_BD_DESECHABLE bash "$CAJA" "${CONEXION[@]}"

niega "con otro valor tampoco (solo «si» vale)" "SGIP_CAJA_BD_DESECHABLE=si" \
  SGIP_CAJA_BD_DESECHABLE=1 bash "$CAJA" "${CONEXION[@]}"

niega "con un servidor remoto no corre, aunque se declare desechable" "loopback" \
  SGIP_CAJA_BD_DESECHABLE=si bash "$CAJA" -h 10.255.255.1 -u root

niega "con «localhost.evil.com» tampoco: no es el loopback" "loopback" \
  SGIP_CAJA_BD_DESECHABLE=si bash "$CAJA" -h localhost.evil.com -u root

# El usuario ajeno: una empresa de verdad ya tiene sus usuarios.
salida=$(ejecutar "INSERT INTO usuarios (empresa_id, rol_id, nombre, correo, clave_hash, activo, bloqueado,
                                          intentos_fallidos, cambio_clave_req)
                   SELECT 1, rol_id, 'Usuario ajeno de la prueba de guardas', '$AJENO', 'x', 1, 0, 0, 0
                     FROM roles WHERE nombre = 'VENDEDOR';")
if [[ -n "$salida" ]]; then
  falla "no se pudo crear el usuario ajeno: $salida"
else
  niega "con un usuario que no es de la demostración no corre" "no son de la demostración" \
    SGIP_CAJA_BD_DESECHABLE=si bash "$CAJA" "${CONEXION[@]}"
fi
limpiar

niega "un -p suelto dice qué hacer" "MYSQL_PWD" \
  SGIP_CAJA_BD_DESECHABLE=si bash "$CAJA" -h 127.0.0.1 -u root -p

niega "-P sin valor es un error" "La opción -P" \
  SGIP_CAJA_BD_DESECHABLE=si bash "$CAJA" -h 127.0.0.1 -u root -P

niega "-h sin valor es un error" "La opción -h" \
  SGIP_CAJA_BD_DESECHABLE=si bash "$CAJA" -u root -h

niega "un puerto que no es un número es un error" "La opción -P" \
  SGIP_CAJA_BD_DESECHABLE=si bash "$CAJA" -h 127.0.0.1 -P abc -u root

echo
if [[ "$fallos" -eq 0 ]]; then
  echo "TODO EN VERDE"
  exit 0
fi
echo "$fallos comprobación(es) fallaron"
exit 1

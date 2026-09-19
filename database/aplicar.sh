#!/usr/bin/env bash
# Aplica las migraciones del SGIP en orden.
# Uso:  ./aplicar.sh [-u usuario] [-p] [-h host] [--recrear] [--demo]
#
#   --recrear  borra la base y la reconstruye desde cero
#   --demo     carga además datos de prueba
set -euo pipefail

USUARIO="root"
HOST=""          # vacío usa el socket local
PEDIR_CLAVE=0
DEMO=0
RECREAR=0
CNF=""

# El archivo temporal con la credencial se borra pase lo que pase:
# al terminar bien, al fallar o si interrumpes con Ctrl-C.
limpiar() { [[ -n "$CNF" && -f "$CNF" ]] && rm -f "$CNF"; }
trap limpiar EXIT INT TERM

while [[ $# -gt 0 ]]; do
  case "$1" in
    -u) USUARIO="$2"; shift 2 ;;
    -h) HOST="$2"; shift 2 ;;
    -p) PEDIR_CLAVE=1; shift ;;
    --demo) DEMO=1; shift ;;
    --recrear) RECREAR=1; shift ;;
    *) echo "Opción desconocida: $1"; exit 1 ;;
  esac
done

# ── Localizar el cliente mysql ───────────────────────────────────────
# El instalador MSI de Windows no agrega su carpeta al PATH, así que
# antes de fallar se revisan las rutas de instalación habituales.
if ! command -v mysql >/dev/null 2>&1; then
  for d in "/c/Program Files/MySQL"/*/bin \
           "/c/Program Files (x86)/MySQL"/*/bin \
           "/mnt/c/Program Files/MySQL"/*/bin; do
    if [[ -x "$d/mysql.exe" || -x "$d/mysql" ]]; then
      PATH="$PATH:$d"
      echo "cliente mysql encontrado en: $d"
      break
    fi
  done
fi

if ! command -v mysql >/dev/null 2>&1; then
  cat >&2 <<'AYUDA'
No se encontró el cliente 'mysql'.

Si MySQL está instalado, falta agregar su carpeta bin al PATH.
En Windows suele ser:

  C:\Program Files\MySQL\MySQL Server 9.7\bin

Solo para esta terminal:

  export PATH="$PATH:/c/Program Files/MySQL/MySQL Server 9.7/bin"

De forma permanente en Git Bash:

  echo 'export PATH="$PATH:/c/Program Files/MySQL/MySQL Server 9.7/bin"' >> ~/.bashrc

Si la carpeta no existe, MySQL no está instalado en esta máquina.
Cada equipo necesita su propio motor: la base de datos no viaja por Git,
se reconstruye aquí con estas migraciones.
AYUDA
  exit 1
fi

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/migrations"
# Se pide la contraseña una sola vez y se guarda en un archivo temporal
# de configuración, en lugar de repetir el -p en cada llamada. Pasarla
# como argumento (-pClave) la dejaría en el historial de la terminal y
# visible en la lista de procesos del sistema.
if [[ $PEDIR_CLAVE -eq 1 ]]; then
  read -r -s -p "Contraseña de MySQL para '$USUARIO': " CLAVE_TXT
  echo
  CNF="$(mktemp)"
  chmod 600 "$CNF" 2>/dev/null || true
  printf '[client]\nuser=%s\npassword=%s\n' "$USUARIO" "$CLAVE_TXT" > "$CNF"
  unset CLAVE_TXT
  # Git Bash entrega rutas tipo /tmp/... que mysql.exe no entiende.
  CNF_ARG="$CNF"
  command -v cygpath >/dev/null 2>&1 && CNF_ARG="$(cygpath -w "$CNF")"
  # --defaults-extra-file debe ir como primera opción.
  MYSQL=(mysql "--defaults-extra-file=$CNF_ARG")
else
  MYSQL=(mysql -u "$USUARIO")
fi
[[ -n "$HOST" ]] && MYSQL+=(-h "$HOST")

# Las migraciones no son idempotentes: cada una supone que su parte aún
# no existe. Si la base ya tiene tablas hay que recrearla, no reaplicar.
YA=$("${MYSQL[@]}" -N -B -e \
  "SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema='sgip';" 2>/dev/null || echo 0)

if [[ "${YA:-0}" -gt 0 && $RECREAR -eq 0 ]]; then
  cat >&2 <<AYUDA
La base 'sgip' ya tiene $YA objetos.

Las migraciones solo se aplican sobre una base vacía. Para reconstruirla
desde cero, que es lo habitual en desarrollo:

  bash aplicar.sh -u $USUARIO -p --recrear

Eso BORRA la base y todos sus datos. El esquema se rehace con estas
mismas migraciones, así que no se pierde estructura, solo los datos que
hayas cargado.
AYUDA
  exit 1
fi

if [[ $RECREAR -eq 1 ]]; then
  echo "→ borrando la base existente"
  "${MYSQL[@]}" -e "DROP DATABASE IF EXISTS sgip;"
fi

echo "→ creando la base de datos"
"${MYSQL[@]}" < "$DIR/00__crear_base.sql"

for f in "$DIR"/V[1-7]__*.sql; do
  echo "→ $(basename "$f")"
  "${MYSQL[@]}" sgip < "$f"
done

if [[ $DEMO -eq 1 ]]; then
  echo "→ datos de demostración"
  "${MYSQL[@]}" sgip < "$DIR/V8__datos_demo.sql"
fi

tablas=$("${MYSQL[@]}" -N -B -e \
  "SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema='sgip' AND table_type='BASE TABLE';")
vistas=$("${MYSQL[@]}" -N -B -e \
  "SELECT COUNT(*) FROM information_schema.views WHERE table_schema='sgip';")

echo "listo: $tablas tablas, $vistas vistas"
[[ "$tablas" -eq 24 ]] || { echo "ATENCIÓN: se esperaban 24 tablas"; exit 1; }

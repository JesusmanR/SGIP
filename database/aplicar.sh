#!/usr/bin/env bash
# Aplica las migraciones del SGIP en orden.
# Uso:  ./aplicar.sh [-u usuario] [-p] [-h host] [--demo]
set -euo pipefail

USUARIO="root"
HOST=""          # vacío usa el socket local
CLAVE=()
DEMO=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    -u) USUARIO="$2"; shift 2 ;;
    -h) HOST="$2"; shift 2 ;;
    -p) CLAVE=(-p); shift ;;
    --demo) DEMO=1; shift ;;
    *) echo "Opción desconocida: $1"; exit 1 ;;
  esac
done

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/migrations"
MYSQL=(mysql -u "$USUARIO")
[[ -n "$HOST" ]] && MYSQL+=(-h "$HOST")
[[ ${#CLAVE[@]} -gt 0 ]] && MYSQL+=("${CLAVE[@]}")

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

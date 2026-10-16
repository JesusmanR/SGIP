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

# El orden va escrito a mano, no con comodín. Un glob los ordena
# alfabéticamente y 'V10' queda antes de 'V2', lo que rompe la cadena en
# la primera clave foránea. Al agregar una migración, añádela aquí.
ANTES_DEL_DEMO=(
  V1__seguridad.sql
  V2__catalogo.sql
  V3__parametrizacion.sql
  V4__comercial.sql
  V5__funciones.sql
  V6__vistas.sql
  V7__datos_iniciales.sql
)
DESPUES_DEL_DEMO=(
  V9__rol_super_admin_y_venta_sin_existencia.sql
  V10__plataforma_multiempresa.sql
  V11__claves_compuestas_por_empresa.sql
  V12__configuracion_tributaria_por_empresa.sql
  V13__logo_de_empresa.sql
  V14__miniatura_de_imagen.sql
)

for f in "${ANTES_DEL_DEMO[@]}"; do
  echo "→ $f"
  "${MYSQL[@]}" sgip < "$DIR/$f"
done

# V8 va en medio: carga datos de demostración que V9 y V10 después
# migran, de modo que aplicarlo al final dejaría esas filas sin empresa.
if [[ $DEMO -eq 1 ]]; then
  echo "→ V8__datos_demo.sql"
  "${MYSQL[@]}" sgip < "$DIR/V8__datos_demo.sql"
fi

for f in "${DESPUES_DEL_DEMO[@]}"; do
  echo "→ $f"
  "${MYSQL[@]}" sgip < "$DIR/$f"
done

tablas=$("${MYSQL[@]}" -N -B -e \
  "SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema='sgip' AND table_type='BASE TABLE';")
vistas=$("${MYSQL[@]}" -N -B -e \
  "SELECT COUNT(*) FROM information_schema.views WHERE table_schema='sgip';")
funciones=$("${MYSQL[@]}" -N -B -e \
  "SELECT COUNT(*) FROM information_schema.routines
   WHERE routine_schema='sgip' AND routine_type='FUNCTION';")

echo "listo: $tablas tablas, $vistas vistas, $funciones funciones"

fallo=0
[[ "$tablas"    -eq 39 ]] || { echo "ATENCIÓN: se esperaban 39 tablas";   fallo=1; }
[[ "$vistas"    -eq  6 ]] || { echo "ATENCIÓN: se esperaban 6 vistas";    fallo=1; }
[[ "$funciones" -eq  4 ]] || { echo "ATENCIÓN: se esperaban 4 funciones"; fallo=1; }

# Centinela de la V9: si esta columna no existe, la migración se detuvo a
# medias y el esquema quedó incompleto aunque el conteo de tablas cuadre.
sin_existencia=$("${MYSQL[@]}" -N -B -e \
  "SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema='sgip' AND table_name='ventas'
     AND column_name='sin_existencia';")
[[ "$sin_existencia" -eq 1 ]] || {
  echo "ATENCIÓN: falta ventas.sin_existencia — la V9 no se aplicó completa"
  fallo=1
}

# Centinela de las claves compuestas por empresa: veinte de la V11 y
# cuatro de la V12 (reglas_iva hacia categorías y hacia productos,
# producto_imagenes y venta_retenciones).
compuestas=$("${MYSQL[@]}" -N -B -e \
  "SELECT COUNT(*) FROM information_schema.referential_constraints
   WHERE constraint_schema='sgip' AND constraint_name LIKE '%\\_emp';")
[[ "$compuestas" -eq 24 ]] || {
  echo "ATENCIÓN: se esperaban 24 claves por empresa y hay $compuestas"
  fallo=1
}

exit $fallo

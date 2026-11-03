#!/usr/bin/env bash
# Comprueba contra una base real lo que la puesta a punto cambió en el
# esquema y que ninguna prueba de unidad puede ver: cada restricción nueva
# tiene que rechazar de verdad lo que dice rechazar.
#
# Se ejecuta sobre una base con la cadena V1 a V31 aplicada, las
# migraciones nuevas (V32 en adelante) aplicadas con el cliente `mysql` y
# los datos de demostración cargados.
#
#   bash verificar_puesta_a_punto.sh -u root -p
#   bash verificar_puesta_a_punto.sh -h 127.0.0.1 -u root -psecreto
#   bash verificar_puesta_a_punto.sh --socket=/tmp/my.sock
#
# Deja la base como la encontró: todo lo que crea lleva el prefijo PAP- y
# se borra al terminar, también cuando una comprobación falla. Cada tarea
# de la puesta a punto añade su sección y, si crea filas, su línea en
# `limpiar`. Úsalo en una base de desarrollo.
set -uo pipefail

CONEXION=("$@")

sin_aviso () { grep -v "Using a password on the command line interface" || true; }
ejecutar () { mysql "${CONEXION[@]}" sgip -e "$1" 2>&1 | sin_aviso; }
consulta () { mysql "${CONEXION[@]}" -N -B sgip -e "$1" 2>/dev/null; }
solo_error () { grep -m1 "^ERROR" <<<"$1" || echo "${1:-(sin salida)}"; }

fallos=0
primera_falla=""
ok ()    { echo "  ok    · $1"; }
falla () {
  echo "  FALLA · $1"
  fallos=$((fallos + 1))
  [[ -z "$primera_falla" ]] && primera_falla="$1"
}

# Cada `mysql -e` es una sesión propia: no hay una transacción que abarque
# el guion y deshacer con un ROLLBACK no es posible. La limpieza es un
# borrado por prefijo, de hija a madre, y corre al empezar (por si una
# ejecución anterior murió a medias) y al salir por cualquier camino.
limpiar () {
  local salida
  salida=$(
    {
      ejecutar "DELETE FROM productos   WHERE codigo LIKE 'PAP-%';"
      ejecutar "DELETE FROM proveedores WHERE nit    LIKE 'PAP-%';"
      ejecutar "DELETE FROM categorias  WHERE nombre LIKE 'PAP-%';"
      ejecutar "DELETE FROM empresas    WHERE nit    LIKE 'PAP-%';"
    }
  )
  # Un borrado que se negó no puede pasar en silencio: dejaría filas que
  # la próxima ejecución leería como suyas.
  [[ -n "$salida" ]] && echo "  AVISO · la limpieza dejó filas PAP-: $(solo_error "$salida")"
  return 0
}
trap limpiar EXIT
trap 'exit 130' INT TERM
limpiar

# Una sentencia que DEBE ser rechazada por una restricción concreta. Se
# comprueban el código del motor y el nombre de la restricción: si la fila
# se rechaza por otra razón, la prueba no prueba lo que dice probar.
# $1 descripción · $2 código del motor · $3 restricción · $4 SQL
rechaza () {
  local salida; salida=$(ejecutar "$4")
  if [[ -z "$salida" ]]; then
    falla "$1 — la fila entró"
  elif [[ "$salida" != *"ERROR $2"* || "$salida" != *"$3"* ]]; then
    falla "$1 — rechazada, pero no por $3 (ERROR $2): $(solo_error "$salida")"
  else
    ok "$1"
  fi
}

# $1 descripción · $2 SQL
acepta () {
  local salida; salida=$(ejecutar "$2")
  [[ -z "$salida" ]] && ok "$1" || falla "$1 — $(solo_error "$salida")"
}

CAT1=$(consulta "SELECT categoria_id FROM categorias WHERE empresa_id = 1 LIMIT 1;")
PRV1=$(consulta "SELECT proveedor_id FROM proveedores WHERE empresa_id = 1 LIMIT 1;")
if [[ -z "$CAT1" || -z "$PRV1" ]]; then
  echo "No hay categoría y proveedor de la empresa 1. ¿Están los datos de demostración?"
  exit 1
fi

# La empresa ajena se construye aquí y no se hereda de los datos de
# demostración, que pueden tener una sola.
ejecutar "INSERT INTO empresas (nit, razon_social, estado)
          VALUES ('PAP-900-1','Comercio de prueba de la puesta a punto','ACTIVA');" >/dev/null
AJENA=$(consulta "SELECT empresa_id FROM empresas WHERE nit = 'PAP-900-1';")
if [[ -z "$AJENA" ]]; then
  echo "No se pudo crear la empresa de prueba. ¿Está aplicada la V10?"
  exit 1
fi
ejecutar "INSERT INTO categorias (empresa_id, nombre, activa)
          VALUES ($AJENA, 'PAP- categoría', TRUE);" >/dev/null
ejecutar "INSERT INTO proveedores (empresa_id, razon_social, nit, plazo_dias,
                                   origen_lista, activo)
          VALUES ($AJENA, 'PAP- proveedor', 'PAP-800-1', 30, 'NINGUNO', TRUE);" >/dev/null
CAT2=$(consulta "SELECT categoria_id FROM categorias  WHERE empresa_id = $AJENA LIMIT 1;")
PRV2=$(consulta "SELECT proveedor_id FROM proveedores WHERE empresa_id = $AJENA LIMIT 1;")
if [[ -z "$CAT2" || -z "$PRV2" ]]; then
  echo "No se pudo crear la categoría y el proveedor de la empresa de prueba."
  exit 1
fi

# El SQL de un producto de prueba. Código y SKU son iguales y propios de
# cada fila, de modo que ninguna de las claves únicas del código y del SKU
# puede ser la que rechace: lo único que se repite es lo que se prueba.
# $1 empresa · $2 categoría · $3 proveedor · $4 código · $5 código de barras
# (NULL o 'texto' entre comillas)
producto () {
  echo "INSERT INTO productos (empresa_id, categoria_id, proveedor_id, codigo, sku,
                               nombre, presentacion, costo, existencia,
                               existencia_minima, activo, codigo_barras)
        VALUES ($1, $2, $3, '$4', '$4', 'Prueba PAP $4', 'unidad', 1000, 0, 0, TRUE, $5);"
}

# ── Un código de barras por empresa (V33) ──────────────────────────────
# Dos productos de la misma empresa con el mismo código de barras hacían
# que el lector del POS devolviera el que el motor encontrara primero. La
# V33 lo convierte en una clave única por empresa.
echo "── un código de barras por empresa (V33) ──"

salida=$(ejecutar "$(producto 1 "$CAT1" "$PRV1" PAP-B1 "'PAP-BARRAS-1'")")
if [[ -n "$salida" ]]; then
  falla "el primer producto con un código de barras no se pudo crear — $(solo_error "$salida")"
else
  ok "un producto con código de barras"
fi

rechaza "el mismo código de barras en la misma empresa" 1062 uq_productos_barras \
  "$(producto 1 "$CAT1" "$PRV1" PAP-B2 "'PAP-BARRAS-1'")"

acepta "el mismo código de barras en otra empresa" \
  "$(producto "$AJENA" "$CAT2" "$PRV2" PAP-B3 "'PAP-BARRAS-1'")"

acepta "un producto sin código de barras" \
  "$(producto 1 "$CAT1" "$PRV1" PAP-N1 NULL)"
acepta "y otro más sin código de barras en la misma empresa" \
  "$(producto 1 "$CAT1" "$PRV1" PAP-N2 NULL)"

# La clave única sirve también a la búsqueda del POS por empresa y código
# de barras, así que el índice simple de la V10 sobra y sobraría dos veces.
clave=$(consulta "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index)
                    FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'productos'
                     AND index_name = 'uq_productos_barras' AND non_unique = 0;")
[[ "$clave" == "empresa_id,codigo_barras" ]] \
  && ok "la clave única cubre (empresa_id, codigo_barras)" \
  || falla "la clave uq_productos_barras no es única sobre (empresa_id, codigo_barras): '${clave:-(no existe)}'"

viejo=$(consulta "SELECT COUNT(*) FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'productos'
                     AND index_name = 'ix_productos_barras';")
[[ "${viejo:-1}" == "0" ]] \
  && ok "el índice simple ix_productos_barras ya no existe" \
  || falla "el índice simple ix_productos_barras sigue ahí"

echo
if [[ "$fallos" -eq 0 ]]; then
  echo "TODO EN VERDE"
  exit 0
fi
echo "::error title=Puesta a punto del esquema::$fallos comprobación(es) fallaron · la primera: $primera_falla"
echo "$fallos comprobación(es) fallaron"
exit 1

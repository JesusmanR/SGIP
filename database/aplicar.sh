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
#
# El `return 0` no sobra. Sin clave no hay archivo que borrar, la
# condición da falso y la función devuelve 1; como corre en el trap de
# EXIT, ese 1 pisaba el código de salida del guion y `aplicar.sh`
# terminaba en error habiendo aplicado la cadena entera y pasado todos
# sus centinelas. Se descubrió al ejecutarlo de verdad por primera vez:
# la verificación local usaba un guion de cadena cruda y nunca pasaba
# por aquí.
limpiar() {
  [[ -n "$CNF" && -f "$CNF" ]] && rm -f "$CNF"
  return 0
}
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
  V15__vista_de_precios_acotada_por_empresa.sql
  V16__retirar_parametros_de_imagen.sql
  V17__vista_de_precios_expone_los_identificadores.sql
  V18__movimientos_admiten_existencia_negativa.sql
  V19__la_valorizacion_no_resta_lo_que_se_debe.sql
  V20__la_vista_de_precios_cierra_la_modalidad_a_credito.sql
  V21__auditoria_de_la_plataforma.sql
  V22__parametros_de_la_plataforma.sql
  V23__el_kardex_se_explica_solo.sql
  V24__claves_por_empresa_en_compras.sql
  V25__el_precio_respeta_la_forma_de_calculo.sql
  V26__el_tipo_de_un_parametro_obliga.sql
  V27__el_periodo_de_prueba_se_mide_en_meses.sql
  V28__claves_por_empresa_en_importacion.sql
  V29__el_rechazo_dice_en_que_fila.sql
  V30__el_rechazo_tiene_categoria.sql
  V31__claves_por_empresa_en_lo_comercial.sql
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
[[ "$tablas"    -eq 41 ]] || { echo "ATENCIÓN: se esperaban 41 tablas";   fallo=1; }
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

# Centinela de las claves compuestas por empresa: veinte de la V11,
# cuatro de la V12 (reglas_iva hacia categorías y hacia productos,
# producto_imagenes y venta_retenciones), cinco de la V24 (las dos de
# orden_compra_items, las dos de recepciones y la de movimientos hacia
# recepciones), siete de la V28 y diez de la V31 (dos por cada una de
# venta_items, cotizacion_items, producto_proveedor y
# descuadres_existencia, más facturas_electronicas y auditoria_accesos).
compuestas=$("${MYSQL[@]}" -N -B -e \
  "SELECT COUNT(*) FROM information_schema.referential_constraints
   WHERE constraint_schema='sgip' AND constraint_name LIKE '%\\_emp';")
[[ "$compuestas" -eq 46 ]] || {
  echo "ATENCIÓN: se esperaban 46 claves por empresa y hay $compuestas"
  fallo=1
}

# El centinela de arriba cuenta, y un conteo es un número que alguien
# tiene que acordarse de subir. Cuatro migraciones —V11, V24, V28 y
# V31— han corregido el mismo defecto, y el conteo no encontró ninguna
# de las tres últimas: las encontró ir a construir encima.
#
# Esto mide la propiedad en lugar del número: toda clave foránea que
# apunta a una tabla con `empresa_id` debe incluir `empresa_id`. Una
# clave nueva que se olvide de la empresa aparece aquí el día que se
# escribe, sin que nadie tenga que contar nada. Los dos centinelas no
# se solapan: este no ve una clave que alguien borre del todo, y el
# conteo no ve una clave nueva mal formada.
#
# Las dos excepciones van nombradas y no por tabla, para que una clave
# nueva hacia `usuarios` desde esas mismas tablas no herede la
# excepción. El porqué de cada una está en la V31.
descuidadas=$("${MYSQL[@]}" -N -B -e \
  "SELECT GROUP_CONCAT(x.table_name, '.', x.constraint_name SEPARATOR ', ')
     FROM (SELECT k.table_name, k.constraint_name,
                  GROUP_CONCAT(k.column_name ORDER BY k.ordinal_position) cols
             FROM information_schema.key_column_usage k
            WHERE k.table_schema='sgip'
              AND k.referenced_table_name IS NOT NULL
              AND k.referenced_table_name <> 'empresas'
              AND k.constraint_name NOT IN ('fk_sesiones_usuario',
                                            'fk_auditoria_plataforma_usuario')
              AND EXISTS (SELECT 1 FROM information_schema.columns c
                           WHERE c.table_schema='sgip'
                             AND c.table_name = k.referenced_table_name
                             AND c.column_name = 'empresa_id')
            GROUP BY k.constraint_schema, k.constraint_name, k.table_name) x
    WHERE FIND_IN_SET('empresa_id', x.cols) = 0;")
[[ -z "$descuidadas" || "$descuidadas" == "NULL" ]] || {
  echo "ATENCIÓN: hay claves foráneas hacia tablas por empresa que no"
  echo "          incluyen empresa_id: $descuidadas"
  fallo=1
}

# Centinela de la V18: las dos restricciones del kárdex deben llevar el
# mismo piso que la V9 puso en productos. Si quedaran en cero, el esquema
# permitiría dejar la existencia en negativo y prohibiría anotar por qué.
piso=$("${MYSQL[@]}" -N -B -e \
  "SELECT COUNT(*) FROM information_schema.check_constraints
   WHERE constraint_schema='sgip'
     AND constraint_name IN ('ck_movimientos_anterior','ck_movimientos_posterior')
     AND check_clause LIKE '%9999%';")
[[ "$piso" -eq 2 ]] || {
  echo "ATENCIÓN: el kárdex no admite existencia negativa — falta la V18"
  fallo=1
}

# Centinela de la V19 y la V20: siete columnas nuevas repartidas en tres
# vistas. El conteo de vistas no cambia con esas migraciones, de modo que
# cuadraría igual si no se hubieran aplicado.
columnas=$("${MYSQL[@]}" -N -B -e \
  "SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema='sgip' AND (
     (table_name='v_precios_producto'
      AND column_name IN ('total_credito','cerca_umbral_credito','cruza_umbral_en_credito'))
     OR (table_name='v_inventario_valorizado'
      AND column_name IN ('unidades_pendientes','valor_pendiente_al_costo'))
     OR (table_name='v_rotacion_inventario'
      AND column_name IN ('unidades_pendientes','valor_pendiente_al_costo')));")
[[ "$columnas" -eq 7 ]] || {
  echo "ATENCIÓN: se esperaban 7 columnas de la V19 y la V20 y hay $columnas"
  fallo=1
}

# Centinela de la V23: la restriccion de coherencia del kardex y la
# ausencia de `observacion`, que esa migracion consolido en `motivo`. El
# conteo de tablas no cambia con la V23, de modo que cuadraria igual sin
# aplicarla.
coherencia=$("${MYSQL[@]}" -N -B -e \
  "SELECT COUNT(*) FROM information_schema.check_constraints
   WHERE constraint_schema='sgip'
     AND constraint_name='ck_movimientos_coherencia';")
[[ "$coherencia" -eq 1 ]] || {
  echo "ATENCIÓN: falta ck_movimientos_coherencia — la V23 no se aplicó"
  fallo=1
}

sobrante=$("${MYSQL[@]}" -N -B -e \
  "SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema='sgip' AND table_name='movimientos_inventario'
     AND column_name='observacion';")
[[ "$sobrante" -eq 0 ]] || {
  echo "ATENCIÓN: movimientos_inventario.observacion sigue ahí — la V23 quedó a medias"
  fallo=1
}

# Centinela de la V25: la restriccion del margen sobre el precio y la
# columna que dice que base aplico. Sin ellas, la forma de calculo se
# guarda y no se usa, que es lo que esa migracion corrigio.
precio=$("${MYSQL[@]}" -N -B -e \
  "SELECT (SELECT COUNT(*) FROM information_schema.check_constraints
            WHERE constraint_schema='sgip'
              AND constraint_name='ck_reglas_margen_sobre_precio')
        + (SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema='sgip' AND table_name='v_precios_producto'
              AND column_name='base_contado');")
[[ "$precio" -eq 2 ]] || {
  echo "ATENCIÓN: la vista de precios no respeta la forma de cálculo — falta la V25"
  fallo=1
}

exit $fallo

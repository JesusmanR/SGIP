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
      # Las reglas y la configuración cuelgan de la empresa de prueba y
      # van antes que ella; el tipo de impuesto de prueba, después de las
      # reglas que lo usan (la clave hacia el catálogo es RESTRICT).
      ejecutar "DELETE r FROM reglas_iva r JOIN empresas e ON e.empresa_id = r.empresa_id
                 WHERE e.nit LIKE 'PAP-%';"
      ejecutar "DELETE r FROM reglas_margen r JOIN empresas e ON e.empresa_id = r.empresa_id
                 WHERE e.nit LIKE 'PAP-%';"
      ejecutar "DELETE FROM tipos_impuesto WHERE codigo LIKE 'PAP-%';"
      ejecutar "DELETE FROM productos   WHERE codigo LIKE 'PAP-%';"
      ejecutar "DELETE FROM proveedores WHERE nit    LIKE 'PAP-%';"
      ejecutar "DELETE FROM categorias  WHERE nombre LIKE 'PAP-%';"
      ejecutar "DELETE c FROM empresa_config c JOIN empresas e ON e.empresa_id = c.empresa_id
                 WHERE e.nit LIKE 'PAP-%';"
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

# $1 descripción · $2 esperado · $3 obtenido
igual () {
  [[ "$2" == "$3" ]] && ok "$1" || falla "$1 — esperaba '$2' y obtuve '${3:-(nada)}'"
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
clave=$(consulta "SELECT COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index), '(no existe)')
                    FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'productos'
                     AND index_name = 'uq_productos_barras' AND non_unique = 0;")
[[ "$clave" == "empresa_id,codigo_barras" ]] \
  && ok "la clave única cubre (empresa_id, codigo_barras)" \
  || falla "la clave uq_productos_barras no es única sobre (empresa_id, codigo_barras): '$clave'"

viejo=$(consulta "SELECT COUNT(*) FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'productos'
                     AND index_name = 'ix_productos_barras';")
[[ "${viejo:-1}" == "0" ]] \
  && ok "el índice simple ix_productos_barras ya no existe" \
  || falla "el índice simple ix_productos_barras sigue ahí"


# ── Los impuestos son opcionales (V34) ─────────────────────────────────
# Muchos comercios pequeños no están obligados a cobrar impuestos, así que
# una empresa tiene que poder vender sin configurar ninguno. Hasta la V34,
# un producto sin regla de IVA salía de la vista sin tratamiento ni
# tarifa y con el total vacío: la RN-25 lo tomaba por «no se puede vender».
# Ahora, sin regla, el total es el precio. Solo quien se declara
# responsable de IVA y se olvida de una regla recibe el aviso.
#
# Cada caso lee todo lo que la venta mira del producto, y comprueba aparte
# que el precio exista: un total nulo comparado con un precio nulo daría
# «igual» sin haber calculado nada.
echo
echo "── los impuestos son opcionales (V34) ──"

ejecutar "INSERT INTO empresa_config (empresa_id) VALUES ($AJENA);" >/dev/null
igual "una empresa nueva nace sin ser responsable de IVA" "0"   "$(consulta "SELECT responsable_iva FROM empresa_config WHERE empresa_id = $AJENA;")"

ejecutar "INSERT INTO reglas_margen (empresa_id, categoria_id, proveedor_id, forma_pago,
                                     forma_calculo, factor, vigente_desde)
          VALUES ($AJENA, NULL, NULL, 'CONTADO', 'SOBRE_COSTO', 1.5000, '2026-01-01'),
                 ($AJENA, NULL, NULL, 'CREDITO', 'SOBRE_COSTO', 1.6000, '2026-01-01');" >/dev/null
acepta "un producto de la empresa de prueba, a 1 000 de costo"   "$(producto "$AJENA" "$CAT2" "$PRV2" PAP-T1 NULL)"

# $1 = columnas o expresiones sobre la vista
fila () { consulta "SELECT $1 FROM v_precios_producto WHERE codigo = 'PAP-T1';"; }

# Lo que la venta tiene que poder leer en cada caso.
# $1 descripción · $2 tratamiento · $3 aviso · $4 total de contado en pesos,
# o «precio» si ha de ser igual al precio · $5 total a crédito en pesos
cobra () {
  local d="$1" tratamiento="$2" aviso="$3" contado="$4" credito="$5"
  igual "$d · el precio de contado existe" "1" "$(fila "precio_contado IS NOT NULL")"
  igual "$d · tratamiento" "$tratamiento" "$(fila "tratamiento_iva")"
  igual "$d · aviso de tributación sin configurar" "$aviso" "$(fila "tributacion_sin_configurar")"
  if [[ "$contado" == "precio" ]]; then
    igual "$d · el total de contado es el precio" "1" "$(fila "total_contado = precio_contado")"
    igual "$d · el total a crédito es el precio" "1" "$(fila "total_credito = precio_credito")"
  else
    igual "$d · el total de contado" "1" "$(fila "total_contado = $contado")"
    igual "$d · el total a crédito" "1" "$(fila "total_credito = $credito")"
  fi
}

igual "el precio de contado es el costo por 1,5" "1" "$(fila "precio_contado = 1500")"

# 1. No responsable de IVA y sin ninguna regla: vende sin impuesto y sin aviso.
cobra "no responsable y sin reglas" NO_GRAVADO 0 precio precio
igual "no responsable y sin reglas · la tarifa es cero" "1" "$(fila "tarifa_contado = 0")"

# 2. Responsable y sin regla: vende al precio y avisa de que falta la regla.
ejecutar "UPDATE empresa_config SET responsable_iva = TRUE WHERE empresa_id = $AJENA;" >/dev/null
cobra "responsable y sin reglas" NULL 1 precio precio
igual "responsable y sin reglas · la tarifa es cero, no nula" "1" "$(fila "tarifa_contado = 0")"

# 3. Responsable con la regla general del IVA al 19 %.
ejecutar "INSERT INTO reglas_iva (empresa_id, categoria_id, producto_id, tipo_impuesto_id, vigente_desde)
          SELECT $AJENA, NULL, NULL, tipo_impuesto_id, '2026-01-01'
            FROM tipos_impuesto WHERE codigo = 'IVA_19';" >/dev/null
cobra "responsable con la regla general IVA_19" GRAVADO 0 1785 1904

# 4. La regla de un producto manda sobre la general.
PIDT=$(consulta "SELECT producto_id FROM productos WHERE codigo = 'PAP-T1';")
ejecutar "INSERT INTO reglas_iva (empresa_id, categoria_id, producto_id, tipo_impuesto_id, vigente_desde)
          SELECT $AJENA, NULL, $PIDT, tipo_impuesto_id, '2026-01-01'
            FROM tipos_impuesto WHERE codigo = 'EXENTO';" >/dev/null
cobra "la regla del producto manda sobre la general" EXENTO 0 precio precio

# 5. Un tipo inactivo no cuenta: la regla del producto que lo usa se ignora
#    y manda la general, en lugar de dejar al producto sin ninguna.
ejecutar "INSERT INTO tipos_impuesto (codigo, nombre, tratamiento, tarifa, vigente_desde, activo)
          VALUES ('PAP-INACTIVO', 'Tipo de prueba inactivo', 'EXCLUIDO', 0.0000, '2026-01-01', FALSE);" >/dev/null
ejecutar "UPDATE reglas_iva SET tipo_impuesto_id =
            (SELECT tipo_impuesto_id FROM tipos_impuesto WHERE codigo = 'PAP-INACTIVO')
          WHERE empresa_id = $AJENA AND producto_id = $PIDT;" >/dev/null
cobra "un tipo inactivo no tapa la regla general" GRAVADO 0 1785 1904

# 6. Una regla vencida es como no tener regla.
ejecutar "UPDATE reglas_iva SET vigente_hasta = CURDATE() - INTERVAL 1 DAY
          WHERE empresa_id = $AJENA;" >/dev/null
cobra "con todas las reglas vencidas" NULL 1 precio precio

# 7. La regla de una categoría manda sobre la general.
ejecutar "DELETE FROM reglas_iva WHERE empresa_id = $AJENA;" >/dev/null
ejecutar "INSERT INTO reglas_iva (empresa_id, categoria_id, producto_id, tipo_impuesto_id, vigente_desde)
          SELECT $AJENA, NULL, NULL, tipo_impuesto_id, '2026-01-01'
            FROM tipos_impuesto WHERE codigo = 'IVA_19';" >/dev/null
ejecutar "INSERT INTO reglas_iva (empresa_id, categoria_id, producto_id, tipo_impuesto_id, vigente_desde)
          SELECT $AJENA, $CAT2, NULL, tipo_impuesto_id, '2026-01-01'
            FROM tipos_impuesto WHERE codigo = 'EXENTO';" >/dev/null
cobra "la regla de la categoría manda sobre la general" EXENTO 0 precio precio

# 8. Una regla que empieza mañana todavía no se aplica.
ejecutar "DELETE FROM reglas_iva WHERE empresa_id = $AJENA;" >/dev/null
ejecutar "INSERT INTO reglas_iva (empresa_id, categoria_id, producto_id, tipo_impuesto_id, vigente_desde)
          SELECT $AJENA, NULL, NULL, tipo_impuesto_id, CURDATE() + INTERVAL 1 DAY
            FROM tipos_impuesto WHERE codigo = 'IVA_19';" >/dev/null
cobra "con una regla que empieza mañana" NULL 1 precio precio

# 9. Quien deja de ser responsable de IVA teniendo reglas no cobra nada y no
#    recibe aviso: la regla sigue ahí, pero no se aplica.
ejecutar "DELETE FROM reglas_iva WHERE empresa_id = $AJENA;" >/dev/null
ejecutar "INSERT INTO reglas_iva (empresa_id, categoria_id, producto_id, tipo_impuesto_id, vigente_desde)
          SELECT $AJENA, NULL, NULL, tipo_impuesto_id, '2026-01-01'
            FROM tipos_impuesto WHERE codigo = 'IVA_19';" >/dev/null
cobra "responsable con la regla general IVA_19 (otra vez)" GRAVADO 0 1785 1904
ejecutar "UPDATE empresa_config SET responsable_iva = FALSE WHERE empresa_id = $AJENA;" >/dev/null
cobra "deja de ser responsable teniendo reglas" NO_GRAVADO 0 precio precio
igual "deja de ser responsable teniendo reglas · la tarifa es cero" "1" "$(fila "tarifa_contado = 0")"

# 10. Una empresa sin fila en empresa_config tampoco deja a sus productos sin
#     fila ni con el total vacío: se trata como no responsable.
ejecutar "INSERT INTO empresas (nit, razon_social, estado)
          VALUES ('PAP-900-2','Comercio de prueba sin configuración','ACTIVA');" >/dev/null
SINCFG=$(consulta "SELECT empresa_id FROM empresas WHERE nit = 'PAP-900-2';")
ejecutar "INSERT INTO categorias (empresa_id, nombre, activa) VALUES ($SINCFG, 'PAP- sin configuración', TRUE);" >/dev/null
ejecutar "INSERT INTO proveedores (empresa_id, razon_social, nit, plazo_dias, origen_lista, activo)
          VALUES ($SINCFG, 'PAP- proveedor 2', 'PAP-800-2', 30, 'NINGUNO', TRUE);" >/dev/null
CAT3=$(consulta "SELECT categoria_id FROM categorias  WHERE empresa_id = $SINCFG LIMIT 1;")
PRV3=$(consulta "SELECT proveedor_id FROM proveedores WHERE empresa_id = $SINCFG LIMIT 1;")
ejecutar "INSERT INTO reglas_margen (empresa_id, categoria_id, proveedor_id, forma_pago,
                                     forma_calculo, factor, vigente_desde)
          VALUES ($SINCFG, NULL, NULL, 'CONTADO', 'SOBRE_COSTO', 1.5000, '2026-01-01'),
                 ($SINCFG, NULL, NULL, 'CREDITO', 'SOBRE_COSTO', 1.6000, '2026-01-01');" >/dev/null
acepta "un producto en una empresa sin fila de configuración" \
  "$(producto "$SINCFG" "$CAT3" "$PRV3" PAP-S1 NULL)"
sin_fila () { consulta "SELECT $1 FROM v_precios_producto WHERE codigo = 'PAP-S1';"; }
igual "sin fila de configuración · el producto sale en la vista, una sola vez" "1" \
  "$(consulta "SELECT COUNT(*) FROM v_precios_producto WHERE codigo = 'PAP-S1';")"
igual "sin fila de configuración · tratamiento" "NO_GRAVADO" "$(sin_fila "tratamiento_iva")"
igual "sin fila de configuración · aviso" "0" "$(sin_fila "tributacion_sin_configurar")"
igual "sin fila de configuración · el total es el precio" "1" \
  "$(sin_fila "precio_contado IS NOT NULL AND total_contado = precio_contado")"

# ── Las líneas de venta y de cotización guardan NO_GRAVADO ──────────────
# La vista da NO_GRAVADO a quien no es responsable de IVA, y el punto de
# venta copia ese tratamiento a la línea. Hasta la V34 la columna solo
# admitía GRAVADO, EXCLUIDO y EXENTO, y esa venta habría fallado al
# guardarse. Se prueba con filas de verdad dentro de una transacción que se
# deshace, y se mira además el tipo y el valor por omisión.
echo
echo "── las líneas de venta y de cotización admiten NO_GRAVADO (V34) ──"
for tabla in venta_items cotizacion_items; do
  tipo=$(consulta "SELECT column_type FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = '$tabla'
                      AND column_name = 'tratamiento_iva';")
  [[ "$tipo" == *"'NO_GRAVADO'"* ]] \
    && ok "$tabla.tratamiento_iva admite NO_GRAVADO" \
    || falla "$tabla.tratamiento_iva no admite NO_GRAVADO: $tipo"
  igual "$tabla.tratamiento_iva sigue sin admitir nulos y con GRAVADO por omisión" \
    "NO|GRAVADO" \
    "$(consulta "SELECT CONCAT(is_nullable, '|', column_default) FROM information_schema.columns
                  WHERE table_schema = DATABASE() AND table_name = '$tabla'
                    AND column_name = 'tratamiento_iva';")"
done

PIDB=$(consulta "SELECT producto_id FROM productos WHERE codigo = 'PAP-N1';")
acepta "una línea de venta con NO_GRAVADO se guarda" \
  "START TRANSACTION;
   INSERT INTO ventas (empresa_id, consecutivo, cliente_id, usuario_id, forma_pago)
   SELECT 1, 'PAP-V1', (SELECT cliente_id FROM clientes WHERE empresa_id = 1 LIMIT 1),
          (SELECT usuario_id FROM usuarios WHERE empresa_id = 1 LIMIT 1), 'CONTADO';
   INSERT INTO venta_items (venta_id, empresa_id, producto_id, cantidad, precio_unitario,
                            costo_unitario, tarifa_iva, tratamiento_iva)
   VALUES (LAST_INSERT_ID(), 1, $PIDB, 1, 1500, 1000, 0, 'NO_GRAVADO');
   ROLLBACK;"
acepta "una línea de cotización con NO_GRAVADO se guarda" \
  "START TRANSACTION;
   INSERT INTO cotizaciones (empresa_id, consecutivo, cliente_id, usuario_id)
   SELECT 1, 'PAP-C1', (SELECT cliente_id FROM clientes WHERE empresa_id = 1 LIMIT 1),
          (SELECT usuario_id FROM usuarios WHERE empresa_id = 1 LIMIT 1);
   INSERT INTO cotizacion_items (cotizacion_id, empresa_id, producto_id, cantidad,
                                 precio_unitario, tarifa_iva, tratamiento_iva)
   VALUES (LAST_INSERT_ID(), 1, $PIDB, 1, 1500, 0, 'NO_GRAVADO');
   ROLLBACK;"
rechaza "y un tratamiento inventado sigue rechazado" 1265 tratamiento_iva \
  "START TRANSACTION;
   INSERT INTO ventas (empresa_id, consecutivo, cliente_id, usuario_id, forma_pago)
   SELECT 1, 'PAP-V2', (SELECT cliente_id FROM clientes WHERE empresa_id = 1 LIMIT 1),
          (SELECT usuario_id FROM usuarios WHERE empresa_id = 1 LIMIT 1), 'CONTADO';
   INSERT INTO venta_items (venta_id, empresa_id, producto_id, cantidad, precio_unitario,
                            costo_unitario, tarifa_iva, tratamiento_iva)
   VALUES (LAST_INSERT_ID(), 1, $PIDB, 1, 1500, 1000, 0, 'INVENTADO');
   ROLLBACK;"

# La vista devuelve una fila por producto activo, también donde no hay regla,
# y ninguna de más: un LEFT JOIN mal puesto duplicaría los productos.
for e in 1 "$AJENA"; do
  igual "la empresa $e: una fila por producto activo" "1"     "$(consulta "SELECT (SELECT COUNT(*) FROM v_precios_producto WHERE empresa_id = $e)
                      = (SELECT COUNT(*) FROM productos WHERE empresa_id = $e AND activo = 1);")"
done
echo
if [[ "$fallos" -eq 0 ]]; then
  echo "TODO EN VERDE"
  exit 0
fi
echo "::error title=Puesta a punto del esquema::$fallos comprobación(es) fallaron · la primera: $primera_falla"
echo "$fallos comprobación(es) fallaron"
exit 1

#!/usr/bin/env bash
# Comprueba que las tablas de importación de listas de precios no pueden
# cruzar empresas (V28, RN-16).
#
#   bash verificar_importacion.sh -u root -p
#   bash verificar_importacion.sh --socket=/tmp/my.sock
#
# Se ejecuta sobre una base con la cadena V1 a V28 aplicada y los datos
# de demostración cargados.
#
# Crea su propia empresa de prueba con su proveedor, su usuario y su
# producto, y los retira al terminar: el NIT y los códigos llevan el
# prefijo IMP- para poder encontrarlos. No depende de que otro guion
# haya dejado una segunda empresa cargada, que es como se descubrió
# —probando a mano— que una comprobación puede pasar porque la fila
# que debía rechazarse ni siquiera llegó a formarse.
set -uo pipefail

CONEXION=("$@")

sin_aviso () { grep -v "Using a password on the command line interface" || true; }
ejecutar () { mysql "${CONEXION[@]}" sgip -e "$1" 2>&1 | sin_aviso; }
consulta () { mysql "${CONEXION[@]}" -N -B sgip -e "$1" 2>/dev/null; }

fallos=0
ok ()    { echo "  ok    · $1"; }
falla () { echo "  FALLA · $1"; fallos=$((fallos + 1)); }

# Una sentencia que el esquema TIENE que rechazar.
#
# Comprueba además que el rechazo sea de integridad y no de sintaxis: un
# identificador que viene vacío produce un ERROR 1064 y, si solo se
# mirara «¿falló?», la comprobación pasaría sin haber probado nada.
rechaza () { # $1 descripción · $2 sentencia
  local salida
  salida=$(ejecutar "$2")
  if [[ -z "$salida" ]]; then
    falla "$1 — la fila ENTRÓ"
  elif grep -q "ERROR 1064" <<<"$salida"; then
    falla "$1 — error de sintaxis, no de restricción: la prueba no probó nada"
  elif grep -qE "ERROR (1452|1216|1062|3819)" <<<"$salida"; then
    ok "$1"
  else
    falla "$1 — rechazada por otro motivo: $(grep -m1 -o 'ERROR .*' <<<"$salida" | cut -c1-90)"
  fi
}

acepta () { # $1 descripción · $2 sentencia
  local salida
  salida=$(ejecutar "$2")
  [[ -z "$salida" ]] && ok "$1" || falla "$1 — $(grep -m1 -o 'ERROR .*' <<<"$salida" | cut -c1-90)"
}

# El orden importa y no es el obvio.
#
# Las filas que este guion crea NO son todas de la empresa de prueba:
# varias son de la empresa 1, porque lo que se comprueba es precisamente
# que una empresa real no pueda alcanzar el catálogo de la ajena. Borrar
# «lo de la empresa de prueba» deja esas atrás, y entonces la clave
# `fk_historico_carga_emp`, que es RESTRICT, impide borrar la carga: el
# guion se queda con dos cargas del mismo nombre y la siguiente ejecución
# lee dos identificadores donde espera uno.
#
# De modo que se borra por lo que las identifica —el prefijo IMP- y la
# carga de la que cuelgan—, de hija a madre, y no por empresa.
limpiar () {
  local cargas="SELECT carga_id FROM cargas_precios WHERE nombre_archivo LIKE 'IMP-%'"
  ejecutar "DELETE FROM historico_costos      WHERE carga_id IN ($cargas);" >/dev/null
  ejecutar "DELETE FROM historico_costos      WHERE empresa_id IN (SELECT empresa_id FROM empresas WHERE nit LIKE 'IMP-%');" >/dev/null
  ejecutar "DELETE FROM detalle_carga_precios WHERE carga_id IN ($cargas);" >/dev/null
  ejecutar "DELETE FROM detalle_carga_precios WHERE referencia_origen LIKE 'IMP-%';" >/dev/null
  ejecutar "DELETE FROM cargas_precios        WHERE nombre_archivo LIKE 'IMP-%';" >/dev/null
  ejecutar "DELETE FROM historico_costos      WHERE producto_id IN (SELECT producto_id FROM productos WHERE codigo LIKE 'IMP-%');" >/dev/null
  ejecutar "DELETE FROM detalle_carga_precios WHERE producto_id IN (SELECT producto_id FROM productos WHERE codigo LIKE 'IMP-%');" >/dev/null
  ejecutar "DELETE FROM productos             WHERE codigo LIKE 'IMP-%';" >/dev/null
  ejecutar "DELETE FROM usuarios              WHERE correo LIKE 'imp-%';" >/dev/null
  ejecutar "DELETE FROM proveedores           WHERE nit LIKE 'IMP-%';" >/dev/null
  ejecutar "DELETE FROM categorias            WHERE nombre LIKE 'IMP-%';" >/dev/null
  ejecutar "DELETE FROM empresa_config        WHERE empresa_id IN (SELECT empresa_id FROM empresas WHERE nit LIKE 'IMP-%');" >/dev/null
  ejecutar "DELETE FROM empresas              WHERE nit LIKE 'IMP-%';" >/dev/null
}
trap limpiar EXIT
limpiar


# ── la empresa ajena, construida aquí y no heredada ──────────────────
ejecutar "INSERT INTO empresas (nit, razon_social, estado)
          VALUES ('IMP-900-1','Comercio ajeno de prueba','ACTIVA');" >/dev/null
AJENA=$(consulta "SELECT empresa_id FROM empresas WHERE nit='IMP-900-1';")
if [[ -z "$AJENA" ]]; then
  echo "No se pudo crear la empresa de prueba. ¿Está aplicada la V10?"
  exit 1
fi

ejecutar "INSERT INTO categorias (empresa_id, nombre, activa)
          VALUES ($AJENA,'IMP- categoría ajena',TRUE);" >/dev/null
ejecutar "INSERT INTO proveedores (empresa_id, razon_social, nit, plazo_dias,
                                   origen_lista, activo)
          VALUES ($AJENA,'IMP- proveedor ajeno','IMP-800-2',30,'ARCHIVO',TRUE);" >/dev/null
# La empresa ajena necesita usuario propio: una carga la firma alguien, y
# la V28 exige que ese alguien sea de la misma empresa que el proveedor.
ejecutar "INSERT INTO usuarios (empresa_id, rol_id, nombre, correo, clave_hash)
          SELECT $AJENA, (SELECT rol_id FROM roles WHERE nombre='ADMINISTRADOR'),
                 'Usuario de prueba de importación', 'imp-ajeno@prueba.local',
                 'sin-clave-no-se-usa-para-entrar';" >/dev/null

ejecutar "INSERT INTO productos (empresa_id, categoria_id, proveedor_id, codigo, sku,
                                 nombre, presentacion, costo, existencia,
                                 existencia_minima, activo)
          SELECT $AJENA,
                 (SELECT categoria_id FROM categorias  WHERE empresa_id=$AJENA LIMIT 1),
                 (SELECT proveedor_id FROM proveedores WHERE empresa_id=$AJENA LIMIT 1),
                 'IMP-AJENO','IMP-AJENO','Producto de la empresa ajena','unidad',
                 2000.00, 0, 0, TRUE;" >/dev/null

PRV_PROPIO=$(consulta "SELECT proveedor_id FROM proveedores WHERE empresa_id=1 LIMIT 1;")
USR_PROPIO=$(consulta "SELECT usuario_id   FROM usuarios    WHERE empresa_id=1 LIMIT 1;")
PRD_PROPIO=$(consulta "SELECT producto_id  FROM productos   WHERE empresa_id=1 LIMIT 1;")
PRV_AJENO=$(consulta  "SELECT proveedor_id FROM proveedores WHERE empresa_id=$AJENA LIMIT 1;")
PRD_AJENO=$(consulta  "SELECT producto_id  FROM productos   WHERE empresa_id=$AJENA LIMIT 1;")

for v in PRV_PROPIO USR_PROPIO PRD_PROPIO PRV_AJENO PRD_AJENO; do
  if [[ -z "${!v}" ]]; then
    echo "Falta $v: los datos de demostración no están completos. Nada que probar."
    exit 1
  fi
done
echo "  empresa ajena: $AJENA · su proveedor: $PRV_AJENO · su producto: $PRD_AJENO"


echo
echo "── la carga declara una empresa y tiene que ser coherente (V28) ──"
rechaza "una carga de la empresa 1 con un proveedor de la ajena" \
  "INSERT INTO cargas_precios (empresa_id, proveedor_id, usuario_id, origen, nombre_archivo)
   VALUES (1, $PRV_AJENO, $USR_PROPIO, 'ARCHIVO', 'IMP-cruzada.xlsx');"

rechaza "una carga de la empresa ajena firmada por un usuario de la 1" \
  "INSERT INTO cargas_precios (empresa_id, proveedor_id, usuario_id, origen, nombre_archivo)
   VALUES ($AJENA, $PRV_AJENO, $USR_PROPIO, 'ARCHIVO', 'IMP-firmada.xlsx');"

acepta "una carga con proveedor y usuario de la misma empresa" \
  "INSERT INTO cargas_precios (empresa_id, proveedor_id, usuario_id, origen, nombre_archivo)
   VALUES (1, $PRV_PROPIO, $USR_PROPIO, 'ARCHIVO', 'IMP-propia.xlsx');"
CARGA=$(consulta "SELECT carga_id FROM cargas_precios WHERE nombre_archivo='IMP-propia.xlsx';")
# Un identificador vacío o doble convierte todo lo que sigue en errores
# de sintaxis, y ocho líneas de ERROR 1064 esconden el fallo que de
# verdad importa. Se para aquí y se dice por qué.
if [[ "$(wc -l <<<"$CARGA")" -ne 1 || -z "$CARGA" ]]; then
  echo "  FALLA · la carga de prueba no quedó única: '${CARGA//$'\n'/, }'"
  echo "          la limpieza anterior dejó filas; nada más se puede probar."
  exit 1
fi


echo
echo "── el detalle no puede tocar el catálogo de otro comercio ──"
# Es la que muerde: de aquí sale el UPDATE del costo, y del costo cuelga
# toda la vista de precios.
rechaza "un detalle de la empresa 1 apuntando al producto de la ajena" \
  "INSERT INTO detalle_carga_precios (empresa_id, carga_id, producto_id,
                                      referencia_origen, costo, aceptado)
   VALUES (1, $CARGA, $PRD_AJENO, 'IMP-AJENO', 999999.00, 1);"

rechaza "un detalle que se declara de otra empresa que su carga" \
  "INSERT INTO detalle_carga_precios (empresa_id, carga_id, producto_id,
                                      referencia_origen, costo, aceptado)
   VALUES ($AJENA, $CARGA, $PRD_AJENO, 'IMP-AJENO', 999999.00, 1);"

acepta "un detalle sobre un producto propio" \
  "INSERT INTO detalle_carga_precios (empresa_id, carga_id, producto_id,
                                      referencia_origen, costo, aceptado)
   VALUES (1, $CARGA, $PRD_PROPIO, 'IMP-PROPIO', 1500.00, 1);"

# Un rechazo por referencia inexistente no tiene producto al que apuntar,
# y tiene que poder quedar anotado igualmente con su motivo (RF-26).
acepta "un rechazo sin producto, con su motivo y su categoría" \
  "INSERT INTO detalle_carga_precios (empresa_id, carga_id, producto_id,
                                      referencia_origen, costo, aceptado,
                                      motivo_rechazo, motivo_codigo)
   VALUES (1, $CARGA, NULL, 'IMP-NO-EXISTE', NULL, 0, 'referencia inexistente',
           'REFERENCIA_INEXISTENTE');"

# La categoría y la frase van juntas o no van (V30). Una pastilla sin
# texto no explica, y un texto sin pastilla no se puede pintar.
rechaza "un rechazo con motivo pero sin categoría" \
  "INSERT INTO detalle_carga_precios (empresa_id, carga_id, producto_id,
                                      referencia_origen, costo, aceptado, motivo_rechazo)
   VALUES (1, $CARGA, NULL, 'IMP-SIN-CATEGORIA', NULL, 0, 'referencia inexistente');"

rechaza "una fila aceptada que trae motivo de rechazo" \
  "INSERT INTO detalle_carga_precios (empresa_id, carga_id, producto_id,
                                      referencia_origen, costo, aceptado,
                                      motivo_rechazo, motivo_codigo)
   VALUES (1, $CARGA, $PRD_PROPIO, 'IMP-INCOHERENTE', 1500.00, 1,
           'esto no debería estar', 'COSTO_NO_NUMERICO');"

rechaza "un rechazo sin motivo" \
  "INSERT INTO detalle_carga_precios (empresa_id, carga_id, producto_id,
                                      referencia_origen, costo, aceptado)
   VALUES (1, $CARGA, NULL, 'IMP-SIN-MOTIVO', NULL, 0);"


echo
echo "── el histórico de costos tampoco ──"
rechaza "histórico de la empresa 1 sobre el producto de la ajena" \
  "INSERT INTO historico_costos (empresa_id, producto_id, costo, vigente_desde)
   VALUES (1, $PRD_AJENO, 999999.00, CURDATE());"

# Con fecha antigua y no con la de hoy: el periodo que rige hoy para esa
# referencia puede existir ya —lo deja cualquier carga real— y
# `uq_historico_vigencia` es única por producto y fecha de comienzo. La
# comprobación es que la fila entra, no en qué día.
acepta "histórico sobre un producto propio" \
  "INSERT INTO historico_costos (empresa_id, producto_id, costo, vigente_desde,
                                 vigente_hasta, carga_id)
   VALUES (1, $PRD_PROPIO, 1500.00, '2001-01-01', '2001-12-31', $CARGA);"

rechaza "histórico colgado de la carga de otra empresa" \
  "INSERT INTO historico_costos (empresa_id, producto_id, costo, vigente_desde, carga_id)
   VALUES ($AJENA, $PRD_AJENO, 1500.00, '2001-01-01', $CARGA);"


echo
echo "── la correspondencia de columnas sigue a su proveedor ──"
rechaza "un mapeo de la empresa 1 sobre un proveedor de la ajena" \
  "INSERT INTO mapeos_proveedor (empresa_id, proveedor_id, campo_sistema,
                                 columna_origen, obligatorio)
   VALUES (1, $PRV_AJENO, 'codigo', 'REFERENCIA', TRUE);"

acepta "un mapeo sobre un proveedor propio" \
  "INSERT INTO mapeos_proveedor (empresa_id, proveedor_id, campo_sistema,
                                 columna_origen, obligatorio)
   VALUES (1, $PRV_PROPIO, 'codigo', 'REFERENCIA', TRUE);"
ejecutar "DELETE FROM mapeos_proveedor WHERE columna_origen='REFERENCIA';" >/dev/null


echo
echo "── aplicar la lista, con las cifras del RF-27 ──"
# Las cuatro sentencias que `RepositorioImportacion` ejecuta al aplicar,
# aquí tal cual, sobre cinco mil referencias de verdad. El requisito
# concede un minuto para esa cantidad y es la única forma de saber si
# cabe: una prueba de unidad con el repositorio simulado mide cuántas
# veces se llamó a un método, no cuánto tarda la base.
CAT=$(consulta "SELECT categoria_id FROM categorias  WHERE empresa_id=$AJENA LIMIT 1;")
PRV=$(consulta "SELECT proveedor_id FROM proveedores WHERE empresa_id=$AJENA LIMIT 1;")
USR=$(consulta "SELECT usuario_id   FROM usuarios    WHERE empresa_id=1 LIMIT 1;")

# El tope de recursión por omisión de MySQL es mil, de modo que sin
# subirlo la generación se corta en seco y deja cero filas.
ejecutar "SET SESSION cte_max_recursion_depth = 6000;
          INSERT INTO productos (empresa_id, categoria_id, proveedor_id, codigo, sku,
                                 nombre, presentacion, costo, existencia,
                                 existencia_minima, activo)
          WITH RECURSIVE n(i) AS (
            SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i < 5000)
          SELECT $AJENA, $CAT, $PRV,
                 CONCAT('IMP-M', LPAD(i,5,'0')), CONCAT('IMP-M', LPAD(i,5,'0')),
                 CONCAT('Referencia de volumen ', i), 'unidad',
                 1000.00, 0, 0, TRUE
            FROM n;" >/dev/null
masivos=$(consulta "SELECT COUNT(*) FROM productos WHERE codigo LIKE 'IMP-M%';")
igual_n () { [[ "$2" == "$3" ]] && ok "$1" || falla "$1 — esperado $2, obtenido $3"; }
igual_n "se crearon cinco mil referencias para medir" "5000" "${masivos:-0}"

ejecutar "INSERT INTO cargas_precios (empresa_id, proveedor_id, usuario_id, origen,
                                      nombre_archivo, estado, registros_leidos,
                                      registros_aceptados, registros_rechazados)
          VALUES ($AJENA, $PRV,
                  (SELECT usuario_id FROM usuarios WHERE empresa_id=$AJENA LIMIT 1),
                  'ARCHIVO','IMP-masiva.csv','VALIDADA',5000,5000,0);" >/dev/null
MASIVA=$(consulta "SELECT carga_id FROM cargas_precios WHERE nombre_archivo='IMP-masiva.csv';")

if [[ -z "$MASIVA" ]]; then
  echo "  (la empresa de prueba no tiene usuario propio: la medida no se pudo hacer)"
else
  ejecutar "INSERT INTO detalle_carga_precios (empresa_id, carga_id, numero_fila, producto_id,
                                               referencia_origen, costo, costo_anterior, aceptado)
            SELECT $AJENA, $MASIVA, ROW_NUMBER() OVER (ORDER BY producto_id) + 1,
                   producto_id, codigo, 1500.00, costo, TRUE
              FROM productos WHERE codigo LIKE 'IMP-M%';" >/dev/null

  inicio=$(date +%s%3N)
  ejecutar "
    UPDATE historico_costos h
      JOIN detalle_carga_precios d
        ON d.empresa_id = h.empresa_id AND d.producto_id = h.producto_id
       SET h.vigente_hasta = CURDATE() - INTERVAL 1 DAY
     WHERE d.empresa_id = $AJENA AND d.carga_id = $MASIVA AND d.aceptado = TRUE
       AND h.vigente_hasta IS NULL AND h.vigente_desde < CURDATE();
    DELETE h FROM historico_costos h
      JOIN detalle_carga_precios d
        ON d.empresa_id = h.empresa_id AND d.producto_id = h.producto_id
     WHERE d.empresa_id = $AJENA AND d.carga_id = $MASIVA AND d.aceptado = TRUE
       AND h.vigente_desde = CURDATE();
    INSERT INTO historico_costos (empresa_id, producto_id, costo, vigente_desde, carga_id)
    SELECT d.empresa_id, d.producto_id, d.costo, CURDATE(), d.carga_id
      FROM detalle_carga_precios d
     WHERE d.empresa_id = $AJENA AND d.carga_id = $MASIVA AND d.aceptado = TRUE;
    UPDATE productos p
      JOIN detalle_carga_precios d
        ON d.empresa_id = p.empresa_id AND d.producto_id = p.producto_id
       SET p.costo = d.costo
     WHERE d.empresa_id = $AJENA AND d.carga_id = $MASIVA AND d.aceptado = TRUE;" >/dev/null
  milisegundos=$(( $(date +%s%3N) - inicio ))
  segundos=$(( milisegundos / 1000 ))

  cambiados=$(consulta "SELECT COUNT(*) FROM productos
                         WHERE codigo LIKE 'IMP-M%' AND costo = 1500.00;")
  igual_n "las cinco mil quedaron con el costo nuevo" "5000" "${cambiados:-0}"

  # La medida solo vale si de verdad se aplicaron las cinco mil. La
  # primera versión de esto informaba «aplicados en 0s» en verde cuando
  # la generación de referencias había fallado y no se había aplicado
  # nada: un cronómetro sobre cero filas siempre cabe en el minuto.
  if [[ "${cambiados:-0}" -ne 5000 ]]; then
    falla "la medida de tiempo no se pudo hacer: se aplicaron ${cambiados:-0} de 5000"
  elif [[ "$segundos" -lt 60 ]]; then
    ok "cinco mil registros aplicados en ${milisegundos} ms (el RF-27 concede 60 000)"
  else
    falla "cinco mil registros tardaron ${segundos}s y el RF-27 concede 60"
  fi

  historico=$(consulta "SELECT COUNT(*) FROM historico_costos WHERE carga_id = $MASIVA;")
  igual_n "y cada una dejó su periodo de costo (RF-28)" "5000" "${historico:-0}"
fi


echo
echo "── aplicar dos listas el mismo día no duplica el periodo ──"
# `uq_historico_vigencia` es única por producto y fecha de comienzo. Dos
# cargas del mismo día chocarían, y la segunda tiene que sustituir a la
# primera: el costo que rige hoy es el último que se aplicó hoy.
UNO=$(consulta "SELECT producto_id FROM productos WHERE codigo LIKE 'IMP-M%' LIMIT 1;")
if [[ -n "$UNO" && -n "${MASIVA:-}" ]]; then
  ejecutar "DELETE h FROM historico_costos h
             WHERE h.empresa_id=$AJENA AND h.producto_id=$UNO AND h.vigente_desde=CURDATE();
            INSERT INTO historico_costos (empresa_id,producto_id,costo,vigente_desde,carga_id)
            VALUES ($AJENA,$UNO,1800.00,CURDATE(),$MASIVA);" >/dev/null
  n=$(consulta "SELECT COUNT(*) FROM historico_costos
                 WHERE empresa_id=$AJENA AND producto_id=$UNO AND vigente_desde=CURDATE();")
  igual_n "un solo periodo abierto hoy por referencia" "1" "${n:-0}"
  v=$(consulta "SELECT costo FROM historico_costos
                 WHERE empresa_id=$AJENA AND producto_id=$UNO AND vigente_desde=CURDATE();")
  igual_n "y el que rige es el último aplicado" "1800.00" "${v:-}"
fi


echo
echo "── los periodos de costo no se solapan ni dejan huecos (RF-28) ──"
solapes=$(consulta "SELECT COUNT(*)
  FROM historico_costos a JOIN historico_costos b
    ON a.empresa_id=b.empresa_id AND a.producto_id=b.producto_id
   AND a.historico_id <> b.historico_id
 WHERE a.vigente_desde <= COALESCE(b.vigente_hasta,'9999-12-31')
   AND b.vigente_desde <= COALESCE(a.vigente_hasta,'9999-12-31');")
igual_n "ninguna referencia tiene dos costos vigentes a la vez" "0" "${solapes:-?}"

abiertos=$(consulta "SELECT COUNT(*) FROM (
  SELECT producto_id FROM historico_costos WHERE vigente_hasta IS NULL
   GROUP BY producto_id HAVING COUNT(*) > 1) x;")
igual_n "ninguna tiene dos periodos sin cerrar" "0" "${abiertos:-?}"


echo
if [[ "$fallos" -eq 0 ]]; then
  echo "TODO EN VERDE"
  exit 0
fi
echo "$fallos comprobación(es) fallaron"
exit 1

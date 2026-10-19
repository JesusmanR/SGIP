#!/usr/bin/env bash
# Comprueba la invariante del RF-49: para cualquier referencia, la suma
# algebraica de sus movimientos iguala su existencia actual.
#
# Se ejecuta sobre una base con la cadena V1 a V23 aplicada y los datos
# de demostración cargados.
#
#   bash verificar_kardex.sh -u root -p
#   bash verificar_kardex.sh -h 127.0.0.1 -u root -psecreto
#   bash verificar_kardex.sh --socket=/tmp/my.sock
#
# Deja la base como la encontró: los movimientos que inserta para probar
# las restricciones llevan el documento 'KARDEX-PRUEBA' y se borran al
# terminar. Úsalo en una base de desarrollo.
set -uo pipefail

CONEXION=("$@")

sin_aviso () { grep -v "Using a password on the command line interface" || true; }
ejecutar () { mysql "${CONEXION[@]}" sgip -e "$1" 2>&1 | sin_aviso; }
consulta () { mysql "${CONEXION[@]}" -N -B sgip -e "$1" 2>/dev/null; }
solo_error () { grep -m1 "^ERROR" <<<"$1" || echo "${1:-(sin salida)}"; }

fallos=0
ok ()    { echo "  ok    · $1"; }
falla () { echo "  FALLA · $1"; fallos=$((fallos + 1)); }

# La suma algebraica de un movimiento.
#
# El EGRESO resta y el INGRESO y la DEVOLUCION suman, porque la V23 fijó
# que `cantidad` es una magnitud positiva y la dirección la da el tipo.
# El AJUSTE usa el salto y no la cantidad: es el único que puede ir en
# las dos direcciones, de modo que su magnitud no dice hacia dónde.
SUMA="CASE m.tipo
        WHEN 'EGRESO' THEN -m.cantidad
        WHEN 'AJUSTE' THEN m.existencia_posterior - m.existencia_anterior
        ELSE m.cantidad
      END"

echo "── la invariante del RF-49 sobre todo el catálogo ──"

total=$(consulta "SELECT COUNT(*) FROM productos;")
echo "  referencias en el catálogo: ${total:-0}"

descuadres=$(consulta "
  SELECT COUNT(*) FROM (
    SELECT p.producto_id, p.existencia,
           COALESCE(SUM($SUMA), 0) AS suma
      FROM productos p
      LEFT JOIN movimientos_inventario m
             ON m.empresa_id  = p.empresa_id
            AND m.producto_id = p.producto_id
     GROUP BY p.producto_id, p.existencia
    HAVING p.existencia <> suma) AS d;")

if [[ "${descuadres:-1}" == "0" ]]; then
  ok "ninguna referencia descuadra con sus movimientos"
else
  falla "$descuadres referencia(s) descuadran; el detalle:"
  ejecutar "
    SELECT p.codigo, p.existencia,
           COALESCE(SUM($SUMA), 0) AS suma_movimientos,
           COUNT(m.movimiento_id)  AS movimientos
      FROM productos p
      LEFT JOIN movimientos_inventario m
             ON m.empresa_id  = p.empresa_id
            AND m.producto_id = p.producto_id
     GROUP BY p.producto_id, p.codigo, p.existencia
    HAVING p.existencia <> COALESCE(SUM($SUMA), 0);" | sed 's/^/        /'
fi

# Una referencia con existencia y sin un solo movimiento es el caso que
# la V23 corrigió sembrando los saldos iniciales. Se comprueba aparte
# porque su síntoma en la suma es idéntico al de un movimiento perdido, y
# la causa no: aquí falta el primero, allí falta uno del medio.
sin_respaldo=$(consulta "
  SELECT COUNT(*) FROM productos p
   WHERE p.existencia <> 0
     AND NOT EXISTS (SELECT 1 FROM movimientos_inventario m
                      WHERE m.empresa_id  = p.empresa_id
                        AND m.producto_id = p.producto_id);")
[[ "${sin_respaldo:-1}" == "0" ]] \
  && ok "ninguna referencia tiene existencia sin un solo movimiento" \
  || falla "$sin_respaldo referencia(s) con existencia y sin movimientos"

# La cadena no debe tener huecos: el posterior de un movimiento es el
# anterior del siguiente. Es lo único que las restricciones no pueden
# comprobar —miran una fila a la vez— y lo que le toca garantizar a la
# capa de Java leyendo la existencia dentro de la misma transacción.
huecos=$(consulta "
  SELECT COUNT(*) FROM (
    SELECT m.movimiento_id,
           m.existencia_anterior,
           LAG(m.existencia_posterior) OVER (
             PARTITION BY m.empresa_id, m.producto_id
             ORDER BY m.fecha, m.movimiento_id) AS previo
      FROM movimientos_inventario m) AS c
   WHERE c.previo IS NOT NULL AND c.previo <> c.existencia_anterior;")
[[ "${huecos:-1}" == "0" ]] \
  && ok "la cadena de movimientos no tiene huecos" \
  || falla "$huecos movimiento(s) arrancan de un saldo que no es el anterior"


echo
echo "── lo que el esquema no debe dejar escribir (V23) ──"

PROD=$(consulta "SELECT producto_id FROM productos WHERE empresa_id = 1 LIMIT 1;")
USU=$(consulta "SELECT usuario_id FROM usuarios WHERE empresa_id = 1 LIMIT 1;")
if [[ -z "$PROD" || -z "$USU" ]]; then
  falla "no se encontró un producto y un usuario de la empresa 1"
else
  ejecutar "DELETE FROM movimientos_inventario WHERE documento = 'KARDEX-PRUEBA';" >/dev/null

  # $1 descripción · $2 restricción esperada · $3..$7 tipo, cantidad,
  # anterior, posterior, motivo (NULL o 'texto')
  rechaza () {
    local salida
    salida=$(ejecutar "INSERT INTO movimientos_inventario
                              (empresa_id, producto_id, usuario_id, tipo, cantidad,
                               existencia_anterior, existencia_posterior, motivo, documento)
                       VALUES (1, $PROD, $USU, '$3', $4, $5, $6, $7, 'KARDEX-PRUEBA');")
    if [[ -z "$salida" ]]; then
      falla "$1 — la fila entró"
    elif [[ "$salida" != *"$2"* ]]; then
      falla "$1 — rechazada por otra restricción: $(solo_error "$salida")"
    else
      ok "$1"
    fi
  }

  rechaza "una cantidad de cero" ck_movimientos_cantidad   INGRESO 0 10 10 NULL
  rechaza "una cantidad negativa" ck_movimientos_cantidad  INGRESO -5 10 5 NULL
  rechaza "un ingreso cuyo salto no es la cantidad" \
          ck_movimientos_coherencia INGRESO 5 10 14 NULL
  rechaza "un egreso que sube la existencia" \
          ck_movimientos_coherencia EGRESO 3 10 13 NULL
  rechaza "un ajuste cuya magnitud no es el salto" \
          ck_movimientos_coherencia AJUSTE 2 10 15 "'conteo'"
  rechaza "un ajuste sin motivo" ck_movimientos_ajuste     AJUSTE 2 10 12 NULL
  rechaza "un saldo por debajo del piso de la V18" \
          ck_movimientos_posterior EGRESO 1 -9999 -10000 NULL

  echo
  echo "── lo que sí debe poder escribirse ──"

  acepta () {
    local salida
    salida=$(ejecutar "INSERT INTO movimientos_inventario
                              (empresa_id, producto_id, usuario_id, tipo, cantidad,
                               existencia_anterior, existencia_posterior, motivo, documento)
                       VALUES (1, $PROD, $USU, '$2', $3, $4, $5, $6, 'KARDEX-PRUEBA');")
    [[ -z "$salida" ]] && ok "$1" || falla "$1 — $(solo_error "$salida")"
  }

  acepta "un ingreso que cuadra"                  INGRESO 5 10 15 NULL
  acepta "una devolución de cliente, que suma"    DEVOLUCION 4 10 14 NULL
  acepta "un ajuste al alza con su motivo"        AJUSTE 2 10 12 "'conteo físico'"
  acepta "un ajuste a la baja con su motivo"      AJUSTE 2 10 8 "'merma'"
  # La venta sin existencia del RF-42, que la V18 destrabó y que es la
  # razón por la que este guion existe junto al de aislamiento.
  acepta "un egreso que deja el saldo en negativo" EGRESO 3 0 -3 NULL

  ejecutar "DELETE FROM movimientos_inventario WHERE documento = 'KARDEX-PRUEBA';" >/dev/null
fi

echo
if [[ "$fallos" -eq 0 ]]; then
  echo "TODO EN VERDE"
  exit 0
fi
echo "$fallos comprobación(es) fallaron"
exit 1

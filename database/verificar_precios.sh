#!/usr/bin/env bash
# Comprueba el motor de precios: las dos bases de la regla de ganancia,
# la regla más específica, el aislamiento entre empresas y el umbral
# tributario (RF-30 a RF-32, RF-34).
#
# Se ejecuta sobre una base con la cadena V1 a V25 aplicada y los datos
# de demostración cargados.
#
#   bash verificar_precios.sh -u root -p
#   bash verificar_precios.sh --socket=/tmp/my.sock
#
# Deja la base como la encontró: los productos y las reglas que crea
# llevan el prefijo PREC- y se retiran al terminar.
set -uo pipefail

CONEXION=("$@")

sin_aviso () { grep -v "Using a password on the command line interface" || true; }
ejecutar () { mysql "${CONEXION[@]}" sgip -e "$1" 2>&1 | sin_aviso; }
consulta () { mysql "${CONEXION[@]}" -N -B sgip -e "$1" 2>/dev/null; }
solo_error () { grep -m1 "^ERROR" <<<"$1" || echo "${1:-(sin salida)}"; }

fallos=0
ok ()    { echo "  ok    · $1"; }
falla () { echo "  FALLA · $1"; fallos=$((fallos + 1)); }
igual () { # $1 descripción · $2 esperado · $3 obtenido
  if [[ "$2" == "$3" ]]; then ok "$1"; else falla "$1 — esperado $2, obtenido $3"; fi
}

limpiar () {
  ejecutar "DELETE FROM movimientos_inventario
             WHERE producto_id IN (SELECT producto_id FROM productos
                                    WHERE codigo LIKE 'PREC-%');" >/dev/null
  ejecutar "DELETE FROM reglas_margen WHERE factor IN (1.3300, 1.4400);" >/dev/null
  ejecutar "DELETE FROM productos WHERE codigo LIKE 'PREC-%';" >/dev/null
}
trap limpiar EXIT
limpiar

CAT=$(consulta "SELECT categoria_id FROM categorias WHERE empresa_id = 1 LIMIT 1;")
PRV=$(consulta "SELECT proveedor_id FROM proveedores WHERE empresa_id = 1 LIMIT 1;")
if [[ -z "$CAT" || -z "$PRV" ]]; then
  echo "No hay categoría y proveedor de la empresa 1. ¿Están los datos de demostración?"
  exit 1
fi

crear () { # $1 código · $2 costo · $3 categoría
  ejecutar "INSERT INTO productos (empresa_id, categoria_id, proveedor_id, codigo, sku,
                                    nombre, presentacion, costo, existencia,
                                    existencia_minima, activo)
            VALUES (1, $3, $PRV, '$1', '$1', 'Prueba de precios $1', 'unidad',
                    $2, 0, 0, TRUE);" >/dev/null
}
precio () { consulta "SELECT precio_contado FROM v_precios_producto
                       WHERE empresa_id = 1 AND codigo = '$1';"; }
campo ()  { consulta "SELECT $2 FROM v_precios_producto
                       WHERE empresa_id = 1 AND codigo = '$1';"; }


echo "── las dos bases de la regla de ganancia (RF-32) ──"
# El ejemplo del requisito, con sus cifras: costo 1 000, 20 %.
crear PREC-BASE 1000.00 "$CAT"
ejecutar "UPDATE reglas_margen SET forma_calculo='SOBRE_COSTO', factor=1.2000
           WHERE empresa_id=1 AND forma_pago='CONTADO'
             AND categoria_id IS NULL AND proveedor_id IS NULL;" >/dev/null
igual "recargo del 20 % sobre el costo da 1 200" "1200" "$(precio PREC-BASE)"
igual "y la vista dice qué base aplicó" "SOBRE_COSTO" "$(campo PREC-BASE base_contado)"

ejecutar "UPDATE reglas_margen SET forma_calculo='SOBRE_PRECIO', factor=1.2000
           WHERE empresa_id=1 AND forma_pago='CONTADO'
             AND categoria_id IS NULL AND proveedor_id IS NULL;" >/dev/null
igual "margen del 20 % sobre el precio da 1 250" "1250" "$(precio PREC-BASE)"
igual "y la vista lo dice" "SOBRE_PRECIO" "$(campo PREC-BASE base_contado)"

# Un margen del 100 % sobre el precio significaría precio infinito.
salida=$(ejecutar "UPDATE reglas_margen SET factor=2.0000
                    WHERE empresa_id=1 AND forma_pago='CONTADO'
                      AND categoria_id IS NULL AND proveedor_id IS NULL;")
if [[ "$salida" == *"ck_reglas_margen_sobre_precio"* ]]; then
  ok "el margen sobre el precio no llega al 100 %"
else
  falla "un margen del 100 % sobre el precio entró: $(solo_error "${salida:-(sin error)}")"
fi

ejecutar "UPDATE reglas_margen SET forma_calculo='SOBRE_COSTO', factor=1.2000
           WHERE empresa_id=1 AND forma_pago='CONTADO'
             AND categoria_id IS NULL AND proveedor_id IS NULL;" >/dev/null


echo
echo "── ante reglas concurrentes gana la más específica (RN-03, RF-30) ──"
# La general ya existe, al 20 %. Se añade una de categoría al 33 % y
# luego una de categoría y proveedor al 44 %.
ejecutar "INSERT INTO reglas_margen (empresa_id, categoria_id, proveedor_id, forma_pago,
                                      forma_calculo, factor, vigente_desde)
          VALUES (1, $CAT, NULL, 'CONTADO', 'SOBRE_COSTO', 1.3300, '2026-01-01');" >/dev/null
igual "la regla de categoría gana a la general" "1330" "$(precio PREC-BASE)"

ejecutar "INSERT INTO reglas_margen (empresa_id, categoria_id, proveedor_id, forma_pago,
                                      forma_calculo, factor, vigente_desde)
          VALUES (1, $CAT, $PRV, 'CONTADO', 'SOBRE_COSTO', 1.4400, '2026-01-01');" >/dev/null
igual "la de categoría y proveedor gana a la de categoría" "1440" "$(precio PREC-BASE)"

# Y la de otra empresa no se cuela.
AJENA=$(consulta "SELECT empresa_id FROM empresas WHERE empresa_id <> 1 LIMIT 1;")
if [[ -n "$AJENA" ]]; then
  antes=$(precio PREC-BASE)
  ejecutar "INSERT INTO reglas_margen (empresa_id, categoria_id, proveedor_id, forma_pago,
                                        forma_calculo, factor, vigente_desde)
            VALUES ($AJENA, NULL, NULL, 'CONTADO', 'SOBRE_COSTO', 1.3300, '2026-01-01');" >/dev/null
  igual "una regla de otra empresa no cambia este precio" "$antes" "$(precio PREC-BASE)"
  ejecutar "DELETE FROM reglas_margen WHERE empresa_id = $AJENA AND factor = 1.3300;" >/dev/null
else
  echo "  (sin segunda empresa cargada: el aislamiento de reglas no se probó)"
fi

ejecutar "DELETE FROM reglas_margen WHERE empresa_id=1 AND factor IN (1.3300, 1.4400);" >/dev/null


echo
echo "── el umbral tributario, con tres referencias (RF-34) ──"
# La categoría tiene que ser una de umbral, o no hay nada que probar.
TRAT=$(campo PREC-BASE tratamiento_iva)
if [[ "$TRAT" != "EXCLUIDO_HASTA_UMBRAL" ]]; then
  CAT_U=$(consulta "SELECT p.categoria_id FROM v_precios_producto p
                     WHERE p.empresa_id = 1
                       AND p.tratamiento_iva = 'EXCLUIDO_HASTA_UMBRAL' LIMIT 1;")
else
  CAT_U=$CAT
fi

if [[ -z "$CAT_U" ]]; then
  falla "no hay ninguna categoría con tratamiento de umbral; el RF-34 no se pudo probar"
else
  # El umbral y el factor se leen de la vista, no se escriben a mano: la
  # UVT cambia cada año y una prueba con una cifra fija caducaría sola.
  crear PREC-U 1000.00 "$CAT_U"
  UMBRAL=$(consulta "SELECT FLOOR(umbral_pesos) FROM v_precios_producto
                      WHERE empresa_id = 1 AND codigo = 'PREC-U';")
  FACTOR=$(consulta "SELECT factor_contado FROM v_precios_producto
                      WHERE empresa_id = 1 AND codigo = 'PREC-U';")
  echo "  umbral vigente: $UMBRAL · factor: $FACTOR"

  # El costo que deja el precio justo en el umbral.
  COSTO=$(consulta "SELECT ROUND($UMBRAL / $FACTOR, 2);")
  for delta in -1 0 1; do
    ejecutar "UPDATE productos SET costo = $COSTO + ($delta)
               WHERE empresa_id = 1 AND codigo = 'PREC-U';" >/dev/null
    p=$(precio PREC-U)
    t=$(campo PREC-U tarifa_contado)
    gravado=$(awk -v t="$t" 'BEGIN{print (t+0 > 0) ? "gravado" : "excluido"}')
    esperado=$(awk -v p="$p" -v u="$UMBRAL" 'BEGIN{print (p+0 > u+0) ? "gravado" : "excluido"}')
    rel=$(awk -v p="$p" -v u="$UMBRAL" \
            'BEGIN{print (p+0<u+0)?"por debajo del umbral":((p+0==u+0)?"justo en el umbral":"por encima del umbral")}')
    igual "precio $p, $rel → $esperado" "$esperado" "$gravado"
  done
fi


echo
echo "── el precio no se captura a mano (RF-31) ──"
n=$(consulta "SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema='sgip' AND table_name='productos'
                 AND column_name LIKE '%precio%';")
igual "productos no tiene ninguna columna de precio" "0" "${n:-?}"

# Y el precio sigue al costo sin que nadie lo reescriba.
ejecutar "UPDATE productos SET costo = 2000.00 WHERE empresa_id=1 AND codigo='PREC-BASE';" >/dev/null
igual "al doblar el costo, el precio se dobla solo" "2400" "$(precio PREC-BASE)"


echo
echo "── el aviso de cruce del umbral (RF-35) ──"
# La simulación del RF-35 calcula el precio de una regla que todavía no
# existe, de modo que no puede salir de la vista: repite su fórmula. Si
# las dos expresiones discreparan, el aviso mentiría. Esta comprobación
# es la que lo ataja: simular con el factor que YA rige tiene que
# encontrar cero cruces, porque nada cambia.
FACTOR_VIGENTE=$(consulta "SELECT factor FROM reglas_margen
                            WHERE empresa_id = 1 AND forma_pago = 'CONTADO'
                              AND categoria_id IS NULL AND proveedor_id IS NULL
                            LIMIT 1;")
cruces () { # $1 factor
  consulta "SELECT COUNT(*) FROM v_precios_producto v
             WHERE v.empresa_id = 1
               AND v.tratamiento_iva = 'EXCLUIDO_HASTA_UMBRAL'
               AND ((v.tarifa_contado > 0)
                    <> (ROUND(v.costo * $1, 0) > v.umbral_pesos));"
}
igual "simular con el factor vigente ($FACTOR_VIGENTE) no encuentra cruces" \
      "0" "$(cruces "$FACTOR_VIGENTE")"

# Y subirlo sí tiene que encontrarlos, o la comprobación de arriba
# estaría pasando porque la consulta no mira nada.
SUBIDO=$(consulta "SELECT $FACTOR_VIGENTE + 0.1000;")
n=$(cruces "$SUBIDO")
if [[ "${n:-0}" -gt 0 ]]; then
  ok "subir el factor a $SUBIDO avisa de $n referencia(s) que cruzarían"
else
  falla "subir el factor a $SUBIDO no avisó de ninguna: la simulación no está mirando"
fi

echo
if [[ "$fallos" -eq 0 ]]; then
  echo "TODO EN VERDE"
  exit 0
fi
echo "$fallos comprobación(es) fallaron"
exit 1

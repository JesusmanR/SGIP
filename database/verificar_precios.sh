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
# existe, de modo que no puede salir de la vista: repite su fórmula (ver
# ADR-0021). Si las dos expresiones discreparan, el aviso mentiría.
#
# Lo que ataja esa discrepancia es la comprobación de aquí abajo, que
# compara precio contra precio para TODA referencia. La que parecía
# bastar —simular el factor vigente tiene que dar cero cruces— no basta:
# probada a propósito con la fórmula desviada un 0,1 %, pasó en verde,
# porque solo mira la franja pegada al umbral y en esa franja no caía
# ninguna referencia. Se conserva debajo, pero como prueba de extremo a
# extremo del aviso, no como guardián de la duplicación.
#
# El factor y la base se leen de la vista referencia por referencia, no
# de la regla general: así la comprobación sigue valiendo cuando un
# producto esté gobernado por una regla de categoría o de proveedor.
desviadas () { # cuántas referencias calculan distinto en los dos sitios
  consulta "SELECT COUNT(*) FROM v_precios_producto v
             WHERE v.empresa_id = 1
               AND v.factor_contado IS NOT NULL
               AND (CASE WHEN v.base_contado = 'SOBRE_PRECIO'
                         THEN ROUND(v.costo / (2 - v.factor_contado), 0)
                         ELSE ROUND(v.costo * v.factor_contado, 0) END)
                   <> v.precio_contado;"
}

cruces () { # $1 factor · $2 forma de cálculo
  local precio
  if [[ "$2" == "SOBRE_PRECIO" ]]; then
    precio="ROUND(v.costo / (2 - $1), 0)"
  else
    precio="ROUND(v.costo * $1, 0)"
  fi
  consulta "SELECT COUNT(*) FROM v_precios_producto v
             WHERE v.empresa_id = 1
               AND v.tratamiento_iva = 'EXCLUIDO_HASTA_UMBRAL'
               AND ((v.tarifa_contado > 0)
                    <> ($precio > v.umbral_pesos));"
}

regir () { # $1 forma de cálculo · $2 factor
  ejecutar "UPDATE reglas_margen SET forma_calculo='$1', factor=$2
             WHERE empresa_id=1 AND forma_pago='CONTADO'
               AND categoria_id IS NULL AND proveedor_id IS NULL;" >/dev/null
}

for base in SOBRE_COSTO SOBRE_PRECIO; do
  # La fórmula duplicada se vigila en sus dos ramas, no solo en la que
  # rija hoy: dejar una sin guardián es exactamente lo que permitió que
  # la vista ignorara `forma_calculo` hasta la V25.
  # Dos factores que dan precios distintos en cada base, para que la
  # invariante no se cumpla por casualidad de que coincidan. Y los dos
  # bajos a propósito: con un factor alto casi todo el catálogo ya cruzó
  # el umbral y el control de abajo se queda sin referencia que mover.
  if [[ "$base" == "SOBRE_PRECIO" ]]; then f=1.1000; else f=1.0500; fi
  regir "$base" "$f"
  igual "con $base, la simulación y la vista dan el mismo precio" \
        "0" "$(desviadas)"
  igual "con $base al factor $f, simular lo vigente no encuentra cruces" \
        "0" "$(cruces "$f" "$base")"

  # Y un factor más alto sí tiene que encontrarlos, o la comprobación de
  # arriba estaría pasando porque la consulta no mira nada.
  #
  # Cuál sea ese factor no se puede fijar a mano: subir un décimo encuentra
  # referencias o no según dónde caigan los costos de la base cargada, y
  # una cifra escrita aquí convertiría el control en una lotería. Se
  # despeja de los datos el factor que cruza a la SIGUIENTE referencia en
  # hacerlo y se pasa un pelo de ahí:
  #
  #   sobre el costo    costo × f > umbral          →  f > umbral / costo
  #   sobre el precio   costo / (2 − f) > umbral    →  f > 2 − costo / umbral
  #
  # El mínimo tiene que buscarse entre los que superan el factor vigente.
  # El mínimo a secas devuelve el de la referencia más barata, que no es
  # la siguiente en cruzar sino la última.
  if [[ "$base" == "SOBRE_PRECIO" ]]; then
    necesario="(2 - (v.costo / v.umbral_pesos))"
  else
    necesario="(v.umbral_pesos / v.costo)"
  fi
  SUBIDO=$(consulta "SELECT ROUND(MIN($necesario) + 0.0100, 4)
                       FROM v_precios_producto v
                      WHERE v.empresa_id = 1
                        AND v.tratamiento_iva = 'EXCLUIDO_HASTA_UMBRAL'
                        AND v.tarifa_contado = 0
                        AND v.costo > 0
                        AND $necesario > $f;")
  if [[ -z "$SUBIDO" || "$SUBIDO" == "NULL" ]]; then
    echo "  (con $base no queda ninguna referencia por cruzar: el control no se probó)"
  elif awk -v s="$SUBIDO" 'BEGIN{exit !(s+0 >= 2)}'; then
    echo "  (con $base haría falta un factor de $SUBIDO, que la restricción prohíbe)"
  else
    n=$(cruces "$SUBIDO" "$base")
    if [[ "${n:-0}" -gt 0 ]]; then
      ok "con $base, subir a $SUBIDO avisa de $n referencia(s) que cruzarían"
    else
      falla "con $base, subir a $SUBIDO no avisó de ninguna: la simulación no mira"
    fi
  fi
done

# Se devuelve la regla general a como estaba.
regir SOBRE_COSTO 1.2000

echo
if [[ "$fallos" -eq 0 ]]; then
  echo "TODO EN VERDE"
  exit 0
fi
echo "$fallos comprobación(es) fallaron"
exit 1

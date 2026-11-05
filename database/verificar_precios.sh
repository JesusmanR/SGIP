#!/usr/bin/env bash
# Comprueba el motor de precios: las dos bases de la regla de ganancia,
# la regla más específica, el aislamiento entre empresas y el umbral
# tributario (RF-30 a RF-32, RF-34).
#
# Se ejecuta sobre una base con todas las migraciones aplicadas y los datos
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
  ejecutar "DELETE FROM reglas_margen
             WHERE producto_id IN (SELECT producto_id FROM productos
                                    WHERE codigo LIKE 'PREC-%');" >/dev/null
  ejecutar "DELETE FROM productos WHERE codigo LIKE 'PREC-%';" >/dev/null
  ejecutar "DELETE FROM uvt_anual WHERE resolucion LIKE 'PREC-%';" >/dev/null
  ejecutar "DELETE FROM proveedores
             WHERE empresa_id IN (SELECT empresa_id FROM empresas WHERE nit = '900000777-1');" >/dev/null
  ejecutar "DELETE FROM categorias
             WHERE empresa_id IN (SELECT empresa_id FROM empresas WHERE nit = '900000777-1');" >/dev/null
  ejecutar "DELETE FROM empresas WHERE nit = '900000777-1';" >/dev/null
}
trap limpiar EXIT
limpiar

# La vista mide el umbral con la UVT del año en curso, y los datos de
# demostración traen solo 2025 y 2026. Sin esta fila, el 1 de enero de 2027
# `umbral_pesos` sería nulo y la prueba del umbral no probaría nada. Si
# falta la UVT del año en curso se pone una de prueba, que `limpiar` retira.
ejecutar "INSERT INTO uvt_anual (anio, valor, resolucion)
          SELECT YEAR(CURDATE()), 52374.00, 'PREC- UVT de prueba'
           WHERE NOT EXISTS (SELECT 1 FROM uvt_anual WHERE anio = YEAR(CURDATE()));" >/dev/null

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
echo "── el precio sale de una regla, no de una columna del producto (ADR-0025) ──"
# El ADR-0025 retiró la prohibición de escribir el precio del RF-31. Lo que se
# conserva es dónde vive: en una regla con vigencia, que deja historia, y no
# en una columna del producto que se sobrescribe sin rastro.
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
echo "── el porcentaje y el precio escritos para un producto (ADR-0025) ──"
# Las cifras son las de la tabla de reglas de la tarea, y cada producto se
# crea aquí: ninguna comprobación depende de lo que haya en la demostración.
regla_propia () { # $1 código · $2 forma de pago · $3 factor · $4 precio fijo · $5 desde · $6 hasta
  ejecutar "INSERT INTO reglas_margen (empresa_id, producto_id, forma_pago, forma_calculo,
                                        factor, precio_fijo, vigente_desde, vigente_hasta)
            SELECT empresa_id, producto_id, '$2', 'SOBRE_COSTO', $3, $4, $5, $6
              FROM productos WHERE codigo = '$1';"
}
hoy="CURDATE()"
ayer="CURDATE() - INTERVAL 1 DAY"
abierta="NULL"

crear PREC-PR-20 1000.00 "$CAT"
regla_propia PREC-PR-20 CONTADO 1.2000 NULL "$hoy" "$abierta" >/dev/null
igual "costo 1.000 y 20 % dan 1.200" "1200" "$(precio PREC-PR-20)"
igual "y su margen es 20,00 %" "20.00" "$(campo PREC-PR-20 margen_contado)"
igual "y la vista dice el porcentaje escrito" "20.00" "$(campo PREC-PR-20 porcentaje_contado)"
igual "sin precio fijo" "sin precio fijo" \
      "$(campo PREC-PR-20 "COALESCE(precio_fijo_contado, 'sin precio fijo')")"

crear PREC-PR-999 999.00 "$CAT"
regla_propia PREC-PR-999 CONTADO 1.2000 NULL "$hoy" "$abierta" >/dev/null
igual "costo 999 y 20 % dan 1.199: se redondea al peso" "1199" "$(precio PREC-PR-999)"
igual "el margen se mide contra ese precio: 20,02 %" "20.02" "$(campo PREC-PR-999 margen_contado)"
igual "y el porcentaje escrito sigue siendo 20,00 %" "20.00" "$(campo PREC-PR-999 porcentaje_contado)"

crear PREC-PR-FIJO 1000.00 "$CAT"
regla_propia PREC-PR-FIJO CONTADO NULL 1250 "$hoy" "$abierta" >/dev/null
igual "un precio escrito se vende a esa cifra" "1250" "$(precio PREC-PR-FIJO)"
igual "costo 1.000 y precio 1.250 son 25,00 %" "25.00" "$(campo PREC-PR-FIJO margen_contado)"
igual "la vista expone el precio fijo" "1250" "$(campo PREC-PR-FIJO precio_fijo_contado)"
igual "un precio fijo no se calcula sobre ninguna base" \
      "sin base" "$(campo PREC-PR-FIJO "COALESCE(base_contado, 'sin base')")"
igual "ni tiene porcentaje escrito" \
      "sin porcentaje" "$(campo PREC-PR-FIJO "COALESCE(porcentaje_contado, 'sin porcentaje')")"

ejecutar "UPDATE productos SET costo = 1500.00 WHERE codigo = 'PREC-PR-20';" >/dev/null
igual "si se escribió el 20 % y sube el costo, cambia el precio: 1.800" "1800" "$(precio PREC-PR-20)"
igual "y el porcentaje se conserva" "20.00" "$(campo PREC-PR-20 margen_contado)"

ejecutar "UPDATE productos SET costo = 1100.00 WHERE codigo = 'PREC-PR-FIJO';" >/dev/null
igual "si se escribió el precio y sube el costo, el precio se conserva" "1250" "$(precio PREC-PR-FIJO)"
igual "y cambia el margen: 13,64 %" "13.64" "$(campo PREC-PR-FIJO margen_contado)"

crear PREC-PR-BAJO 1000.00 "$CAT"
regla_propia PREC-PR-BAJO CONTADO NULL 900 "$hoy" "$abierta" >/dev/null
igual "un precio bajo el costo se admite" "900" "$(precio PREC-PR-BAJO)"
igual "y su margen sale negativo: −10,00 %" "-10.00" "$(campo PREC-PR-BAJO margen_contado)"

# Crédito y contado son reglas separadas: una no arrastra a la otra.
regla_propia PREC-PR-BAJO CREDITO NULL 1300 "$hoy" "$abierta" >/dev/null
igual "el precio a crédito es el suyo" "1300" "$(campo PREC-PR-BAJO precio_credito)"
igual "con su propio margen" "30.00" "$(campo PREC-PR-BAJO margen_credito)"
igual "y su precio fijo" "1300" "$(campo PREC-PR-BAJO precio_fijo_credito)"
igual "y el de contado no se movió" "900" "$(precio PREC-PR-BAJO)"


echo
echo "── un costo que no sea positivo no llega a dividir ──"
# El margen divide por el costo. Lo que lo protege es ck_productos_costo; si
# algún día se quita, la vista devuelve NULL y no un margen inventado.
salida=$(ejecutar "UPDATE productos SET costo = 0 WHERE codigo = 'PREC-PR-20';")
if [[ "$salida" == *"ck_productos_costo"* ]]; then
  ok "un costo de cero sigue sin poder guardarse"
else
  falla "un costo de cero entró: $(solo_error "${salida:-(sin error)}")"
fi


echo
echo "── la regla del producto gana, y solo en ese producto ──"
# Una de categoría y proveedor ya rige para PREC-PR-GANA; la propia debe
# imponerse y no rozar a PREC-PR-VECINO, de la misma categoría y proveedor.
ejecutar "INSERT INTO reglas_margen (empresa_id, categoria_id, proveedor_id, forma_pago,
                                      forma_calculo, factor, vigente_desde)
          VALUES (1, $CAT, $PRV, 'CONTADO', 'SOBRE_COSTO', 1.4400, '2026-01-01');" >/dev/null
crear PREC-PR-GANA 1000.00 "$CAT"
crear PREC-PR-VECINO 1000.00 "$CAT"
igual "antes de la regla propia manda la de categoría y proveedor" "1440" "$(precio PREC-PR-GANA)"
regla_propia PREC-PR-GANA CONTADO 1.0500 NULL "$hoy" "$abierta" >/dev/null
igual "la regla del producto gana a la de categoría y a la general" "1050" "$(precio PREC-PR-GANA)"
igual "el producto vecino sigue con la de categoría" "1440" "$(precio PREC-PR-VECINO)"
igual "la vista distingue la regla propia" "1" "$(campo PREC-PR-GANA regla_propia_contado)"
igual "y la que no lo es" "0" "$(campo PREC-PR-VECINO regla_propia_contado)"

# La vigencia también vale para la regla propia: una vencida o una que aún
# no empieza no decide nada, y el precio vuelve a la regla que corresponda.
crear PREC-PR-VENCE 1000.00 "$CAT"
regla_propia PREC-PR-VENCE CONTADO NULL 5000 "$ayer" "$ayer" >/dev/null
igual "una regla propia vencida no decide" "1440" "$(precio PREC-PR-VENCE)"
regla_propia PREC-PR-VENCE CONTADO NULL 6000 "CURDATE() + INTERVAL 1 DAY" "$abierta" >/dev/null
igual "ni una que todavía no empieza" "1440" "$(precio PREC-PR-VENCE)"
ejecutar "DELETE FROM reglas_margen WHERE empresa_id=1 AND factor = 1.4400;" >/dev/null

# Una segunda empresa con su propio catálogo: nace sin ninguna regla.
ejecutar "INSERT INTO empresas (nit, razon_social, estado)
          VALUES ('900000777-1', 'PREC- Otra empresa', 'ACTIVA');" >/dev/null
OTRA=$(consulta "SELECT empresa_id FROM empresas WHERE nit = '900000777-1';")
ejecutar "INSERT INTO categorias (empresa_id, nombre, activa) VALUES ($OTRA, 'PREC- cat', TRUE);" >/dev/null
ejecutar "INSERT INTO proveedores (empresa_id, razon_social, nit, plazo_dias, origen_lista, activo)
          VALUES ($OTRA, 'PREC- prov', '800000777-2', 30, 'NINGUNO', TRUE);" >/dev/null
CAT2=$(consulta "SELECT categoria_id FROM categorias WHERE empresa_id = $OTRA;")
PRV2=$(consulta "SELECT proveedor_id FROM proveedores WHERE empresa_id = $OTRA;")
ejecutar "INSERT INTO productos (empresa_id, categoria_id, proveedor_id, codigo, sku, nombre,
                                  presentacion, costo, existencia, existencia_minima, activo)
          VALUES ($OTRA, $CAT2, $PRV2, 'PREC-OTRA', 'PREC-OTRA', 'Prueba otra empresa',
                  'unidad', 1000.00, 0, 0, TRUE);" >/dev/null
de_otra () { consulta "SELECT COALESCE($1, 'NULL') FROM v_precios_producto
                        WHERE empresa_id = $OTRA AND codigo = 'PREC-OTRA';"; }
igual "una regla propia de otra empresa no alcanza a esta" "NULL" "$(de_otra precio_contado)"
igual "sin ninguna regla el precio es NULL, nunca cero" "NULL" "$(de_otra precio_credito)"
igual "sin precio no hay margen" "NULL" "$(de_otra margen_contado)"
igual "ni total" "NULL" "$(de_otra total_contado)"

salida=$(ejecutar "INSERT INTO reglas_margen (empresa_id, producto_id, forma_pago, forma_calculo,
                                               factor, precio_fijo, vigente_desde)
                   SELECT 1, producto_id, 'CONTADO', 'SOBRE_COSTO', NULL, 1000, CURDATE()
                     FROM productos WHERE codigo = 'PREC-OTRA';")
if [[ "$salida" == *"fk_reglas_margen_producto_emp"* ]]; then
  ok "una empresa no puede ponerle regla al producto de otra"
else
  falla "una regla cruzó de empresa: $(solo_error "${salida:-(sin error)}")"
fi

# El caso de la V34: sin regla de impuesto, el total es el precio.
regla_propia PREC-OTRA CONTADO NULL 1500 "$hoy" "$abierta" >/dev/null
igual "un precio fijo sin regla de impuesto: el total es el precio" "1500.00" \
      "$(consulta "SELECT ROUND(total_contado, 2) FROM v_precios_producto
                    WHERE empresa_id = $OTRA AND codigo = 'PREC-OTRA';")"
igual "y el crédito de ese producto sigue sin precio" "NULL" "$(de_otra precio_credito)"


echo
echo "── el esquema se niega a guardar una regla sin sentido ──"
rechaza () { # $1 descripción · $2 restricción esperada · $3 sentencia
  local salida
  salida=$(ejecutar "$3")
  if [[ "$salida" == *"$2"* ]]; then
    ok "$1"
  else
    falla "$1 — entró o falló por otro motivo: $(solo_error "${salida:-(sin error)}")"
  fi
}
crear PREC-PR-CK 1000.00 "$CAT"
ID_CK=$(consulta "SELECT producto_id FROM productos WHERE codigo = 'PREC-PR-CK';")
insertar_regla () { # $1 producto · $2 factor · $3 precio fijo
  echo "INSERT INTO reglas_margen (empresa_id, producto_id, forma_pago, forma_calculo,
                                   factor, precio_fijo, vigente_desde)
        VALUES (1, $1, 'CONTADO', 'SOBRE_COSTO', $2, $3, CURDATE());"
}
rechaza "sin factor ni precio no hay regla" "ck_reglas_margen_factor_o_precio" \
        "$(insertar_regla "$ID_CK" NULL NULL)"
rechaza "factor y precio a la vez son dos respuestas" "ck_reglas_margen_factor_o_precio" \
        "$(insertar_regla "$ID_CK" 1.2000 1200)"
rechaza "un precio fijo exige producto" "ck_reglas_margen_factor_o_precio" \
        "$(insertar_regla NULL NULL 1200)"
rechaza "un precio fijo de cero se rechaza" "ck_reglas_margen_precio_fijo" \
        "$(insertar_regla "$ID_CK" NULL 0)"
rechaza "un precio fijo negativo se rechaza" "ck_reglas_margen_precio_fijo" \
        "$(insertar_regla "$ID_CK" NULL -5)"
rechaza "un precio fijo de 13 cifras no cabe: no se podría vender" "precio_fijo" \
        "$(insertar_regla "$ID_CK" NULL 1234567890123)"
rechaza "un porcentaje de cero, que es factor 1, se rechaza" "ck_reglas_margen_factor" \
        "$(insertar_regla "$ID_CK" 1.0000 NULL)"
regla_propia PREC-PR-CK CONTADO 1.2000 NULL "$hoy" "$abierta" >/dev/null
rechaza "dos reglas propias que empiezan el mismo día no caben" "uq_reglas_margen_producto" \
        "$(insertar_regla "$ID_CK" 1.3000 NULL)"
# El cierre del historial: cerrar ayer solo vale si empezó antes de hoy.
rechaza "una regla que empieza hoy no se puede cerrar ayer" "ck_reglas_margen_rango" \
        "UPDATE reglas_margen SET vigente_hasta = CURDATE() - INTERVAL 1 DAY
          WHERE empresa_id = 1 AND producto_id = $ID_CK AND vigente_desde = CURDATE();"
ejecutar "DELETE FROM reglas_margen WHERE empresa_id = 1 AND producto_id = $ID_CK;" >/dev/null
regla_propia PREC-PR-CK CONTADO 1.2000 NULL "$ayer" "$abierta" >/dev/null
salida=$(ejecutar "UPDATE reglas_margen SET vigente_hasta = CURDATE() - INTERVAL 1 DAY
                    WHERE empresa_id = 1 AND producto_id = $ID_CK;")
igual "y una que empezó ayer sí, el mismo día en que empezó" "" "$salida"
igual "y queda cerrada" "1" "$(consulta "SELECT COUNT(*) FROM reglas_margen
                                          WHERE empresa_id = 1 AND producto_id = $ID_CK
                                            AND vigente_hasta = CURDATE() - INTERVAL 1 DAY;")"


echo
echo "── un precio fijo también decide el umbral del Art. 424 ──"
if [[ -z "$CAT_U" ]]; then
  falla "no hay categoría con tratamiento de umbral; este caso no se pudo probar"
else
  crear PREC-PR-U 1000.00 "$CAT_U"
  regla_propia PREC-PR-U CONTADO NULL "$UMBRAL" "$hoy" "$abierta" >/dev/null
  regla_propia PREC-PR-U CREDITO NULL "$((UMBRAL + 1))" "$hoy" "$abierta" >/dev/null
  igual "justo en el umbral: excluido aunque el costo sea mínimo" "1" \
        "$(consulta "SELECT tarifa_contado = 0 FROM v_precios_producto
                      WHERE empresa_id = 1 AND codigo = 'PREC-PR-U';")"
  igual "un peso por encima a crédito: gravado" "1" \
        "$(consulta "SELECT tarifa_credito > 0 FROM v_precios_producto
                      WHERE empresa_id = 1 AND codigo = 'PREC-PR-U';")"
  igual "y la vista avisa que cruza en crédito" "1" "$(campo PREC-PR-U cruza_umbral_en_credito)"
  igual "el total de contado es el precio fijo" "1" \
        "$(consulta "SELECT total_contado = precio_fijo_contado FROM v_precios_producto
                      WHERE empresa_id = 1 AND codigo = 'PREC-PR-U';")"
  igual "el total a crédito es el precio fijo con su impuesto" "1" \
        "$(consulta "SELECT total_credito = precio_fijo_credito * (1 + tarifa_credito)
                       FROM v_precios_producto
                      WHERE empresa_id = 1 AND codigo = 'PREC-PR-U';")"
fi


echo
echo "── una regla sobre el precio dice su porcentaje sobre el costo ──"
# Una regla SOBRE_PRECIO de factor 1,33 es un margen del 33 % sobre el precio,
# que sobre el costo son 49,25 %. El margen se mide contra el precio ya
# redondeado, de modo que se separa del porcentaje por unas décimas.
ejecutar "INSERT INTO reglas_margen (empresa_id, categoria_id, proveedor_id, forma_pago,
                                      forma_calculo, factor, vigente_desde)
          VALUES (1, $CAT, NULL, 'CONTADO', 'SOBRE_PRECIO', 1.3300, '2026-01-01');" >/dev/null
crear PREC-PR-BASE 1000.00 "$CAT"
igual "el precio sigue siendo el de su fórmula" "1493" "$(precio PREC-PR-BASE)"
igual "el margen se mide sobre el costo" "49.30" "$(campo PREC-PR-BASE margen_contado)"
igual "y el porcentaje de la regla también" "49.25" "$(campo PREC-PR-BASE porcentaje_contado)"
ejecutar "DELETE FROM reglas_margen WHERE empresa_id=1 AND factor = 1.3300;" >/dev/null


echo
echo "── la tabla de casos que comparten la vista, el servicio y el navegador ──"
# Este archivo lo leen también CalculoDePrecioTest y precio.prueba.js. Si los
# tres leen la misma tabla, los tres redondean igual o alguno se pone en rojo;
# tres tablas escritas a mano podrían ponerse de acuerdo en un error.
CASOS="$(dirname "${BASH_SOURCE[0]}")/muestras/casos_de_precio.csv"
if [[ ! -r "$CASOS" ]]; then
  falla "no se pudo leer la tabla de casos en $CASOS"
else
  fila=0
  while IFS=, read -r tipo costo entrada esperado _nota; do
    [[ "$tipo" == "tipo" ]] && continue
    fila=$((fila + 1))
    cod="PREC-T$fila"
    crear "$cod" "$costo" "$CAT"
    if [[ "$tipo" == "precio_desde_porcentaje" ]]; then
      factor=$(consulta "SELECT CAST(1 + $entrada / 100 AS DECIMAL(6,4));")
      regla_propia "$cod" CONTADO "$factor" NULL "$hoy" "$abierta" >/dev/null
      igual "costo $costo con $entrada % da $esperado" "$esperado" "$(precio "$cod")"
    else
      regla_propia "$cod" CONTADO NULL "$entrada" "$hoy" "$abierta" >/dev/null
      igual "costo $costo con precio $entrada da $esperado %" "$esperado" "$(campo "$cod" margen_contado)"
    fi
  done < <(tr -d '\r' < "$CASOS")
  [[ "$fila" -gt 0 ]] || falla "la tabla de casos está vacía"
fi

echo
if [[ "$fallos" -eq 0 ]]; then
  echo "TODO EN VERDE"
  exit 0
fi
echo "$fallos comprobación(es) fallaron"
exit 1

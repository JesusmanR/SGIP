#!/usr/bin/env bash
# Comprueba la configuración tributaria por empresa que introduce la V12,
# y que los cuatro defectos de aislamiento que corrige quedaron cerrados.
#
# Se ejecuta sobre una base con la cadena V1 a V12 aplicada y los datos
# de demostración cargados. Los argumentos se pasan tal cual al cliente
# mysql:
#
#   bash verificar_tributacion.sh -u root -p
#   bash verificar_tributacion.sh --socket=/tmp/my.sock
#
# Deja en la base una empresa de prueba con el NIT 900000000-2. Úsalo en
# una base de desarrollo, no en una con datos reales.
set -uo pipefail

CONEXION=("$@")
ejecutar () { mysql "${CONEXION[@]}" sgip -e "$1" 2>&1; }
consulta () { mysql "${CONEXION[@]}" -N -B sgip -e "$1" 2>/dev/null; }

fallos=0
ok ()    { echo "  ok    · $1"; }
falla () { echo "  FALLA · $1"; fallos=$((fallos + 1)); }
igual () { # $1 descripción, $2 esperado, $3 obtenido
  [[ "$2" == "$3" ]] && ok "$1" || falla "$1 — esperaba '$2' y obtuve '$3'"
}

echo "── preparando una empresa sin configurar ──"
ejecutar "DELETE FROM reglas_iva    WHERE empresa_id > 1;"          >/dev/null
ejecutar "DELETE FROM productos     WHERE sku = 'SKU-TRIB';"        >/dev/null
ejecutar "DELETE FROM categorias    WHERE nombre = 'Panaderia';"    >/dev/null
ejecutar "DELETE FROM proveedores   WHERE nit = '800000000-3';"     >/dev/null
ejecutar "DELETE FROM empresa_config WHERE empresa_id IN
            (SELECT empresa_id FROM empresas WHERE nit = '900000000-2');" >/dev/null
ejecutar "DELETE FROM empresas      WHERE nit = '900000000-2';"     >/dev/null

ejecutar "INSERT INTO empresas (nit, razon_social, estado)
          VALUES ('900000000-2','Panaderia de prueba','ACTIVA');" >/dev/null
ID=$(consulta "SELECT empresa_id FROM empresas WHERE nit='900000000-2';")
[[ -z "$ID" ]] && { echo "No se pudo crear la empresa. ¿Está aplicada la V10?"; exit 1; }
ejecutar "INSERT INTO empresa_config (empresa_id) VALUES ($ID);" >/dev/null
ejecutar "INSERT INTO categorias (empresa_id, nombre, activa)
          VALUES ($ID,'Panaderia',TRUE);" >/dev/null
ejecutar "INSERT INTO proveedores (empresa_id, razon_social, nit, plazo_dias, origen_lista, activo)
          VALUES ($ID,'Molino de prueba','800000000-3',15,'NINGUNO',TRUE);" >/dev/null
ejecutar "INSERT INTO productos (empresa_id, categoria_id, proveedor_id, codigo, sku,
                                 nombre, presentacion, costo, existencia, existencia_minima, activo)
          SELECT $ID,
                 (SELECT categoria_id FROM categorias  WHERE empresa_id=$ID LIMIT 1),
                 (SELECT proveedor_id FROM proveedores WHERE empresa_id=$ID LIMIT 1),
                 'PAN-001','SKU-TRIB','Pan tajado','500 g',2500,40,5,TRUE;" >/dev/null
echo "  empresa de prueba: $ID"

echo
echo "── 1. una empresa sin configurar no puede calcular impuesto ──"
# Sin reglas_iva propias no hay regla aplicable. La vista lo dice en una
# bandera en lugar de devolver un total vacío sin explicación.
v=$(consulta "SELECT tributacion_sin_configurar FROM v_precios_producto WHERE sku='SKU-TRIB';")
igual "el producto sale marcado como sin configurar" "1" "$v"

echo
echo "── 2. la regla de IVA de una empresa no alcanza a otra ──"
# Este era el defecto: reglas_iva no tenía empresa_id, y su regla general
# era una sola para toda la plataforma.
ejecutar "UPDATE empresa_config SET responsable_iva=TRUE WHERE empresa_id=$ID;" >/dev/null
ejecutar "INSERT INTO reglas_iva (empresa_id, categoria_id, producto_id, tipo_impuesto_id, vigente_desde)
          SELECT $ID, NULL, NULL, tipo_impuesto_id, '2026-01-01'
            FROM tipos_impuesto WHERE codigo='EXCLUIDO';" >/dev/null
prop=$(consulta "SELECT codigo_impuesto FROM v_precios_producto WHERE sku='SKU-TRIB';")
igual "la panaderia usa su propia regla" "EXCLUIDO" "$prop"
ajena=$(consulta "SELECT codigo_impuesto FROM v_precios_producto
                   WHERE empresa_id=1 AND codigo='SRV-001';")
[[ "$ajena" != "EXCLUIDO" ]] \
  && ok "la empresa 1 conserva la suya ($ajena)" \
  || falla "la regla de la panaderia alcanzo a la empresa 1"

echo
echo "── 3. el margen de una empresa no alcanza a otra ──"
# v_regla_margen no filtraba por empresa: la regla general de la empresa 1
# fijaba el precio de los productos de la empresa 2.
sin=$(consulta "SELECT COALESCE(precio_contado,'nulo') FROM v_precios_producto WHERE sku='SKU-TRIB';")
igual "sin regla de margen propia no hay precio" "nulo" "$sin"
ejecutar "INSERT INTO reglas_margen (empresa_id, categoria_id, proveedor_id, forma_pago,
                                     forma_calculo, factor, vigente_desde)
          VALUES ($ID, NULL, NULL, 'CONTADO', 'SOBRE_COSTO', 1.5000, '2026-01-01');" >/dev/null
con=$(consulta "SELECT precio_contado FROM v_precios_producto WHERE sku='SKU-TRIB';")
igual "con su propia regla al 1,5 el pan de 2500 vale 3750" "3750" "$con"
e1=$(consulta "SELECT precio_contado FROM v_precios_producto WHERE empresa_id=1 AND codigo='PC-001';")
igual "el portatil de la empresa 1 sigue en su precio" "2520000" "$e1"

echo
echo "── 4. quien no es responsable de IVA no causa impuesto ──"
ejecutar "UPDATE empresa_config SET responsable_iva=FALSE WHERE empresa_id=$ID;" >/dev/null
t=$(consulta "SELECT tratamiento_iva FROM v_precios_producto WHERE sku='SKU-TRIB';")
tar=$(consulta "SELECT tarifa_contado FROM v_precios_producto WHERE sku='SKU-TRIB';")
igual "el tratamiento es NO_GRAVADO" "NO_GRAVADO" "$t"
igual "la tarifa es cero" "0.0000" "$tar"
ejecutar "UPDATE empresa_config SET responsable_iva=TRUE WHERE empresa_id=$ID;" >/dev/null

echo
echo "── 5. la exclusion por umbral solo opera si la empresa la aplica ──"
# Para quien no la aplica, ese tipo se comporta como gravado a su tarifa.
ejecutar "UPDATE empresa_config SET aplica_exclusion_umbral=FALSE WHERE empresa_id=1;" >/dev/null
t=$(consulta "SELECT tratamiento_iva FROM v_precios_producto WHERE empresa_id=1 AND codigo='PC-001';")
igual "sin aplicarla, el portatil queda GRAVADO" "GRAVADO" "$t"
ejecutar "UPDATE empresa_config SET aplica_exclusion_umbral=TRUE WHERE empresa_id=1;" >/dev/null
t=$(consulta "SELECT tratamiento_iva FROM v_precios_producto WHERE empresa_id=1 AND codigo='PC-001';")
igual "aplicandola, vuelve a EXCLUIDO_HASTA_UMBRAL" "EXCLUIDO_HASTA_UMBRAL" "$t"

echo
echo "── 6. dos empresas pueden emitir ambas su venta V-0001 ──"
ejecutar "DELETE FROM ventas WHERE consecutivo='V-0001';" >/dev/null
ejecutar "INSERT INTO ventas (empresa_id, consecutivo, cliente_id, usuario_id, forma_pago)
          SELECT 1,'V-0001',
                 (SELECT cliente_id FROM clientes WHERE empresa_id=1 LIMIT 1),
                 (SELECT usuario_id FROM usuarios WHERE empresa_id=1 LIMIT 1),
                 'CONTADO';" >/dev/null
salida=$(ejecutar "INSERT INTO ventas (empresa_id, consecutivo, cliente_id, usuario_id, forma_pago)
          SELECT 1,'V-0001',
                 (SELECT cliente_id FROM clientes WHERE empresa_id=1 LIMIT 1),
                 (SELECT usuario_id FROM usuarios WHERE empresa_id=1 LIMIT 1),
                 'CONTADO';")
[[ "$salida" == *"ERROR 1062"* ]] \
  && ok "el consecutivo repetido en la MISMA empresa sigue rechazado" \
  || falla "se admitio el consecutivo repetido dentro de una empresa"
# El único es (empresa_id, consecutivo), de modo que otra empresa sí puede
# usar V-0001. Se comprueba sobre el índice, porque la panaderia no tiene
# usuarios y la venta exige uno.
idx=$(consulta "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index)
                FROM information_schema.statistics
                WHERE table_schema='sgip' AND table_name='ventas'
                  AND index_name='uq_ventas_consecutivo';")
igual "el unico es por empresa" "empresa_id,consecutivo" "$idx"

echo
echo "── 7. un producto admite una sola imagen principal ──"
PID=$(consulta "SELECT producto_id FROM productos WHERE sku='SKU-TRIB';")
ejecutar "DELETE FROM producto_imagenes WHERE producto_id=$PID;" >/dev/null
ejecutar "INSERT INTO producto_imagenes (empresa_id, producto_id, ruta, orden, principal)
          VALUES ($ID,$PID,'pan-1.jpg',1,TRUE),($ID,$PID,'pan-2.jpg',2,FALSE);" >/dev/null
salida=$(ejecutar "INSERT INTO producto_imagenes (empresa_id, producto_id, ruta, orden, principal)
          VALUES ($ID,$PID,'pan-3.jpg',3,TRUE);")
[[ "$salida" == *"ERROR 1062"* ]] \
  && ok "la segunda principal fue rechazada" \
  || falla "se admitio una segunda imagen principal: ${salida:-(entro)}"
salida=$(ejecutar "INSERT INTO producto_imagenes (empresa_id, producto_id, ruta, orden, principal)
          VALUES ($ID,$PID,'pan-6.jpg',6,FALSE);")
[[ "$salida" == *"ERROR"* ]] \
  && ok "la sexta imagen fue rechazada (el RF-63 admite cinco)" \
  || falla "se admitio una sexta imagen"
img=$(consulta "SELECT imagen_principal FROM v_precios_producto WHERE sku='SKU-TRIB';")
igual "la vista expone la imagen principal" "pan-1.jpg" "$img"

echo
echo "── 8. el neto a recibir es el total menos la retencion ──"
VID=$(consulta "SELECT venta_id FROM ventas WHERE consecutivo='V-0001' AND empresa_id=1 LIMIT 1;")
ejecutar "UPDATE ventas SET subtotal=1000000, iva=190000, retencion_total=25000 WHERE venta_id=$VID;" >/dev/null
read -r tot net <<< "$(consulta "SELECT total, neto_a_recibir FROM ventas WHERE venta_id=$VID;")"
igual "total 1.190.000" "1190000.00" "$tot"
igual "neto 1.165.000" "1165000.00" "$net"
salida=$(ejecutar "UPDATE ventas SET retencion_total=-1 WHERE venta_id=$VID;")
[[ "$salida" == *"ERROR"* ]] \
  && ok "una retencion negativa queda rechazada" \
  || falla "se admitio una retencion negativa"

echo
echo "── 9. las seis vistas exponen empresa_id ──"
n=$(consulta "SELECT COUNT(*) FROM information_schema.views v
              WHERE v.table_schema='sgip'
                AND EXISTS (SELECT 1 FROM information_schema.columns c
                             WHERE c.table_schema='sgip' AND c.table_name=v.table_name
                               AND c.column_name='empresa_id');")
igual "vistas con empresa_id" "6" "$n"

echo
if [[ "$fallos" -eq 0 ]]; then
  echo "TODO EN VERDE"
  exit 0
fi
echo "$fallos comprobación(es) fallaron"
exit 1

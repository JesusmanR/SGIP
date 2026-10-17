#!/usr/bin/env bash
# Comprueba que el aislamiento entre empresas se cumple en el esquema, no
# solo en el código. Se ejecuta sobre una base con la cadena V1 a V11
# aplicada y los datos de demostración cargados.
#
# Los argumentos se pasan tal cual al cliente mysql:
#
#   bash verificar_aislamiento.sh -u root -p
#   bash verificar_aislamiento.sh -h 127.0.0.1 -u root -psecreto
#   bash verificar_aislamiento.sh --socket=/tmp/my.sock
#
# Deja en la base una empresa de prueba con el NIT 900000000-1 y sus
# filas. Úsalo en una base de desarrollo, no en una con datos reales.
set -uo pipefail

CONEXION=("$@")

# El cliente de MySQL escribe este aviso en la salida de error cuando la
# contraseña viaja en la línea de comandos, que es como lo invoca el flujo
# de integración continua. Sin filtrarlo, ensucia toda captura: una
# comprobación que espera salida vacía lo lee como error y falla aunque la
# sentencia haya funcionado. MariaDB no emite el aviso, de modo que esto
# solo se manifiesta contra MySQL.
sin_aviso () { grep -v "Using a password on the command line interface" || true; }

ejecutar () { mysql "${CONEXION[@]}" sgip -e "$1" 2>&1 | sin_aviso; }
consulta () { mysql "${CONEXION[@]}" -N -B sgip -e "$1" 2>/dev/null; }

fallos=0
ok ()    { echo "  ok    · $1"; }
falla () { echo "  FALLA · $1"; fallos=$((fallos + 1)); }

# Una sentencia que DEBE ser rechazada por una clave foránea concreta.
# Se verifica el código 1452 y el nombre de la restricción: si falla por
# otra razón, la prueba no estaría probando lo que dice probar.
rechaza () {  # $1 = descripción, $2 = restricción esperada, $3 = SQL
  local salida; salida=$(ejecutar "$3")
  if [[ "$salida" != *"ERROR 1452"* ]]; then
    falla "$1 — se esperaba ERROR 1452 y se obtuvo: ${salida:-(ninguno, la fila entró)}"
  elif [[ "$salida" != *"$2"* ]]; then
    falla "$1 — rechazada, pero por otra restricción: $salida"
  else
    ok "$1"
  fi
}

echo "── preparando una segunda empresa ──"
ejecutar "DELETE FROM productos  WHERE sku IN ('SKU-OK','SKU-FUGA');" >/dev/null
ejecutar "DELETE FROM ventas     WHERE consecutivo LIKE 'V-AISL-%';"  >/dev/null
ejecutar "DELETE FROM clientes    WHERE nit='1234567890';"            >/dev/null
ejecutar "DELETE FROM categorias  WHERE nombre='Granos de prueba';"   >/dev/null
ejecutar "DELETE FROM proveedores WHERE nit='800000000-2';"           >/dev/null
ejecutar "DELETE FROM empresas    WHERE nit='900000000-1';"           >/dev/null

ejecutar "INSERT INTO empresas (nit, razon_social, estado)
          VALUES ('900000000-1','Tienda de prueba S.A.S.','ACTIVA');" >/dev/null
ID=$(consulta "SELECT empresa_id FROM empresas WHERE nit='900000000-1';")
if [[ -z "$ID" ]]; then
  echo "No se pudo crear la empresa de prueba. ¿Está aplicada la V10?"
  exit 1
fi
echo "  empresa de prueba: $ID"

ejecutar "INSERT INTO categorias (empresa_id, nombre, activa)
          VALUES ($ID,'Granos de prueba',TRUE);" >/dev/null
ejecutar "INSERT INTO proveedores (empresa_id, razon_social, nit, plazo_dias,
                                   origen_lista, activo)
          VALUES ($ID,'Distribuidora de prueba','800000000-2',30,'NINGUNO',TRUE);" >/dev/null
ejecutar "INSERT INTO clientes (empresa_id, razon_social, tipo_documento, nit, activo)
          VALUES ($ID,'Cliente de prueba','CC','1234567890',TRUE);" >/dev/null

PROPIA_CAT="(SELECT categoria_id FROM categorias  WHERE empresa_id=$ID LIMIT 1)"
PROPIO_PRV="(SELECT proveedor_id FROM proveedores WHERE empresa_id=$ID LIMIT 1)"
AJENA_CAT="(SELECT categoria_id FROM categorias  WHERE empresa_id=1 LIMIT 1)"
AJENO_USR="(SELECT usuario_id   FROM usuarios    WHERE empresa_id=1 LIMIT 1)"
AJENO_CLI="(SELECT cliente_id   FROM clientes    WHERE empresa_id=1 LIMIT 1)"

echo
echo "── lo que debe funcionar ──"

salida=$(ejecutar "INSERT INTO productos (empresa_id, categoria_id, proveedor_id,
                                          codigo, sku, nombre, costo, existencia,
                                          existencia_minima, activo)
                   SELECT $ID, $PROPIA_CAT, $PROPIO_PRV, 'PC-001', 'SKU-OK',
                          'Arroz Diana 500 g', 2000, 10, 2, TRUE;")
[[ -z "$salida" ]] && ok "producto con categoría y proveedor propios" \
                   || falla "producto con categoría y proveedor propios: $salida"

# El código de producto es único por empresa, no en toda la plataforma:
# dos tiendas distintas pueden tener su propio PC-001.
n=$(consulta "SELECT COUNT(*) FROM productos WHERE codigo='PC-001';")
[[ "${n:-0}" -ge 2 ]] && ok "el mismo código en dos empresas ($n filas)" \
                      || falla "el mismo código en dos empresas: solo $n"

# La existencia negativa es un estado válido: es la deuda de reposición de
# una venta autorizada sin existencia.
ejecutar "UPDATE productos SET existencia = -3 WHERE sku='SKU-OK';" >/dev/null
estado=$(consulta "SELECT estado_stock FROM productos WHERE sku='SKU-OK';")
[[ "$estado" == "AGOTADO" ]] && ok "existencia -3 clasifica como AGOTADO" \
                             || falla "existencia -3 quedó como '$estado'"

echo
echo "── lo que debe quedar rechazado ──"

rechaza "producto con categoría de otra empresa" "fk_productos_categoria_emp" \
  "INSERT INTO productos (empresa_id, categoria_id, proveedor_id, codigo, sku,
                          nombre, costo, existencia, existencia_minima, activo)
   SELECT $ID, $AJENA_CAT, $PROPIO_PRV, 'FUGA-001', 'SKU-FUGA',
          'Producto cruzado', 1000, 1, 1, TRUE;"

rechaza "venta cuyo vendedor es de otra empresa" "fk_ventas_usuario_emp" \
  "INSERT INTO ventas (empresa_id, consecutivo, cliente_id, usuario_id, forma_pago)
   SELECT $ID, 'V-AISL-1',
          (SELECT cliente_id FROM clientes WHERE empresa_id=$ID LIMIT 1),
          $AJENO_USR, 'CONTADO';"

# Aquí la venta es de la empresa 1 —con su propio vendedor, que sí
# existe— y lo único ajeno es el cliente, que pertenece a la empresa de
# prueba. Así la única causa posible de rechazo es la clave del cliente.
rechaza "venta cuyo cliente es de otra empresa" "fk_ventas_cliente_emp" \
  "INSERT INTO ventas (empresa_id, consecutivo, cliente_id, usuario_id, forma_pago)
   SELECT 1, 'V-AISL-2',
          (SELECT cliente_id FROM clientes WHERE empresa_id=$ID LIMIT 1),
          (SELECT usuario_id FROM usuarios WHERE empresa_id=1 LIMIT 1),
          'CONTADO';"

# La clave de idempotencia es lo que impide que una operación registrada
# sin conexión se aplique dos veces al reintentar el envío.
echo
echo "── idempotencia de la cola de sincronización ──"
CLAVE='aisl-0000-0000-0000-000000000001'
ejecutar "DELETE FROM cola_sincronizacion WHERE clave_idempotencia='$CLAVE';" >/dev/null
encolar () {
  ejecutar "INSERT INTO cola_sincronizacion
              (empresa_id, usuario_id, clave_idempotencia, tipo, carga,
               registrada_dispositivo)
            SELECT u.empresa_id, u.usuario_id, '$CLAVE', 'VENTA', '{}', NOW()
              FROM usuarios u WHERE u.empresa_id IS NOT NULL LIMIT 1;"
}
primera=$(encolar)
[[ -z "$primera" ]] && ok "primera operación encolada" \
                    || falla "primera operación encolada: $primera"
segunda=$(encolar)
[[ "$segunda" == *"ERROR 1062"* ]] && ok "la repetición fue rechazada (1062)" \
                                  || falla "la repetición no fue rechazada: ${segunda:-(entró)}"


# Las consultas que resuelven un dato por una clave ajena no deben
# apoyarse en que esa clave sea única fuera de su empresa. Hoy lo es
# —producto_id es único en toda la tabla— y por eso la vista de precios
# acertaba aunque su subconsulta de imagen no filtrara por empresa. Esta
# comprobación no verifica esa suerte: verifica que la vista filtre.
#
# Para ello construye la situación que hoy el esquema impide, que es la
# única forma de distinguir una consulta correcta de una afortunada: una
# fila de imagen de otra empresa que comparte producto_id. Se desactivan
# las claves foráneas para insertarla y se vuelven a activar enseguida.
# La fila se retira al terminar.
echo
echo "── la vista de precios no cruza empresas ──"
PROD_AJENO=$(consulta "SELECT p.producto_id FROM productos p
                        WHERE p.empresa_id = 1 AND p.activo = 1
                          AND NOT EXISTS (SELECT 1 FROM producto_imagenes i
                                           WHERE i.producto_id = p.producto_id
                                             AND i.principal = TRUE)
                        LIMIT 1;")
if [[ -z "$PROD_AJENO" ]]; then
  falla "no se encontró un producto de la empresa 1 sin imagen principal"
else
  ejecutar "DELETE FROM producto_imagenes WHERE ruta='empresas/zz/FUGA.jpg';" >/dev/null
  ejecutar "SET FOREIGN_KEY_CHECKS = 0;
            INSERT INTO producto_imagenes
                   (empresa_id, producto_id, ruta, orden, principal)
            VALUES ($ID, $PROD_AJENO, 'empresas/zz/FUGA.jpg', 1, TRUE);
            SET FOREIGN_KEY_CHECKS = 1;" >/dev/null
  fuga=$(consulta "SELECT IFNULL(imagen_principal, '')
                     FROM v_precios_producto
                    WHERE empresa_id = 1 AND producto_id = $PROD_AJENO;")
  if [[ "$fuga" == "empresas/zz/FUGA.jpg" ]]; then
    falla "la vista devolvió la imagen de otra empresa: $fuga"
  elif [[ -n "$fuga" ]]; then
    falla "la vista devolvió una imagen inesperada: $fuga"
  else
    ok "la imagen de otra empresa no aparece en la vista"
  fi
  ejecutar "DELETE FROM producto_imagenes WHERE ruta='empresas/zz/FUGA.jpg';" >/dev/null
fi

echo
if [[ "$fallos" -eq 0 ]]; then
  echo "TODO EN VERDE"
  exit 0
fi
echo "$fallos comprobación(es) fallaron"
exit 1

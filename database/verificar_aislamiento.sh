#!/usr/bin/env bash
# Comprueba que el aislamiento entre empresas se cumple en el esquema, no
# solo en el código. Se ejecuta sobre una base con la cadena V1 a V24
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

# El cliente devuelve el eco de la sentencia junto al error. Para el
# mensaje de una comprobación fallida sirve la línea del error y nada más:
# el resto es ruido que esconde lo único que hay que leer.
solo_error () { grep -m1 "^ERROR" <<<"$1" || echo "${1:-(sin salida)}"; }

# Un conteo que distingue «cero filas» de «la consulta no se pudo
# ejecutar». Sin esta diferencia, una columna que falta se lee como un
# conteo vacío y la comprobación falla sin decir por qué.
conteo () {
  local n; n=$(consulta "$1")
  if [[ -z "$n" ]]; then echo "sin-dato"; else echo "$n"; fi
}

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


# La venta sin existencia disponible (RF-42) necesita dos permisos que
# viven en sitios distintos: la V9 lo dio en productos y la V18 en
# movimientos_inventario. Con solo el primero, el esquema deja la
# existencia en negativo pero prohíbe anotar por qué, y la transacción de
# venta entera se aborta. Esta comprobación registra el movimiento que
# deja el saldo en negativo: falla con las restricciones de la V2 y pasa
# con las de la V18.
echo
echo "── el kárdex admite la existencia negativa que el RF-42 autoriza ──"
PROD_NEG=$(consulta "SELECT producto_id FROM productos
                      WHERE empresa_id = $ID AND sku = 'SKU-OK' LIMIT 1;")
if [[ -z "$PROD_NEG" ]]; then
  falla "no se encontró el producto de prueba SKU-OK"
else
  ejecutar "DELETE FROM movimientos_inventario WHERE motivo LIKE 'AISL-%';" >/dev/null
  ejecutar "DELETE FROM usuarios WHERE correo='aislamiento@prueba.local';" >/dev/null
  ejecutar "INSERT INTO usuarios (empresa_id, rol_id, nombre, correo, clave_hash,
                                   activo, bloqueado, cambio_clave_req)
            SELECT $ID, r.rol_id, 'Operario de prueba', 'aislamiento@prueba.local',
                   'x', TRUE, FALSE, FALSE
              FROM roles r WHERE r.nombre = 'ADMINISTRADOR';" >/dev/null
  USU_NEG=$(consulta "SELECT usuario_id FROM usuarios
                       WHERE correo='aislamiento@prueba.local';")

  # El producto queda debiendo tres unidades y el movimiento lo explica.
  ejecutar "UPDATE productos SET existencia = -3
             WHERE empresa_id = $ID AND producto_id = $PROD_NEG;" >/dev/null
  neg=$(consulta "SELECT existencia FROM productos
                   WHERE empresa_id = $ID AND producto_id = $PROD_NEG;")
  [[ "$neg" == "-3" ]] && ok "productos admite existencia -3 (V9)" \
                       || falla "productos no admitió la existencia negativa: ${neg:-(sin dato)}"

  salida=$(ejecutar "INSERT INTO movimientos_inventario
                            (empresa_id, producto_id, usuario_id, tipo, cantidad,
                             existencia_anterior, existencia_posterior, motivo)
                     VALUES ($ID, $PROD_NEG, $USU_NEG, 'EGRESO', 3, 0, -3,
                             'AISL-venta sin existencia');")
  [[ -z "$salida" ]] && ok "el movimiento con saldo posterior -3 entra (V18)" \
                     || falla "el movimiento en negativo fue rechazado: $(solo_error "$salida")"

  # Y la venta siguiente parte de un saldo ya negativo.
  salida=$(ejecutar "INSERT INTO movimientos_inventario
                            (empresa_id, producto_id, usuario_id, tipo, cantidad,
                             existencia_anterior, existencia_posterior, motivo)
                     VALUES ($ID, $PROD_NEG, $USU_NEG, 'EGRESO', 1, -3, -4,
                             'AISL-segunda sin existencia');")
  [[ -z "$salida" ]] && ok "el movimiento con saldo anterior -3 entra (V18)" \
                     || falla "el movimiento desde saldo negativo fue rechazado: $(solo_error "$salida")"

  # Lo que la V18 no debía aflojar. Un piso de -9999 que no rechazara
  # -10000 no sería un piso, y un movimiento de cero unidades seguiría
  # siendo una fila sin significado.
  salida=$(ejecutar "INSERT INTO movimientos_inventario
                            (empresa_id, producto_id, usuario_id, tipo, cantidad,
                             existencia_anterior, existencia_posterior, motivo)
                     VALUES ($ID, $PROD_NEG, $USU_NEG, 'EGRESO', 1, -9999, -10000,
                             'AISL-bajo el piso');")
  [[ "$salida" == *"ck_movimientos_posterior"* ]] \
    && ok "el piso de -9999 sigue cerrado" \
    || falla "el piso de -9999 no rechazó -10000 por ck_movimientos_posterior: $(solo_error "${salida:-(entró)}")"

  salida=$(ejecutar "INSERT INTO movimientos_inventario
                            (empresa_id, producto_id, usuario_id, tipo, cantidad,
                             existencia_anterior, existencia_posterior, motivo)
                     VALUES ($ID, $PROD_NEG, $USU_NEG, 'EGRESO', 0, 5, 5,
                             'AISL-cantidad cero');")
  [[ "$salida" == *"ck_movimientos_cantidad"* ]] \
    && ok "el movimiento de cero unidades sigue rechazado" \
    || falla "el movimiento de cero unidades no fue rechazado: $(solo_error "${salida:-(entró)}")"

  # Con el producto todavía en -3, la valorización no debe restar. Antes
  # de la V19 esta referencia aportaba -6.000 al valor de la bodega.
  echo
  echo "── la valorización no resta lo que se debe reponer ──"
  fila=$(consulta "SELECT valor_al_costo, unidades_pendientes, valor_pendiente_al_costo
                     FROM v_inventario_valorizado
                    WHERE empresa_id = $ID AND producto_id = $PROD_NEG;")
  read -r v_costo v_pend v_valpend <<<"$fila"
  [[ "${v_costo%%.*}" == "0" ]] \
    && ok "la referencia en negativo valoriza en cero" \
    || falla "la referencia en negativo valoriza en ${v_costo:-(sin dato)}"
  [[ "${v_pend:-0}" == "3" ]] \
    && ok "declara 3 unidades pendientes de reponer" \
    || falla "unidades pendientes: ${v_pend:-(sin dato)} (se esperaban 3)"
  [[ "${v_valpend%%.*}" == "6000" ]] \
    && ok "declara 6.000 pendientes al costo" \
    || falla "valor pendiente: ${v_valpend:-(sin dato)} (se esperaban 6000)"

  n=$(conteo "SELECT COUNT(*) FROM v_inventario_valorizado WHERE valor_al_costo < 0;")
  [[ "$n" == "0" ]] && ok "ninguna referencia valoriza en negativo" \
                    || falla "referencias que valorizan en negativo: $n"
  n=$(conteo "SELECT COUNT(*) FROM v_rotacion_inventario WHERE capital_retenido < 0;")
  [[ "$n" == "0" ]] && ok "ningún capital retenido en negativo" \
                    || falla "filas con capital retenido negativo: $n"

  ejecutar "DELETE FROM movimientos_inventario WHERE motivo LIKE 'AISL-%';" >/dev/null
  # El producto de prueba se deja en cero y no en diez.
  #
  # Este guion comprueba el esquema, de modo que escribe la existencia
  # con UPDATE directo, sin movimiento que la explique. Eso era inocuo
  # hasta la V23: desde ella, la invariante del RF-49 exige que la suma
  # de los movimientos de cada referencia iguale su existencia, y una
  # fila con diez unidades y cero movimientos la incumple. En cero
  # cuadra sin necesidad de inventarle un kárdex a un producto que solo
  # existe para probar claves foráneas.
  #
  # Sin esto, verificar_kardex.sh falla después de correr este guion, y
  # el síntoma es desconcertante: señala un PC-001 con diez unidades sin
  # respaldo, que no es el PC-001 de la demostración sino el homónimo de
  # la empresa de prueba —precisamente lo que la comprobación «el mismo
  # código en dos empresas» deja montado.
  ejecutar "UPDATE productos SET existencia = 0
             WHERE empresa_id = $ID AND producto_id = $PROD_NEG;" >/dev/null
  ejecutar "DELETE FROM usuarios WHERE correo='aislamiento@prueba.local';" >/dev/null
fi


# El total a crédito de la V20 no se comprueba contra una cifra fija: el
# umbral del Art. 424 se mide en UVT y la UVT cambia cada año, de modo
# que una prueba con un número escrito a mano caducaría sola. Lo que no
# cambia es la relación entre las tres columnas.
echo
echo "── la vista de precios cierra la modalidad a crédito ──"
n=$(conteo "SELECT COUNT(*) FROM v_precios_producto
             WHERE precio_credito IS NOT NULL
               AND total_credito <> ROUND(precio_credito * (1 + tarifa_credito), 4);")
[[ "$n" == "0" ]] && ok "total_credito concuerda con su precio y su tarifa" \
                  || falla "filas con total_credito inconsistente: $n (sin-dato = la columna no existe; ¿falta la V20?)"

# El caso que el margen provoca por sí solo: excluido de contado y
# gravado a crédito. Donde la bandera se enciende, las dos tarifas deben
# diferir; donde no, no puede haber una diferencia escondida.
n=$(conteo "SELECT COUNT(*) FROM v_precios_producto
             WHERE cruza_umbral_en_credito = 1
               AND NOT (tarifa_contado = 0 AND tarifa_credito > 0);")
[[ "$n" == "0" ]] && ok "la bandera de cruce solo marca cruces reales" \
                  || falla "filas marcadas sin cruzar el umbral: $n"
n=$(conteo "SELECT COUNT(*) FROM v_precios_producto
             WHERE cruza_umbral_en_credito = 0
               AND tratamiento_iva = 'EXCLUIDO_HASTA_UMBRAL'
               AND tarifa_contado = 0 AND tarifa_credito > 0;")
[[ "$n" == "0" ]] && ok "no hay cruces sin marcar" \
                  || falla "filas que cruzan el umbral sin estar marcadas: $n"


# El camino de las compras era el que la V11 dejó sin cerrar. Tres claves
# foráneas apuntaban a su destino sin empresa, y la primera es la que
# muerde: una orden de un comercio aceptaba una línea con el producto de
# otro, de modo que recibirla habría registrado un INGRESO sobre el
# inventario ajeno. No una fuga de lectura: una escritura.
#
# Las tres se reprodujeron en una base real antes de escribir la V24, y
# estas comprobaciones son las que impiden que vuelvan.
echo
echo "── las compras no cruzan empresas (V24) ──"
PROD_PROPIO=$(consulta "SELECT producto_id FROM productos WHERE empresa_id = $ID LIMIT 1;")
PROD_DE_OTRA=$(consulta "SELECT producto_id FROM productos WHERE empresa_id = 1 LIMIT 1;")
PRV_PROPIO=$(consulta "SELECT proveedor_id FROM proveedores WHERE empresa_id = $ID LIMIT 1;")
USU_DE_OTRA=$(consulta "SELECT usuario_id FROM usuarios WHERE empresa_id = 1 LIMIT 1;")

ejecutar "DELETE FROM movimientos_inventario WHERE documento = 'AISL-OC';" >/dev/null
ejecutar "DELETE FROM recepciones        WHERE observacion = 'AISL-OC';"   >/dev/null
ejecutar "DELETE FROM orden_compra_items WHERE orden_id IN
            (SELECT orden_id FROM ordenes_compra WHERE consecutivo = 'AISL-OC');" >/dev/null
ejecutar "DELETE FROM ordenes_compra     WHERE consecutivo = 'AISL-OC';"   >/dev/null

if [[ -z "$PROD_PROPIO" || -z "$PROD_DE_OTRA" || -z "$PRV_PROPIO" || -z "$USU_DE_OTRA" ]]; then
  falla "faltan datos para probar las compras (producto, proveedor o usuario)"
else
  # La orden la firma el administrador de arranque de la empresa de
  # prueba, que el bloque del kárdex ya creó y borró; aquí hace falta uno
  # propio otra vez.
  ejecutar "DELETE FROM usuarios WHERE correo='compras@prueba.local';" >/dev/null
  ejecutar "INSERT INTO usuarios (empresa_id, rol_id, nombre, correo, clave_hash,
                                   activo, bloqueado, cambio_clave_req)
            SELECT $ID, r.rol_id, 'Compras de prueba', 'compras@prueba.local',
                   'x', TRUE, FALSE, FALSE
              FROM roles r WHERE r.nombre = 'ADMINISTRADOR';" >/dev/null
  USU_PROPIO=$(consulta "SELECT usuario_id FROM usuarios WHERE correo='compras@prueba.local';")

  ejecutar "INSERT INTO ordenes_compra (empresa_id, proveedor_id, usuario_id,
                                         consecutivo, estado)
            VALUES ($ID, $PRV_PROPIO, $USU_PROPIO, 'AISL-OC', 'ABIERTA');" >/dev/null
  ORDEN=$(consulta "SELECT orden_id FROM ordenes_compra WHERE consecutivo = 'AISL-OC';")

  if [[ -z "$ORDEN" ]]; then
    falla "no se pudo crear la orden de compra de prueba"
  else
    rechaza "una línea de orden con el producto de otra empresa" fk_oci_producto_emp \
      "INSERT INTO orden_compra_items (orden_id, empresa_id, producto_id,
                                        cantidad, costo_unitario)
       VALUES ($ORDEN, $ID, $PROD_DE_OTRA, 10, 1000.00);"

    rechaza "una recepción firmada por un usuario de otra empresa" fk_rec_usuario_emp \
      "INSERT INTO recepciones (orden_id, empresa_id, usuario_id, observacion)
       VALUES ($ORDEN, $ID, $USU_DE_OTRA, 'AISL-OC');"

    # Y lo propio tiene que seguir entrando, o la restricción estaría
    # cerrando más de lo que debe.
    salida=$(ejecutar "INSERT INTO orden_compra_items (orden_id, empresa_id, producto_id,
                                                        cantidad, costo_unitario)
                       VALUES ($ORDEN, $ID, $PROD_PROPIO, 10, 1000.00);")
    [[ -z "$salida" ]] && ok "la línea con producto propio entra" \
                       || falla "la línea con producto propio fue rechazada: $(solo_error "$salida")"

    salida=$(ejecutar "INSERT INTO recepciones (orden_id, empresa_id, usuario_id, observacion)
                       VALUES ($ORDEN, $ID, $USU_PROPIO, 'AISL-OC');")
    [[ -z "$salida" ]] && ok "la recepción firmada por un usuario propio entra" \
                       || falla "la recepción propia fue rechazada: $(solo_error "$salida")"

    RECEPCION=$(consulta "SELECT recepcion_id FROM recepciones
                           WHERE observacion = 'AISL-OC' LIMIT 1;")
    if [[ -n "$RECEPCION" ]]; then
      rechaza "un movimiento colgado de la recepción de otra empresa" fk_mov_recepcion_emp \
        "INSERT INTO movimientos_inventario
                (empresa_id, producto_id, usuario_id, recepcion_id, tipo, cantidad,
                 existencia_anterior, existencia_posterior, documento)
         VALUES (1, $PROD_DE_OTRA, $USU_DE_OTRA, $RECEPCION, 'INGRESO', 1, 0, 1, 'AISL-OC');"
    fi
  fi

  ejecutar "DELETE FROM movimientos_inventario WHERE documento = 'AISL-OC';" >/dev/null
  ejecutar "DELETE FROM recepciones        WHERE observacion = 'AISL-OC';"   >/dev/null
  ejecutar "DELETE FROM orden_compra_items WHERE orden_id = $ORDEN;"         >/dev/null
  ejecutar "DELETE FROM ordenes_compra     WHERE consecutivo = 'AISL-OC';"   >/dev/null
  ejecutar "DELETE FROM usuarios WHERE correo = 'compras@prueba.local';"     >/dev/null
fi

echo
if [[ "$fallos" -eq 0 ]]; then
  echo "TODO EN VERDE"
  exit 0
fi
echo "$fallos comprobación(es) fallaron"
exit 1

#!/usr/bin/env bash
# Comprueba las cinco lecturas del panel de indicadores (P-02) contra
# datos construidos a propósito para que cada una pueda fallar.
#
# Se ejecuta sobre una base con la cadena V1 a V31 aplicada y los datos
# de demostración cargados.
#
#   bash verificar_panel.sh -u root -p
#   bash verificar_panel.sh -h 127.0.0.1 -u root -psecreto
#
# Por qué construye su propio histórico de costos: los datos de
# demostración dejan `historico_costos` VACÍA. La consulta de cruces de
# umbral necesita un periodo anterior del que comparar, de modo que
# sobre la base de demostración tal cual devuelve cero filas. Una prueba
# que mirara «cero cruces» y lo diera por bueno estaría en verde sin
# haber ejecutado la parte que importa de la consulta. Aquí el periodo
# anterior se construye, y si la consulta devuelve cero la prueba falla.
#
# Deshace todo lo que crea. Los cinco verificadores de este directorio
# se ejecutan seguidos y el orden no debe decidir el resultado.
set -uo pipefail

CONEXION=("$@")

sin_aviso () { grep -v "Using a password on the command line interface" || true; }
ejecutar () { mysql "${CONEXION[@]}" sgip -e "$1" 2>&1 | sin_aviso; }
consulta () { mysql "${CONEXION[@]}" -N -B sgip -e "$1" 2>/dev/null; }

fallos=0
ok ()    { echo "  ok    · $1"; }
falla () { echo "  FALLA · $1"; fallos=$((fallos + 1)); }

igual () {  # $1 = qué, $2 = esperado, $3 = obtenido
  if [[ "$2" == "$3" ]]; then ok "$1 = $3"
  else falla "$1: se esperaba $2 y se obtuvo ${3:-(sin dato)}"; fi
}

# ── desmontaje, al empezar y al terminar ─────────────────────────────
limpiar () {
  local cargas="SELECT carga_id FROM cargas_precios WHERE nombre_archivo LIKE 'PAN-%'"
  ejecutar "DELETE FROM historico_costos      WHERE carga_id IN ($cargas);"     >/dev/null
  ejecutar "DELETE FROM detalle_carga_precios WHERE carga_id IN ($cargas);"     >/dev/null
  ejecutar "DELETE FROM cargas_precios        WHERE nombre_archivo LIKE 'PAN-%';" >/dev/null
  ejecutar "DELETE FROM historico_costos      WHERE vigente_desde = CURDATE() - INTERVAL 10 DAY;" >/dev/null
  ejecutar "DELETE FROM movimientos_inventario WHERE documento LIKE 'PAN-%';"   >/dev/null
  # La empresa vecina, de hija a madre.
  ejecutar "DELETE FROM productos      WHERE codigo LIKE 'PAN-%';"              >/dev/null
  ejecutar "DELETE FROM categorias     WHERE nombre LIKE 'PAN-%';"              >/dev/null
  ejecutar "DELETE FROM proveedores    WHERE nit LIKE 'PAN-%';"                 >/dev/null
  ejecutar "DELETE FROM empresa_config WHERE empresa_id IN
              (SELECT empresa_id FROM empresas WHERE nit LIKE 'PAN-%');"        >/dev/null
  ejecutar "DELETE FROM empresas       WHERE nit LIKE 'PAN-%';"                 >/dev/null
  # Los costos se devuelven a los de demostración para que el resto de
  # los verificadores encuentren la base como la dejó `aplicar.sh`.
  ejecutar "UPDATE productos SET costo = 2200000.00 WHERE empresa_id = 1 AND codigo = 'PC-002';" >/dev/null
  ejecutar "UPDATE productos SET costo = 2150000.00 WHERE empresa_id = 1 AND codigo = 'PC-003';" >/dev/null
  ejecutar "UPDATE productos SET costo =  900000.00 WHERE empresa_id = 1 AND codigo = 'CEL-001';" >/dev/null
  ejecutar "UPDATE productos SET costo = 1000000.00 WHERE empresa_id = 1 AND codigo = 'MON-001';" >/dev/null
  return 0
}
trap limpiar EXIT
limpiar

# Los datos de demostración son el punto de partida de todas las cifras
# esperadas de este guion. Si cambian, esto lo dice en lugar de fallar
# más abajo con un número que nadie sabría interpretar.
base=$(consulta "SELECT CONCAT_WS('|',
                   (SELECT COUNT(*) FROM productos WHERE empresa_id=1 AND activo=TRUE),
                   (SELECT costo FROM productos WHERE empresa_id=1 AND codigo='PC-003'),
                   (SELECT costo FROM productos WHERE empresa_id=1 AND codigo='PC-002'),
                   (SELECT ROUND(umbral_pesos,0) FROM v_precios_producto
                     WHERE empresa_id=1 AND codigo='PC-003'));")
if [[ "$base" != "7|2150000.00|2200000.00|2618700" ]]; then
  echo "Los datos de demostración no son los que este guion espera: $base"
  echo "Se esperaba 7|2150000.00|2200000.00|2618700. Revisa la V8."
  exit 1
fi
echo "datos de demostración en el punto esperado"


# ── 1. las cuatro cifras ─────────────────────────────────────────────
echo
echo "── 1. las cuatro cifras de la cabecera ──"

cifras=$(consulta "
  SELECT CONCAT_WS('|',
           CAST(SUM(p.activo = TRUE) AS SIGNED),
           CAST(SUM(p.activo = TRUE AND p.estado_stock = 'CRITICO') AS SIGNED),
           CAST(SUM(p.activo = TRUE AND p.estado_stock = 'AGOTADO') AS SIGNED),
           (SELECT CAST(COALESCE(SUM(i.valor_al_costo), 0) AS SIGNED)
              FROM v_inventario_valorizado i WHERE i.empresa_id = 1))
    FROM productos p WHERE p.empresa_id = 1;")

# El contraste no repite la consulta: cuenta con COUNT y un WHERE, que
# es la forma distinta de preguntar lo mismo. Si las dos coincidieran
# por estar escritas igual, no se habría comprobado nada.
contraste=$(consulta "
  SELECT CONCAT_WS('|',
           (SELECT COUNT(*) FROM productos WHERE empresa_id=1 AND activo=TRUE),
           (SELECT COUNT(*) FROM productos WHERE empresa_id=1 AND activo=TRUE
                             AND existencia > 0 AND existencia <= existencia_minima),
           (SELECT COUNT(*) FROM productos WHERE empresa_id=1 AND activo=TRUE
                             AND existencia <= 0),
           (SELECT CAST(SUM(GREATEST(existencia,0) * costo) AS SIGNED)
              FROM productos WHERE empresa_id=1 AND activo=TRUE));")
igual "activas|criticas|agotadas|capital" "$contraste" "$cifras"

# Y que las cifras sean de la empresa y no del sistema.
#
# Comparar contra el total de la base no sirve: una base recién
# construida tiene UNA sola empresa y siete referencias, de modo que el
# total y el de la empresa son el mismo número y la comparación no
# puede fallar. Una comprobación que no puede fallar no es una
# comprobación. Así que la segunda empresa se construye aquí, con una
# referencia activa, agotada y de costo alto: si el filtro por empresa
# no estuviera, las cuatro cifras cambiarían las cuatro.
ejecutar "INSERT INTO empresas (nit, razon_social, estado)
          VALUES ('PAN-900-1','Comercio vecino de prueba','ACTIVA');" >/dev/null
VECINA=$(consulta "SELECT empresa_id FROM empresas WHERE nit = 'PAN-900-1';")
if [[ -z "$VECINA" ]]; then
  falla "no se pudo crear la empresa vecina para probar el aislamiento"
else
  ejecutar "INSERT INTO empresa_config (empresa_id) VALUES ($VECINA);" >/dev/null
  ejecutar "INSERT INTO categorias (empresa_id, nombre, activa)
            VALUES ($VECINA,'PAN-categoria',TRUE);" >/dev/null
  ejecutar "INSERT INTO proveedores (empresa_id, razon_social, nit, plazo_dias,
                                      origen_lista, activo)
            VALUES ($VECINA,'PAN-proveedor','PAN-800-1',15,'NINGUNO',TRUE);" >/dev/null
  ejecutar "INSERT INTO productos (empresa_id, categoria_id, proveedor_id, codigo, sku,
                                    nombre, presentacion, costo, existencia,
                                    existencia_minima, activo)
            SELECT $VECINA,
                   (SELECT categoria_id  FROM categorias  WHERE empresa_id=$VECINA LIMIT 1),
                   (SELECT proveedor_id FROM proveedores WHERE empresa_id=$VECINA LIMIT 1),
                   'PAN-AJENO','PAN-SKU','Referencia del vecino','1 u',
                   99000000.00, 0, 5, TRUE;" >/dev/null

  entro=$(consulta "SELECT COUNT(*) FROM productos
                     WHERE empresa_id = $VECINA AND codigo = 'PAN-AJENO';")
  if [[ "$entro" != "1" ]]; then
    falla "no se pudo crear la referencia del vecino ($entro filas)"
  else
    despues=$(consulta "
      SELECT CONCAT_WS('|',
               CAST(SUM(p.activo = TRUE) AS SIGNED),
               CAST(SUM(p.activo = TRUE AND p.estado_stock = 'CRITICO') AS SIGNED),
               CAST(SUM(p.activo = TRUE AND p.estado_stock = 'AGOTADO') AS SIGNED),
               (SELECT CAST(COALESCE(SUM(i.valor_al_costo), 0) AS SIGNED)
                  FROM v_inventario_valorizado i WHERE i.empresa_id = 1))
        FROM productos p WHERE p.empresa_id = 1;")
    igual "las cifras no se mueven con una referencia del vecino" "$cifras" "$despues"

    # Y que la referencia del vecino sí exista y sí cuente en SU panel:
    # si no contara en ninguno de los dos, la comprobación de arriba
    # pasaría porque la fila no está, no porque el filtro funcione.
    suyas=$(consulta "
      SELECT CAST(SUM(p.activo = TRUE) AS SIGNED)
        FROM productos p WHERE p.empresa_id = $VECINA;")
    igual "y sí cuenta en el panel del vecino" "1" "$suyas"
  fi
fi


# ── 2. la tabla corta de existencias críticas ────────────────────────
echo
echo "── 2. la tabla corta de existencias críticas ──"

# Agotadas primero: SRV-001 está en cero y PC-003 por debajo del mínimo.
primera=$(consulta "
  SELECT c.codigo FROM v_existencias_criticas c
   WHERE c.empresa_id = 1
   ORDER BY c.estado_stock = 'AGOTADO' DESC,
            c.existencia - c.existencia_minima ASC, c.codigo ASC
   LIMIT 1;")
estado_primera=$(consulta "SELECT estado_stock FROM productos
                            WHERE empresa_id=1 AND codigo='$primera';")
igual "la primera fila está agotada" "AGOTADO" "$estado_primera"

# El tope se respeta. Se pide 1 sobre una empresa que tiene 2 críticas:
# si devolviera las dos, el LIMIT no estaría puesto donde se cree.
n=$(consulta "
  SELECT COUNT(*) FROM (
    SELECT c.codigo FROM v_existencias_criticas c
     WHERE c.empresa_id = 1 ORDER BY c.codigo LIMIT 1) t;")
igual "el tope de filas se aplica" "1" "$n"


# ── 3. el cruce de umbral de una carga aplicada (RF-35) ──────────────
echo
echo "── 3. el cruce de umbral tras la carga (RF-35) ──"

USU=$(consulta "SELECT usuario_id FROM usuarios WHERE empresa_id = 1 LIMIT 1;")
PRV=$(consulta "SELECT proveedor_id FROM proveedores WHERE empresa_id = 1 LIMIT 1;")
if [[ -z "$USU" || -z "$PRV" ]]; then
  falla "faltan usuario o proveedor de la empresa 1 para construir la carga"
else
  # El periodo anterior, que la demostración no trae. Diez días atrás y
  # cerrado ayer, que es la convención que deja `cerrarCostoVigente`.
  ejecutar "INSERT INTO historico_costos (empresa_id, producto_id, costo,
                                           vigente_desde, vigente_hasta)
            SELECT 1, p.producto_id, p.costo,
                   CURDATE() - INTERVAL 10 DAY, CURDATE() - INTERVAL 1 DAY
              FROM productos p
             WHERE p.empresa_id = 1
               AND p.codigo IN ('PC-002','PC-003','CEL-001','MON-001');" >/dev/null

  previos=$(consulta "SELECT COUNT(*) FROM historico_costos
                       WHERE vigente_desde = CURDATE() - INTERVAL 10 DAY;")
  if [[ "$previos" != "4" ]]; then
    falla "no se pudo construir el periodo anterior ($previos de 4 filas)"
  else
    ok "periodo anterior construido para cuatro referencias"

    ejecutar "INSERT INTO cargas_precios (empresa_id, proveedor_id, usuario_id, origen,
                                           nombre_archivo, estado, fecha, fecha_aplicacion,
                                           registros_leidos, registros_aceptados)
              VALUES (1, $PRV, $USU, 'ARCHIVO', 'PAN-cruces.csv', 'APLICADA',
                      NOW(), NOW(), 4, 4);" >/dev/null
    CARGA=$(consulta "SELECT carga_id FROM cargas_precios
                       WHERE nombre_archivo = 'PAN-cruces.csv';")

    # Cuatro referencias, cuatro situaciones distintas a propósito:
    #   PC-003  sube y cruza el umbral  → EXCLUIDO  a GRAVADO
    #   PC-002  baja y cruza el umbral  → GRAVADO   a EXCLUIDO
    #   CEL-001 sube sin cruzar         → no debe aparecer
    #   MON-001 sube pero es GRAVADO    → no debe aparecer nunca
    nuevo_costo () {  # $1 = código, $2 = costo nuevo
      ejecutar "INSERT INTO detalle_carga_precios (carga_id, empresa_id, numero_fila,
                                                    producto_id, referencia_origen,
                                                    costo, aceptado)
                SELECT $CARGA, 1, 2, p.producto_id, '$1', $2, TRUE
                  FROM productos p WHERE p.empresa_id = 1 AND p.codigo = '$1';" >/dev/null
      ejecutar "INSERT INTO historico_costos (empresa_id, producto_id, costo,
                                               vigente_desde, carga_id)
                SELECT 1, p.producto_id, $2, CURDATE(), $CARGA
                  FROM productos p WHERE p.empresa_id = 1 AND p.codigo = '$1';" >/dev/null
      ejecutar "UPDATE productos SET costo = $2
                 WHERE empresa_id = 1 AND codigo = '$1';" >/dev/null
    }
    nuevo_costo PC-003  2200000.00
    nuevo_costo PC-002  2100000.00
    nuevo_costo CEL-001  910000.00
    nuevo_costo MON-001 1100000.00

    # La consulta del panel, palabra por palabra.
    cruces () {
      consulta "
        SELECT CONCAT_WS('|', v.codigo,
                 (CASE WHEN v.base_contado = 'SOBRE_PRECIO'
                       THEN ROUND(h.costo / (2 - v.factor_contado), 0)
                       ELSE ROUND(h.costo * v.factor_contado, 0) END),
                 v.precio_contado,
                 CASE WHEN (CASE WHEN v.base_contado = 'SOBRE_PRECIO'
                                 THEN ROUND(h.costo / (2 - v.factor_contado), 0)
                                 ELSE ROUND(h.costo * v.factor_contado, 0) END)
                           > v.umbral_pesos THEN 'GRAVADO' ELSE 'EXCLUIDO' END,
                 CASE WHEN v.tarifa_contado > 0 THEN 'GRAVADO' ELSE 'EXCLUIDO' END)
          FROM historico_costos nuevo
          JOIN historico_costos h
            ON h.empresa_id = nuevo.empresa_id
           AND h.producto_id = nuevo.producto_id
           AND h.vigente_hasta = nuevo.vigente_desde - INTERVAL 1 DAY
          JOIN v_precios_producto v
            ON v.empresa_id = nuevo.empresa_id AND v.producto_id = nuevo.producto_id
         WHERE nuevo.empresa_id = 1 AND nuevo.carga_id = $CARGA
           AND v.tratamiento_iva = 'EXCLUIDO_HASTA_UMBRAL'
           AND ((CASE WHEN v.base_contado = 'SOBRE_PRECIO'
                      THEN ROUND(h.costo / (2 - v.factor_contado), 0)
                      ELSE ROUND(h.costo * v.factor_contado, 0) END)
                > v.umbral_pesos) <> (v.tarifa_contado > 0)
         ORDER BY v.codigo;"
    }
    salida=$(cruces)
    cuantos=$(wc -l <<<"$salida" | tr -d ' ')
    [[ -z "$salida" ]] && cuantos=0

    if [[ "$cuantos" -eq 0 ]]; then
      falla "la consulta de cruces devolvió cero filas: la prueba no probó nada"
    else
      igual "cruces encontrados" "2" "$cuantos"
      igual "PC-002 cruza a la baja" "PC-002|2640000|2520000|GRAVADO|EXCLUIDO" \
            "$(grep '^PC-002' <<<"$salida")"
      igual "PC-003 cruza al alza" "PC-003|2580000|2640000|EXCLUIDO|GRAVADO" \
            "$(grep '^PC-003' <<<"$salida")"
      grep -q '^CEL-001' <<<"$salida" \
        && falla "CEL-001 subió sin cruzar y apareció igualmente" \
        || ok "la que sube sin cruzar no aparece"
      grep -q '^MON-001' <<<"$salida" \
        && falla "MON-001 es GRAVADO y no mira al umbral, pero apareció" \
        || ok "la gravada sin umbral no aparece"
    fi
  fi
fi


# ── 4. la última carga ───────────────────────────────────────────────
echo
echo "── 4. la última carga de precios ──"

if [[ -n "${CARGA:-}" ]]; then
  # Una segunda carga del mismo instante, con identificador mayor: el
  # desempate por carga_id es lo que impide que la tarjeta cambie de
  # contenido entre dos recargas sin que nada haya cambiado.
  ejecutar "INSERT INTO cargas_precios (empresa_id, proveedor_id, usuario_id, origen,
                                         nombre_archivo, estado, fecha,
                                         registros_leidos)
            SELECT 1, $PRV, $USU, 'ARCHIVO', 'PAN-segunda.csv', 'VALIDADA', c.fecha, 9
              FROM cargas_precios c WHERE c.carga_id = $CARGA;" >/dev/null

  elegida=$(consulta "
    SELECT c.nombre_archivo FROM cargas_precios c
      JOIN proveedores p ON p.empresa_id = c.empresa_id AND p.proveedor_id = c.proveedor_id
     WHERE c.empresa_id = 1 ORDER BY c.fecha DESC, c.carga_id DESC LIMIT 1;")
  igual "con dos cargas del mismo instante gana la de identificador mayor" \
        "PAN-segunda.csv" "$elegida"

  # Y la elegida es una pendiente, que es lo que la tarjeta debe
  # recordar: ordenar por fecha_aplicacion dejaría las pendientes
  # invisibles.
  estado=$(consulta "SELECT estado FROM cargas_precios WHERE nombre_archivo='PAN-segunda.csv';")
  igual "la carga sin aplicar sigue siendo la que se muestra" "VALIDADA" "$estado"
else
  falla "no hubo carga que comprobar"
fi


# ── 5. la ventana de treinta días del gráfico ────────────────────────
echo
echo "── 5. la ventana de treinta días ──"

PRD=$(consulta "SELECT producto_id FROM productos WHERE empresa_id=1 AND codigo='MON-001';")
if [[ -z "$PRD" || -z "$USU" ]]; then
  falla "faltan datos para probar la ventana del gráfico"
else
  # Tres movimientos: uno dentro del borde, uno justo en el borde y uno
  # justo fuera. El del borde es el que decide si la ventana son
  # treinta días contando hoy o veintinueve.
  mover () {  # $1 = días atrás, $2 = documento
    ejecutar "INSERT INTO movimientos_inventario
                     (empresa_id, producto_id, usuario_id, tipo, cantidad,
                      existencia_anterior, existencia_posterior, documento, fecha, motivo)
              VALUES (1, $PRD, $USU, 'INGRESO', 1, 0, 1, '$2',
                      CURDATE() - INTERVAL $1 DAY, 'PAN-ventana');" >/dev/null
  }
  mover 5  'PAN-dentro'
  mover 29 'PAN-borde'
  mover 30 'PAN-fuera'

  vistos=$(consulta "
    SELECT GROUP_CONCAT(m.documento ORDER BY m.documento)
      FROM movimientos_inventario m
     WHERE m.empresa_id = 1
       AND m.documento LIKE 'PAN-%'
       AND m.fecha >= CURDATE() - INTERVAL 29 DAY;")
  igual "la ventana incluye el día 29 y excluye el 30" "PAN-borde,PAN-dentro" "$vistos"

  # Los dos sumandos salen positivos y el signo lo pone el tipo: una
  # consulta que restara los egresos daría un solo número y la pantalla
  # no podría dibujar dos barras.
  signos=$(consulta "
    SELECT CONCAT_WS('|',
             CAST(COALESCE(SUM(CASE WHEN m.tipo='INGRESO' THEN m.cantidad END),0) AS SIGNED),
             CAST(COALESCE(SUM(CASE WHEN m.tipo='EGRESO'  THEN m.cantidad END),0) AS SIGNED))
      FROM movimientos_inventario m
     WHERE m.empresa_id = 1 AND m.documento LIKE 'PAN-%'
       AND m.fecha >= CURDATE() - INTERVAL 29 DAY;")
  igual "ingresos y egresos por separado" "2|0" "$signos"
fi


echo
if [[ "$fallos" -eq 0 ]]; then
  echo "TODO EN VERDE"
  exit 0
fi
echo "$fallos comprobación(es) fallaron"
exit 1

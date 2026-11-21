#!/usr/bin/env bash
# Recorre un turno completo de la caja de punta a punta, contra una base
# desechable y por la API de verdad: el backend se arranca desde el JAR
# empaquetado, como lo arrancaría quien lo instala, y todo lo que se hace
# entra por HTTP con la sesión de un administrador y la de un vendedor.
#
#   1. abrir el turno;
#   2. una venta mixta (efectivo y transferencia, con cambio);
#   3. un encargo autorizado con PIN, hasta que llega y se entrega;
#   4. un producto nuevo vendido con un solo PIN;
#   5. un domicilio contra entrega, que se despacha;
#   6. el cobro posterior del domicilio;
#   7. una devolución parcial con saldo a favor, y su reintento;
#   8. el cierre con su diferencia: el efectivo esperado se compara, al
#      peso, con la cuenta que lleva este guion (base + lo cobrado en
#      efectivo - lo reembolsado en efectivo) y con la base de datos;
#   9. el kárdex cuadra con la existencia de cada producto tocado (RNF-10).
#
# Además comprueba, con el JAR y no con las pruebas de unidad:
#   - que el recibo en PDF sale del JAR (200, application/pdf, %PDF y 80 mm
#     de ancho): la fuente Liberation Sans vive dentro de un JAR anidado
#     del JAR ejecutable, y solo así se ve que carga desde ahí;
#   - cuánto tardan «buscar por código» y «registrar una venta» con un
#     catálogo de 20.000 referencias (RNF-03). No hay aserción de
#     milisegundos: el presupuesto lo protege el conteo de consultas de las
#     pruebas de la caja. Los tiempos se imprimen para anotarlos.
#
# Se ejecuta contra una base con la cadena V1 a V31 aplicada y los datos de
# demostración (V8): las migraciones posteriores las aplica Flyway cuando
# arranca el JAR. Se conecta con las opciones de siempre:
#
#   bash verificar_caja.sh -h 127.0.0.1 -P 3306 -u root -psecreto
#
# Las credenciales van en la línea de órdenes, no se leen de ningún archivo
# de configuración: el JAR recibe las mismas por variables de entorno.
#
# NO deja la base como la encontró y no borra nada: las ventas, los turnos
# y el kárdex son historia que el esquema no deja deshacer. Cada ejecución
# crea sus propios usuarios, productos y cliente (llevan el identificador de
# la ejecución), de modo que se puede repetir sobre la misma base. Sirve
# solo para una base desechable. Sí deja la empresa 1 en estado ACTIVA con
# la suscripción al día, y 20.000 productos CAJ-VOL-* para medir.
#
# Variables opcionales:
#   SGIP_CAJA_JAR     un JAR ya construido (la CI usa el de `mvn verify`);
#                     sin ella se construye con `./mvnw clean package`.
#   SGIP_CAJA_MUESTRAS  cuántas veces se mide cada operación (20).
set -uo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RAIZ="$(cd "$AQUI/.." && pwd)"

# ── La conexión, tal como la dan los demás verificar_*.sh ──────────────
BD_HOST="127.0.0.1"; BD_PUERTO="3306"; BD_USUARIO="root"; BD_CLAVE=""
argumentos=("$@")
i=0
while ((i < ${#argumentos[@]})); do
  a="${argumentos[i]}"
  case "$a" in
    -h)         BD_HOST="${argumentos[i+1]:-}"; i=$((i + 2)) ;;
    --host=*)   BD_HOST="${a#--host=}"; i=$((i + 1)) ;;
    -h?*)       BD_HOST="${a#-h}"; i=$((i + 1)) ;;
    -P)         BD_PUERTO="${argumentos[i+1]:-}"; i=$((i + 2)) ;;
    --port=*)   BD_PUERTO="${a#--port=}"; i=$((i + 1)) ;;
    -P?*)       BD_PUERTO="${a#-P}"; i=$((i + 1)) ;;
    -u)         BD_USUARIO="${argumentos[i+1]:-}"; i=$((i + 2)) ;;
    --user=*)   BD_USUARIO="${a#--user=}"; i=$((i + 1)) ;;
    -u?*)       BD_USUARIO="${a#-u}"; i=$((i + 1)) ;;
    --password=*) BD_CLAVE="${a#--password=}"; i=$((i + 1)) ;;
    -p?*)       BD_CLAVE="${a#-p}"; i=$((i + 1)) ;;
    *)          i=$((i + 1)) ;;
  esac
done

# El instalador de MySQL para Windows no pone su carpeta en el PATH.
if ! command -v mysql >/dev/null 2>&1; then
  for d in "/c/Program Files/MySQL"/*/bin; do
    [[ -x "$d/mysql.exe" ]] && { PATH="$PATH:$d"; break; }
  done
fi
for herramienta in mysql java node curl; do
  command -v "$herramienta" >/dev/null 2>&1 || { echo "Falta '$herramienta' en el PATH."; exit 1; }
done

MYSQL=(mysql --no-defaults --default-character-set=utf8mb4 -h "$BD_HOST" -P "$BD_PUERTO" -u "$BD_USUARIO")
[[ -n "$BD_CLAVE" ]] && MYSQL+=("-p$BD_CLAVE")

sin_aviso () { grep -v "Using a password on the command line interface" || true; }
ejecutar () { "${MYSQL[@]}" sgip -e "$1" 2>&1 | sin_aviso; }
consulta () { "${MYSQL[@]}" -N -B sgip -e "$1" 2>/dev/null | tr -d '\r'; }

# ── Resultados ──────────────────────────────────────────────────────────
fallos=0
ok ()    { echo "  ok    · $1"; }
falla () { echo "  FALLA · $1"; fallos=$((fallos + 1)); }
igual () { # igual descripción esperado obtenido
  if [[ "$2" == "$3" ]]; then ok "$1"; else falla "$1 — esperaba '$2' y obtuve '${3:-(nada)}'"; fi
}
paso () { echo; echo "── $1 ──"; }
abortar () { falla "$1"; echo; echo "Se detiene aquí: lo que sigue depende de esto."; exit 1; }

# ── Utilidades ──────────────────────────────────────────────────────────
TMP="$(mktemp -d)"
BACK_PID=""
BACK_LOG="$TMP/backend.log"

json () { # json 'expresión sobre o' < documento
  node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{try{const o=JSON.parse(d);const v=eval(process.argv[1]);process.stdout.write(v===undefined||v===null?"":String(v))}catch(e){}})' "$1"
}
calc () { node -p "$1"; }
uuid () { node -e 'console.log(require("crypto").randomUUID())'; }
puerto_libre () {
  node -e 'const s=require("net").createServer();s.listen(0,"127.0.0.1",()=>{console.log(s.address().port);s.close()})'
}
puerto_ocupado () {
  node -e 'const s=require("net").createServer();s.on("error",()=>process.exit(0));s.listen(+process.argv[1],"127.0.0.1",()=>process.exit(1))' "$1"
}
hash_de () { # PBKDF2 con el mismo formato que util/Seguridad.derivar del backend
  node -e 'const c=require("crypto");const s=c.randomBytes(16);const h=c.pbkdf2Sync(process.argv[1],s,120000,32,"sha256");const b=x=>x.toString("base64").replace(/=+$/,"");console.log("pbkdf2$120000$"+b(s)+"$"+b(h))' "$1"
}

detener_backend () {
  [[ -n "$BACK_PID" ]] || return 0
  local win
  win=$(cat "/proc/$BACK_PID/winpid" 2>/dev/null || true)
  if [[ -n "$win" ]] && command -v taskkill >/dev/null 2>&1; then
    taskkill //PID "$win" //T //F >/dev/null 2>&1
  else
    kill "$BACK_PID" 2>/dev/null
    for _ in $(seq 1 20); do kill -0 "$BACK_PID" 2>/dev/null || break; sleep 0.5; done
    kill -9 "$BACK_PID" 2>/dev/null
  fi
  wait "$BACK_PID" 2>/dev/null
  BACK_PID=""
}
limpiar () {
  detener_backend
  rm -rf "$TMP"
}
trap limpiar EXIT
trap 'exit 130' INT TERM

# Llama a la API. Deja el código HTTP en COD y el cuerpo en CUERPO.
URL=""
api () { # api MÉTODO /ruta token [cuerpo]
  local metodo="$1" ruta="$2" token="$3" cuerpo="${4-}"
  local args=(-s -m 120 -X "$metodo" -o "$TMP/respuesta" -w '%{http_code}')
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n "$cuerpo" ]] && args+=(-H 'Content-Type: application/json' --data-binary "$cuerpo")
  COD=$(curl "${args[@]}" "$URL/api/v1$ruta")
  CUERPO=$(cat "$TMP/respuesta")
}
# La respuesta de un fallo, en una línea, para el mensaje.
resumen_error () { json 'o.detail||o.title||""' <<<"$CUERPO"; }
# Espera un código HTTP; si no, falla con lo que dijo el servidor.
espera () { # espera descripción código
  if [[ "$COD" == "$2" ]]; then ok "$1 ($2)"; else falla "$1 — esperaba $2 y respondió $COD: $(resumen_error) ${CUERPO:0:200}"; return 1; fi
}
# Espera cualquier 4xx (una autorización que falta o un PIN errado).
espera_4xx () { # espera_4xx descripción
  if [[ "$COD" =~ ^4[0-9][0-9]$ ]]; then ok "$1 ($COD)"; else falla "$1 — esperaba un 4xx y respondió $COD"; fi
}

# ── 0. El JAR ───────────────────────────────────────────────────────────
paso "0. el programa se arranca desde el JAR empaquetado"

if [[ -z "${SGIP_CAJA_JAR:-}" ]]; then
  echo "  construyendo con ./mvnw clean package -DskipTests (tarda un par de minutos)…"
  if ! (cd "$RAIZ/backend" && ./mvnw -B -q -DskipTests clean package >"$TMP/construccion.log" 2>&1); then
    tail -20 "$TMP/construccion.log"
    abortar "no se pudo empaquetar el backend"
  fi
  SGIP_CAJA_JAR=$(ls "$RAIZ"/backend/target/backend-*.jar 2>/dev/null | grep -v '\.original$' | head -1)
fi
[[ -f "$SGIP_CAJA_JAR" ]] || abortar "no hay JAR: $SGIP_CAJA_JAR"
ok "JAR: $(basename "$SGIP_CAJA_JAR")"

# La configuración entra por variables y por argumentos: si el JAR llevara
# un application.yml, lo que se prueba dependería de ese archivo.
if command -v jar >/dev/null 2>&1; then
  if jar tf "$SGIP_CAJA_JAR" | grep -qE '^BOOT-INF/classes/application(-[^/]*)?\.(yml|yaml|properties)$'; then
    echo "  AVISO · el JAR lleva un application.yml: la configuración no es solo la de este guion."
  else
    ok "el JAR no lleva application.yml: la configuración es solo la de este guion"
  fi
fi

# La base tiene que ser la del esquema: un error claro y no una pila de Spring.
if [[ -z "$(consulta "SELECT 1 FROM information_schema.tables WHERE table_schema='sgip' AND table_name='usuarios';")" ]]; then
  abortar "la base 'sgip' no tiene la cadena V1 a V31 aplicada (database/aplicar.sh --demo)"
fi
if [[ "$(consulta "SELECT COUNT(*) FROM empresas WHERE empresa_id = 1;")" != "1" ]]; then
  abortar "no está la empresa 1 de los datos de demostración (V8)"
fi

PUERTO=$(puerto_libre)
URL="http://127.0.0.1:$PUERTO"
mkdir -p "$TMP/archivos"
SGIP_ARCHIVOS_RAIZ="$TMP/archivos" \
java -jar "$SGIP_CAJA_JAR" \
  --server.port="$PUERTO" --server.address=127.0.0.1 \
  "--spring.datasource.url=jdbc:mysql://$BD_HOST:$BD_PUERTO/sgip?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Bogota" \
  "--spring.datasource.username=$BD_USUARIO" "--spring.datasource.password=$BD_CLAVE" \
  --spring.jpa.open-in-view=false \
  >"$BACK_LOG" 2>&1 &
BACK_PID=$!

listo=0
for _ in $(seq 1 120); do
  [[ "$(curl -s -m 3 -o /dev/null -w '%{http_code}' "$URL/api/v1/productos" 2>/dev/null)" == "401" ]] && { listo=1; break; }
  kill -0 "$BACK_PID" 2>/dev/null || break
  sleep 2
done
if [[ "$listo" != "1" ]]; then
  tail -30 "$BACK_LOG"
  abortar "el backend no arrancó"
fi
ok "el backend responde en el puerto $PUERTO (Flyway aplicó las migraciones nuevas)"
igual "el esquema quedó en la V46 o posterior" "1" \
  "$(consulta "SELECT COUNT(*) >= 1 FROM flyway_schema_history WHERE success = 1 AND CAST(version AS UNSIGNED) >= 46;")"

# ── 1. Los datos de esta ejecución ──────────────────────────────────────
paso "1. usuarios, productos y cliente de esta ejecución"

RUN="$(date +%s)$RANDOM"
CORREO_ADMIN="caja-$RUN-admin@verificar.local"
CORREO_VEND="caja-$RUN-vendedor@verificar.local"
CLAVE="Clave-Caja-$RUN"
PIN="4829"
H=$(hash_de "$CLAVE")

# La empresa 1 tiene que poder escribir hoy: la prueba gratuita de la V8
# pudo vencer, y entonces toda escritura recibiría 403.
salida=$(ejecutar "
  UPDATE empresas SET estado = 'ACTIVA' WHERE empresa_id = 1;
  UPDATE suscripciones SET estado = 'AL_DIA', vigente_hasta = DATE_ADD(CURDATE(), INTERVAL 1 YEAR)
   WHERE empresa_id = 1;
  INSERT INTO usuarios (empresa_id, rol_id, nombre, correo, clave_hash, activo, bloqueado,
                        intentos_fallidos, cambio_clave_req)
  SELECT 1, rol_id, 'Administradora de la caja $RUN', '$CORREO_ADMIN', '$H', 1, 0, 0, 0
    FROM roles WHERE nombre = 'ADMINISTRADOR';
  INSERT INTO usuarios (empresa_id, rol_id, nombre, correo, clave_hash, activo, bloqueado,
                        intentos_fallidos, cambio_clave_req)
  SELECT 1, rol_id, 'Vendedor de la caja $RUN', '$CORREO_VEND', '$H', 1, 0, 0, 0
    FROM roles WHERE nombre = 'VENDEDOR';")
[[ -z "$salida" ]] && ok "dos usuarios nuevos en la empresa 1, y la empresa activa" || abortar "no se pudieron crear los usuarios: $salida"

ingresar () { # ingresar correo -> token
  curl -s -m 30 -X POST "$URL/api/v1/auth/login" -H 'Content-Type: application/json' \
       -d "{\"correo\":\"$1\",\"clave\":\"$CLAVE\"}" | json 'o.token'
}
T_ADMIN=$(ingresar "$CORREO_ADMIN"); [[ -n "$T_ADMIN" ]] || abortar "el administrador no entra"
T_VEND=$(ingresar "$CORREO_VEND");   [[ -n "$T_VEND" ]]  || abortar "el vendedor no entra"
ok "el administrador y el vendedor entran"
api GET /caja/administradores "$T_VEND"
ADMIN_ID=$(json 'o.find(a=>a.nombre.includes("'"$RUN"'")).id' <<<"$CUERPO")
[[ -n "$ADMIN_ID" ]] && ok "el vendedor ve a la administradora para llamarla (id $ADMIN_ID)" \
  || abortar "el vendedor no ve a la administradora en /caja/administradores"
VEND_NOMBRE="Vendedor de la caja $RUN"

api PUT /perfil/pin "$T_ADMIN" "{\"pinNuevo\":\"$PIN\"}"
espera "la administradora fija su PIN" 204 || abortar "sin PIN no hay autorizaciones"

CATEGORIA=$(consulta "SELECT categoria_id FROM categorias WHERE empresa_id = 1 AND generica = 1 LIMIT 1;")
PROVEEDOR=$(consulta "SELECT proveedor_id FROM proveedores WHERE empresa_id = 1 AND nit = 'SIN-PROVEEDOR' LIMIT 1;")
[[ -n "$CATEGORIA" && -n "$PROVEEDOR" ]] || abortar "la empresa 1 no tiene su categoría y proveedor genéricos (V45)"

api GET /caja/medios-pago "$T_VEND"
MEDIO_EFECTIVO=$(json 'o.find(m=>m.tipo==="EFECTIVO"&&m.activo).id' <<<"$CUERPO")
MEDIO_TRANSF=$(json 'o.find(m=>m.tipo==="TRANSFERENCIA"&&m.activo).id' <<<"$CUERPO")
[[ -n "$MEDIO_EFECTIVO" && -n "$MEDIO_TRANSF" ]] && ok "hay medios en efectivo ($MEDIO_EFECTIVO) y por transferencia ($MEDIO_TRANSF)" \
  || abortar "faltan los medios de pago de la empresa 1"

# Un producto por el catálogo, con su existencia por un ingreso del kárdex:
# nada se escribe a mano en `existencia`, o el RNF-10 pasaría por casualidad.
crear_producto () { # crear_producto sufijo nombre costo precio existencia -> id (en PRODUCTO_ID)
  api POST /productos "$T_ADMIN" "{\"categoriaId\":$CATEGORIA,\"proveedorId\":$PROVEEDOR,\"codigo\":\"CAJ-$RUN-$1\",\"nombre\":\"$2 $RUN\",\"presentacion\":\"unidad\",\"costo\":$3,\"existencia\":0,\"existenciaMinima\":0,\"contado\":{\"precio\":$4}}"
  espera "se crea el producto $1" 201 || abortar "no se pudo crear el producto $1"
  PRODUCTO_ID=$(json 'o.productoId' <<<"$CUERPO")
  api POST /inventario/ingresos "$T_ADMIN" "{\"productoId\":$PRODUCTO_ID,\"cantidad\":$5,\"documento\":\"CAJA-$RUN\",\"motivo\":\"Existencia inicial de la verificación\"}"
  espera "ingresan $5 unidades de $1 por el kárdex" 201 || abortar "no se pudo ingresar la existencia de $1"
}
crear_producto A "Producto A de la caja" 40000 100000 50;  PROD_A=$PRODUCTO_ID
crear_producto B "Producto B de la caja" 20000 50000 2;    PROD_B=$PRODUCTO_ID
crear_producto C "Producto C de la caja" 4000 10000 300;   PROD_C=$PRODUCTO_ID

# El precio y el IVA, como los ve la caja: de ahí salen todos los totales.
api GET "/caja/productos?codigo=CAJ-$RUN-A" "$T_VEND"
espera "la caja encuentra el producto A por su código" 200 || abortar "la caja no encuentra el producto A"
PRECIO_A=$(json 'o.precio' <<<"$CUERPO"); TARIFA=$(json 'o.tarifaIva' <<<"$CUERPO")
igual "el producto A es vendible" "true" "$(json 'o.vendible' <<<"$CUERPO")"
echo "  precio de A sin IVA: $PRECIO_A · tarifa: $TARIFA"
# Los precios son múltiplos de 100: con la tarifa del 19 % o con ninguna el IVA es en pesos enteros.
total () { calc "Math.round($1*(1+$TARIFA)+1e-9)"; }
U_A=$(total 100000); U_B=$(total 50000); U_C=$(total 10000)

api POST /caja/clientes "$T_VEND" "{\"nombre\":\"Cliente de la caja $RUN\",\"tipoDocumento\":\"CC\",\"identificacion\":\"7$RUN\",\"correo\":\"cliente-$RUN@verificar.local\",\"telefono\":\"3001234567\",\"direccion\":\"Calle 1 # 2-3\",\"ciudad\":\"Bogotá\",\"autorizacionDatos\":true}"
espera "se crea el cliente identificado" 201 || abortar "no se pudo crear el cliente"
CLIENTE=$(json 'o.id' <<<"$CUERPO")

existencia_de () { # existencia_de código
  api GET "/caja/productos?codigo=$1" "$T_VEND"
  json 'o.existencia' <<<"$CUERPO"
}
manana=$(consulta "SELECT DATE(UTC_TIMESTAMP() - INTERVAL 5 HOUR + INTERVAL 7 DAY);")   # la hora de Bogotá, ADR-0030
hoy=$(consulta "SELECT DATE(UTC_TIMESTAMP() - INTERVAL 5 HOUR);")

# El efectivo que tiene que haber en el cajón: la base más lo cobrado en
# efectivo (el monto de cada pago, no lo que el cliente entregó) menos lo
# reembolsado en efectivo.
BASE=50000
ESPERADO=$BASE

# ── 2. El turno ─────────────────────────────────────────────────────────
paso "2. abrir el turno"
api GET /caja/turnos/actual "$T_VEND";                 espera "sin turno abierto, /actual responde vacío" 204
api POST /caja/ventas "$T_VEND" "{\"claveIdempotencia\":\"$(uuid)\",\"lineas\":[{\"productoId\":$PROD_C,\"cantidad\":1}],\"pagos\":[{\"medioId\":$MEDIO_EFECTIVO,\"monto\":$U_C,\"recibido\":$U_C}]}"
espera_4xx "sin turno abierto no se vende"
api POST /caja/turnos "$T_VEND" "{\"baseEfectivo\":$BASE}";  espera "el vendedor abre su turno con base $BASE" 201 || abortar "sin turno no hay caja"
TURNO=$(json 'o.id' <<<"$CUERPO")
api POST /caja/turnos "$T_VEND" "{\"baseEfectivo\":$BASE}";  espera "un segundo turno abierto del mismo cajero" 409
api POST /caja/turnos "$T_ADMIN" '{"baseEfectivo":0}';      espera "la administradora abre el suyo, con base 0" 201

# ── 3. Venta mixta ──────────────────────────────────────────────────────
paso "3. una venta mixta: efectivo con cambio y transferencia"
EFECTIVO_1=100000
T1=$((U_A * 2))
TRANSF_1=$((T1 - EFECTIVO_1))
CLAVE_V1=$(uuid)
VENTA_1="{\"claveIdempotencia\":\"$CLAVE_V1\",\"clienteId\":$CLIENTE,\"lineas\":[{\"productoId\":$PROD_A,\"cantidad\":2}],\"pagos\":[{\"medioId\":$MEDIO_EFECTIVO,\"monto\":$EFECTIVO_1,\"recibido\":$((EFECTIVO_1 + 3000))},{\"medioId\":$MEDIO_TRANSF,\"monto\":$TRANSF_1,\"referencia\":\"TR-$RUN\"}]}"
api POST /caja/ventas "$T_VEND" "$VENTA_1"
espera "la venta mixta se registra" 201 || abortar "la venta mixta falló"
VENTA_1_ID=$(json 'o.id' <<<"$CUERPO")
igual "el total de la venta es 2 × $U_A" "$T1" "$(json 'Number(o.total)' <<<"$CUERPO")"
igual "el cambio es lo que sobró del efectivo recibido" "3000" "$(json 'Number(o.cambio)' <<<"$CUERPO")"
ESPERADO=$((ESPERADO + EFECTIVO_1))
igual "el inventario de A bajó 2" "48" "$(existencia_de "CAJ-$RUN-A")"
api POST /caja/ventas "$T_VEND" "$VENTA_1"
espera "el reintento con la misma clave devuelve la misma venta" 200
igual "es la misma venta" "$VENTA_1_ID" "$(json 'o.id' <<<"$CUERPO")"
igual "el reintento no tocó el inventario" "48" "$(existencia_de "CAJ-$RUN-A")"
api POST /caja/ventas "$T_VEND" "{\"claveIdempotencia\":\"$CLAVE_V1\",\"lineas\":[{\"productoId\":$PROD_A,\"cantidad\":1}],\"pagos\":[{\"medioId\":$MEDIO_EFECTIVO,\"monto\":$U_A,\"recibido\":$U_A}]}"
espera "la misma clave con otra carga es un conflicto" 409

# ── 4. Encargo con PIN ──────────────────────────────────────────────────
paso "4. un encargo autorizado con PIN"
T3=$((U_B * 3))
ENCARGO="\"lineas\":[{\"productoId\":$PROD_B,\"cantidad\":3,\"entregaEstimada\":\"$manana\"}],\"motivoEncargo\":\"El cliente lo espera de la próxima remesa\",\"pagos\":[{\"medioId\":$MEDIO_EFECTIVO,\"monto\":$T3,\"recibido\":$T3}]"
api POST /caja/ventas "$T_VEND" "{\"claveIdempotencia\":\"$(uuid)\",$ENCARGO}"
espera_4xx "sin la autorización de un administrador, el encargo se rechaza"
api POST /caja/ventas "$T_VEND" "{\"claveIdempotencia\":\"$(uuid)\",$ENCARGO,\"autorizacion\":{\"administradorId\":$ADMIN_ID,\"pin\":\"0000\"}}"
espera_4xx "con un PIN errado, el encargo se rechaza"
igual "las dos negativas no tocaron el inventario de B" "2" "$(existencia_de "CAJ-$RUN-B")"
api POST /caja/ventas "$T_VEND" "{\"claveIdempotencia\":\"$(uuid)\",$ENCARGO,\"autorizacion\":{\"administradorId\":$ADMIN_ID,\"pin\":\"$PIN\"}}"
espera "con el PIN de la administradora, el encargo se registra" 201 || abortar "el encargo falló"
ESPERADO=$((ESPERADO + T3))
igual "B quedó debiendo una unidad (2 - 3)" "-1" "$(existencia_de "CAJ-$RUN-B")"

api GET "/caja/encargos?estado=PENDIENTE" "$T_ADMIN"
ITEM=$(json 'o.find(e=>e.productoId===('"$PROD_B"')).itemId' <<<"$CUERPO")
[[ -n "$ITEM" ]] && ok "la administradora ve el encargo pendiente (ítem $ITEM)" || falla "el encargo no aparece como pendiente"
igual "el encargo es de 1 unidad que falta" "1" "$(json 'o.find(e=>e.productoId===('"$PROD_B"')).cantidadEncargo' <<<"$CUERPO")"
api GET "/caja/encargos?estado=PENDIENTE" "$T_VEND"; espera_4xx "el vendedor no ve la lista de encargos"
api POST /inventario/ingresos "$T_ADMIN" "{\"productoId\":$PROD_B,\"cantidad\":5,\"documento\":\"CAJA-$RUN\",\"motivo\":\"Llegó la remesa\"}"
espera "llega la mercancía (ingreso de 5)" 201
api GET "/caja/encargos?estado=LLEGO" "$T_ADMIN"
[[ -n "$(json 'o.find(e=>e.productoId===('"$PROD_B"')).itemId' <<<"$CUERPO")" ]] \
  && ok "el encargo pasa a «llegó» al subir la existencia" || falla "el encargo no pasó a «llegó»"
if [[ -n "$ITEM" ]]; then
  api POST "/caja/encargos/$ITEM/entrega" "$T_ADMIN"; espera "la administradora marca la entrega" 200
  api POST "/caja/encargos/$ITEM/entrega" "$T_ADMIN"; espera "la segunda entrega es un conflicto" 409
fi
api GET "/caja/encargos?estado=LLEGO" "$T_ADMIN"
igual "ya no queda por entregar" "" "$(json 'o.find(e=>e.productoId===('"$PROD_B"')).itemId' <<<"$CUERPO")"

# ── 5. Producto nuevo con un solo PIN ───────────────────────────────────
paso "5. un producto nuevo vendido con un solo PIN"
P_NUEVO=30000
T4=$(total $P_NUEVO)
api POST /caja/ventas "$T_VEND" "{\"claveIdempotencia\":\"$(uuid)\",\"lineas\":[{\"productoNuevo\":{\"nombre\":\"Producto nuevo $RUN\",\"precioVenta\":$P_NUEVO},\"cantidad\":1,\"entregaEstimada\":\"$manana\"}],\"motivoEncargo\":\"No estaba en el catálogo\",\"pagos\":[{\"medioId\":$MEDIO_EFECTIVO,\"monto\":$T4,\"recibido\":$T4}],\"autorizacion\":{\"administradorId\":$ADMIN_ID,\"pin\":\"$PIN\"}}"
espera "el producto nuevo se vende con un solo PIN" 201 || abortar "el producto nuevo falló"
ESPERADO=$((ESPERADO + T4))
NUEVO_ID=$(consulta "SELECT producto_id FROM productos WHERE empresa_id = 1 AND nombre = 'Producto nuevo $RUN';")
[[ -n "$NUEVO_ID" ]] && ok "el producto nació en el catálogo (id $NUEVO_ID)" || abortar "el producto nuevo no está en el catálogo"
igual "su código es de los rápidos (RAP-n)" "1" "$(consulta "SELECT codigo LIKE 'RAP-%' FROM productos WHERE producto_id = $NUEVO_ID;")"
igual "su costo es provisional" "1" "$(consulta "SELECT costo_provisional FROM productos WHERE producto_id = $NUEVO_ID;")"
igual "quedó debiendo una unidad: es un encargo" "-1" "$(consulta "SELECT existencia FROM productos WHERE producto_id = $NUEVO_ID;")"

# ── 6. Domicilio contra entrega ─────────────────────────────────────────
paso "6. un domicilio contra entrega"
api POST /caja/ventas "$T_VEND" "{\"claveIdempotencia\":\"$(uuid)\",\"clienteId\":$CLIENTE,\"lineas\":[{\"productoId\":$PROD_A,\"cantidad\":1}],\"domicilio\":{\"direccion\":\"Carrera 7 # 45-10\",\"ciudad\":\"Bogotá\",\"telefono\":\"3001234567\",\"recibe\":\"Ana Ruiz\",\"contraEntrega\":true}}"
espera "la venta contra entrega se registra sin pagos" 201 || abortar "el domicilio falló"
DOM_ID=$(json 'o.id' <<<"$CUERPO"); DOM_CONSEC=$(json 'o.consecutivo' <<<"$CUERPO")
igual "su total es el de una unidad de A" "$U_A" "$(json 'Number(o.total)' <<<"$CUERPO")"
igual "A bajó otra unidad" "47" "$(existencia_de "CAJ-$RUN-A")"
api GET "/caja/domicilios?estado=POR_COBRAR" "$T_VEND"
igual "el domicilio está por cobrar, por todo su valor" "$U_A" "$(json 'Number(o.find(d=>d.ventaId===('"$DOM_ID"')).valorPorCobrar)' <<<"$CUERPO")"
api GET "/caja/domicilios?estado=POR_DESPACHAR" "$T_VEND"
[[ -n "$(json 'o.find(d=>d.ventaId===('"$DOM_ID"')).consecutivo' <<<"$CUERPO")" ]] \
  && ok "y por despachar" || falla "el domicilio no está por despachar"

# Antes del cobro, el cajón no tiene nada de este domicilio.
api GET /caja/turnos/actual/resumen "$T_VEND"
igual "el efectivo esperado todavía no cuenta el domicilio" "$ESPERADO" "$(json 'Number(o.efectivoEsperado)' <<<"$CUERPO")"

# ── 7. El cobro posterior ───────────────────────────────────────────────
paso "7. el cobro posterior del domicilio"
api POST "/caja/domicilios/$DOM_ID/cobro" "$T_VEND" "[{\"medioId\":$MEDIO_EFECTIVO,\"monto\":$((U_A - 1000)),\"recibido\":$U_A}]"
espera_4xx "un cobro que no suma todo el saldo se rechaza"
api POST "/caja/domicilios/$DOM_ID/cobro" "$T_VEND" "[{\"medioId\":$MEDIO_EFECTIVO,\"monto\":$U_A,\"recibido\":$((U_A + 1000))}]"
espera "el vendedor cobra el domicilio en efectivo" 201 || abortar "el cobro falló"
igual "lo cobrado es el valor de la venta" "$U_A" "$(json 'Number(o.cobrado)' <<<"$CUERPO")"
igual "el cambio es lo que sobró" "1000" "$(json 'Number(o.cambio)' <<<"$CUERPO")"
ESPERADO=$((ESPERADO + U_A))
api POST "/caja/domicilios/$DOM_ID/cobro" "$T_VEND" "[{\"medioId\":$MEDIO_EFECTIVO,\"monto\":$U_A,\"recibido\":$U_A}]"
espera "cobrarlo dos veces es un conflicto" 409
api GET "/caja/domicilios?estado=POR_COBRAR" "$T_VEND"
igual "ya no está por cobrar" "" "$(json 'o.find(d=>d.ventaId===('"$DOM_ID"')).consecutivo' <<<"$CUERPO")"
api POST "/caja/domicilios/$DOM_ID/entrega" "$T_VEND"; espera "se marca la entrega del domicilio" 200
api POST "/caja/domicilios/$DOM_ID/entrega" "$T_VEND"; espera "la segunda entrega es un conflicto" 409

# ── 8. Devolución parcial con saldo a favor ─────────────────────────────
paso "8. una devolución parcial con saldo a favor"
CONSEC_1=$(consulta "SELECT consecutivo FROM ventas WHERE venta_id = $VENTA_1_ID;")
api GET "/caja/ventas?consecutivo=$CONSEC_1" "$T_VEND"
espera "la venta se consulta por su consecutivo ($CONSEC_1)" 200
api GET "/caja/clientes/$CLIENTE/saldo" "$T_VEND"
MEDIO_SALDO=$(json 'o.medioId' <<<"$CUERPO")
igual "el cliente empieza sin saldo a favor" "0" "$(json 'Number(o.saldo)' <<<"$CUERPO")"
[[ -n "$MEDIO_SALDO" ]] || abortar "la caja no dice cuál es el medio del saldo a favor"

DEVUELTO=$U_A                      # una de las dos unidades de A
EN_EFECTIVO=40000
A_SALDO=$((DEVUELTO - EN_EFECTIVO))
CLAVE_D=$(uuid)
DEVOLUCION="{\"clave\":\"$CLAVE_D\",\"ventaId\":$VENTA_1_ID,\"unidades\":[{\"productoId\":$PROD_A,\"cantidad\":1}],\"motivo\":\"Devolución parcial de la verificación\",\"reembolsos\":[{\"medioId\":$MEDIO_EFECTIVO,\"monto\":$EN_EFECTIVO},{\"medioId\":$MEDIO_SALDO,\"monto\":$A_SALDO}]"
api POST /caja/devoluciones "$T_VEND" "$DEVOLUCION}"
espera_4xx "el vendedor no devuelve sin la autorización de un administrador"
api POST /caja/devoluciones "$T_VEND" "$DEVOLUCION,\"autorizacion\":{\"administradorId\":$ADMIN_ID,\"pin\":\"$PIN\"}}"
espera "con el PIN, la devolución parcial se registra" 201 || abortar "la devolución falló"
DEV_ID=$(json 'o.id' <<<"$CUERPO")
igual "lo devuelto es una unidad" "$DEVUELTO" "$(json 'Number(o.valorDevuelto)' <<<"$CUERPO")"
igual "se reembolsó todo lo devuelto" "$DEVUELTO" "$(json 'Number(o.reembolsado)' <<<"$CUERPO")"
igual "la venta queda devuelta en parte" "DEVUELTA_PARCIAL" "$(json 'o.estadoVenta' <<<"$CUERPO")"
ESPERADO=$((ESPERADO - EN_EFECTIVO))
igual "A recupera la unidad" "48" "$(existencia_de "CAJ-$RUN-A")"
api GET "/caja/clientes/$CLIENTE/saldo" "$T_VEND"
igual "el cliente queda con saldo a favor por lo no reembolsado en efectivo" "$A_SALDO" "$(json 'Number(o.saldo)' <<<"$CUERPO")"
api POST /caja/devoluciones "$T_VEND" "$DEVOLUCION,\"autorizacion\":{\"administradorId\":$ADMIN_ID,\"pin\":\"$PIN\"}}"
espera "el reintento con la misma clave devuelve la misma devolución" 200
igual "es la misma devolución" "$DEV_ID" "$(json 'o.id' <<<"$CUERPO")"
api GET "/caja/clientes/$CLIENTE/saldo" "$T_VEND"
igual "el reintento no abonó dos veces" "$A_SALDO" "$(json 'Number(o.saldo)' <<<"$CUERPO")"
igual "el reintento no repuso otra unidad" "48" "$(existencia_de "CAJ-$RUN-A")"

# ── 9. El recibo en PDF, desde el JAR ───────────────────────────────────
paso "9. el recibo en PDF sale del JAR"
curl -s -m 60 -D "$TMP/cabeceras" -o "$TMP/recibo.pdf" -H "Authorization: Bearer $T_VEND" \
  "$URL/api/v1/caja/ventas/$VENTA_1_ID/recibo.pdf"
ESTADO_PDF=$(head -1 "$TMP/cabeceras" | tr -d '\r' | awk '{print $2}')
TIPO_PDF=$(grep -i '^content-type:' "$TMP/cabeceras" | tr -d '\r' | head -1 | sed 's/^[^:]*: *//')
igual "el PDF responde 200" "200" "$ESTADO_PDF"
igual "con el tipo application/pdf" "application/pdf" "$TIPO_PDF"
igual "el cuerpo empieza por %PDF" "%PDF" "$(head -c 4 "$TMP/recibo.pdf")"
# PDFBox guarda el árbol de páginas en flujos comprimidos: se descomprimen
# todos los flujos y se busca el MediaBox. 80 mm son 226,77 puntos.
ANCHO=$(node -e '
  const fs = require("fs"), zlib = require("zlib");
  const b = fs.readFileSync(process.argv[1]);
  const t = b.toString("latin1");
  let texto = t;
  const re = /stream\r?\n/g; let m;
  while ((m = re.exec(t)) !== null) {
    const ini = m.index + m[0].length, fin = t.indexOf("endstream", ini);
    if (fin < 0) break;
    try { texto += "\n" + zlib.inflateSync(b.subarray(ini, fin)).toString("latin1"); } catch (e) {}
  }
  const r = /\/MediaBox\s*\[\s*([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)\s*\]/.exec(texto);
  if (r) console.log((Number(r[3]) - Number(r[1])).toFixed(2));
' "$TMP/recibo.pdf")
[[ -n "$ANCHO" ]] && igual "la página mide 226.77 pt de ancho (80 mm)" "226.77" "$ANCHO" || falla "no se encontró el MediaBox del PDF"
api GET "/caja/ventas/$VENTA_1_ID/recibo" "$T_VEND"
espera "el mismo recibo en JSON" 200
igual "el recibo no lleva costo ni utilidad" "" "$(json 'JSON.stringify(o).match(/costo|utilidad/i)' <<<"$CUERPO")"

# ── 10. Rendimiento con un catálogo de 20.000 referencias ───────────────
paso "10. cuánto tarda: buscar por código y registrar una venta (20.000 referencias)"
MUESTRAS="${SGIP_CAJA_MUESTRAS:-20}"
existentes=$(consulta "SELECT COUNT(*) FROM productos WHERE empresa_id = 1 AND codigo LIKE 'CAJ-VOL-%';")
if [[ "$existentes" -lt 20000 ]]; then
  salida=$(ejecutar "
    SET SESSION cte_max_recursion_depth = 30000;
    INSERT INTO productos (empresa_id, categoria_id, proveedor_id, codigo, sku, nombre, presentacion,
                           costo, existencia, existencia_minima, activo, codigo_barras)
    WITH RECURSIVE n (i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 20000)
    SELECT 1, $CATEGORIA, $PROVEEDOR, CONCAT('CAJ-VOL-', LPAD(i, 5, '0')), CONCAT('CAJ-VOL-', LPAD(i, 5, '0')),
           CONCAT('Referencia de volumen ', i), 'unidad', 1000 + (i MOD 500) * 10, 0, 0, TRUE,
           CONCAT('77', LPAD(i, 11, '0'))
      FROM n
     WHERE NOT EXISTS (SELECT 1 FROM productos p WHERE p.empresa_id = 1 AND p.codigo = CONCAT('CAJ-VOL-', LPAD(n.i, 5, '0')));")
  [[ -z "$salida" ]] || abortar "no se pudo cargar el catálogo de volumen: $salida"
fi
echo "  referencias de la empresa 1: $(consulta "SELECT COUNT(*) FROM productos WHERE empresa_id = 1;")"

mediana_ms () { # lee segundos, uno por línea; imprime «mediana · p95 · máximo» en ms
  node -e 'const a=require("fs").readFileSync(0,"utf8").trim().split(/\s+/).map(Number).sort((x,y)=>x-y);const q=p=>a[Math.min(a.length-1,Math.ceil(p*a.length)-1)];const ms=x=>(x*1000).toFixed(0);console.log("mediana "+ms(q(0.5))+" ms · p95 "+ms(q(0.95))+" ms · máximo "+ms(a[a.length-1])+" ms ("+a.length+" muestras)")'
}

# Una llamada de calentamiento: la primera paga la compilación de la JVM y el pool de conexiones.
curl -s -o /dev/null -H "Authorization: Bearer $T_VEND" "$URL/api/v1/caja/productos?codigo=CAJ-VOL-00001"
tiempos_busqueda=()
for k in $(seq 1 "$MUESTRAS"); do
  n=$(printf '%05d' $(( (k * 997) % 20000 + 1 )))
  t=$(curl -s -o "$TMP/busqueda" -w '%{http_code} %{time_total}' -H "Authorization: Bearer $T_VEND" \
        "$URL/api/v1/caja/productos?codigo=CAJ-VOL-$n")
  [[ "${t%% *}" == "200" ]] || { falla "buscar CAJ-VOL-$n respondió ${t%% *}"; break; }
  tiempos_busqueda+=("${t##* }")
done
echo "  buscar por código ......: $(printf '%s\n' "${tiempos_busqueda[@]}" | mediana_ms)"
TIEMPO_BUSQUEDA=$(printf '%s\n' "${tiempos_busqueda[@]}" | mediana_ms)

tiempos_venta=()
for k in $(seq 1 "$((MUESTRAS + 1))"); do
  cuerpo="{\"claveIdempotencia\":\"$(uuid)\",\"lineas\":[{\"productoId\":$PROD_C,\"cantidad\":1}],\"pagos\":[{\"medioId\":$MEDIO_EFECTIVO,\"monto\":$U_C,\"recibido\":$U_C}]}"
  t=$(curl -s -o "$TMP/venta" -w '%{http_code} %{time_total}' -X POST -H "Authorization: Bearer $T_VEND" \
        -H 'Content-Type: application/json' --data-binary "$cuerpo" "$URL/api/v1/caja/ventas")
  [[ "${t%% *}" == "201" ]] || { falla "la venta de medición $k respondió ${t%% *}: $(cat "$TMP/venta" | head -c 200)"; break; }
  ESPERADO=$((ESPERADO + U_C))                # son ventas del mismo turno: cuentan en el cajón
  (( k > 1 )) && tiempos_venta+=("${t##* }")   # la primera es el calentamiento
done
echo "  registrar una venta ....: $(printf '%s\n' "${tiempos_venta[@]}" | mediana_ms)"
TIEMPO_VENTA=$(printf '%s\n' "${tiempos_venta[@]}" | mediana_ms)
echo "  (cada llamada es una conexión nueva por curl; el tiempo incluye abrirla)"

# ── 11. El cierre con su diferencia ─────────────────────────────────────
paso "11. el cierre del turno con su diferencia"
api GET /caja/turnos/actual/resumen "$T_VEND"
espera "el resumen del turno abierto" 200
igual "la base de efectivo es la del turno" "$BASE" "$(json 'Number(o.baseEfectivo)' <<<"$CUERPO")"
igual "el efectivo esperado es base + cobrado en efectivo - reembolsado en efectivo" "$ESPERADO" "$(json 'Number(o.efectivoEsperado)' <<<"$CUERPO")"
# La misma cuenta, hecha desde la base de datos y no desde la API.
DESDE_BD=$(consulta "
  SELECT c.base_efectivo
       + COALESCE((SELECT SUM(p.monto) FROM pagos_venta p
                     JOIN medios_pago m ON m.empresa_id = p.empresa_id AND m.medio_id = p.medio_id
                    WHERE p.empresa_id = c.empresa_id AND p.cierre_id = c.cierre_id AND m.tipo = 'EFECTIVO'), 0)
       - COALESCE((SELECT SUM(r.monto) FROM devoluciones d
                     JOIN reembolsos r ON r.empresa_id = d.empresa_id AND r.devolucion_id = d.devolucion_id
                     JOIN medios_pago m ON m.empresa_id = r.empresa_id AND m.medio_id = r.medio_id
                    WHERE d.empresa_id = c.empresa_id AND d.cierre_id = c.cierre_id AND m.tipo = 'EFECTIVO'), 0)
    FROM cierres_caja c WHERE c.cierre_id = $TURNO;" | sed 's/\.00$//')
igual "y la base de datos suma lo mismo" "$ESPERADO" "$DESDE_BD"
igual "lo cobrado por transferencia está en el resumen" "$TRANSF_1" "$(json 'Number(o.porMedio.TRANSFERENCIA)' <<<"$CUERPO")"

FALTANTE=2000
CONTADO=$((ESPERADO - FALTANTE))
api POST /caja/turnos/actual/cierre "$T_VEND" "{\"efectivoContado\":$CONTADO}"
espera_4xx "una diferencia sin explicar no cierra el turno"
api POST /caja/turnos/actual/cierre "$T_VEND" "{\"efectivoContado\":$CONTADO,\"observacion\":\"Faltante de $FALTANTE de la verificación\"}"
espera "con la observación, el turno cierra" 200 || abortar "el cierre falló"
igual "el efectivo esperado del cierre" "$ESPERADO" "$(json 'Number(o.efectivoEsperado)' <<<"$CUERPO")"
igual "el efectivo contado del cierre" "$CONTADO" "$(json 'Number(o.efectivoContado)' <<<"$CUERPO")"
igual "la diferencia es el faltante, en negativo" "-$FALTANTE" "$(json 'Number(o.diferencia)' <<<"$CUERPO")"
api POST /caja/turnos/actual/cierre "$T_VEND" "{\"efectivoContado\":$CONTADO,\"observacion\":\"otra vez\"}"
espera "un segundo cierre es un conflicto" 409
api GET /caja/turnos/actual "$T_VEND";  espera "tras cerrar no hay turno abierto" 204
api POST /caja/ventas "$T_VEND" "{\"claveIdempotencia\":\"$(uuid)\",\"lineas\":[{\"productoId\":$PROD_C,\"cantidad\":1}],\"pagos\":[{\"medioId\":$MEDIO_EFECTIVO,\"monto\":$U_C,\"recibido\":$U_C}]}"
espera_4xx "con el turno cerrado no se vende"

paso "12. quién ve qué turnos"
api GET "/caja/turnos?desde=$hoy&hasta=$hoy" "$T_VEND"
espera "el vendedor lista los turnos de hoy" 200
igual "el vendedor ve un solo turno" "1" "$(json 'o.length' <<<"$CUERPO")"
igual "y es el suyo" "$VEND_NOMBRE" "$(json 'o[0].cajero' <<<"$CUERPO")"
igual "ya cerrado, con la diferencia guardada" "-$FALTANTE" "$(json 'Number(o[0].diferencia)' <<<"$CUERPO")"
api GET "/caja/turnos?desde=$hoy&hasta=$hoy" "$T_ADMIN"
igual "la administradora ve el del vendedor y el suyo" "1" \
  "$(json '[o.some(t=>t.cajero==="'"$VEND_NOMBRE"'"),o.some(t=>t.cajero.includes("Administradora de la caja '"$RUN"'"))].every(Boolean)?1:0' <<<"$CUERPO")"

# ── 13. El kárdex cuadra (RNF-10) ───────────────────────────────────────
paso "13. el kárdex cuadra con la existencia de cada producto tocado (RNF-10)"
SUMA="CASE m.tipo
        WHEN 'EGRESO' THEN -m.cantidad
        WHEN 'AJUSTE' THEN m.existencia_posterior - m.existencia_anterior
        ELSE m.cantidad
      END"
TOCADOS="$PROD_A,$PROD_B,$PROD_C,$NUEVO_ID"
descuadres=$(consulta "
  SELECT COUNT(*) FROM (
    SELECT p.producto_id, p.existencia, COALESCE(SUM($SUMA), 0) AS suma
      FROM productos p
      LEFT JOIN movimientos_inventario m ON m.empresa_id = p.empresa_id AND m.producto_id = p.producto_id
     WHERE p.empresa_id = 1 AND p.producto_id IN ($TOCADOS)
     GROUP BY p.producto_id, p.existencia
    HAVING p.existencia <> suma) d;")
igual "ningún producto tocado descuadra con la suma de sus movimientos" "0" "$descuadres"
while IFS=$'\t' read -r codigo existencia suma movimientos; do
  igual "$codigo: existencia = suma del kárdex ($movimientos movimientos)" "$existencia" "$suma"
done < <(consulta "
  SELECT p.codigo, p.existencia, COALESCE(SUM($SUMA), 0), COUNT(m.movimiento_id)
    FROM productos p
    LEFT JOIN movimientos_inventario m ON m.empresa_id = p.empresa_id AND m.producto_id = p.producto_id
   WHERE p.empresa_id = 1 AND p.producto_id IN ($TOCADOS)
   GROUP BY p.producto_id, p.codigo, p.existencia ORDER BY p.producto_id;")
igual "las existencias finales son A 48, B 4, C $((300 - MUESTRAS - 1)) y el producto nuevo -1" \
  "48 4 $((300 - MUESTRAS - 1)) -1" \
  "$(consulta "SELECT GROUP_CONCAT(existencia ORDER BY producto_id SEPARATOR ' ') FROM productos WHERE producto_id IN ($TOCADOS);")"
huecos=$(consulta "
  SELECT COUNT(*) FROM (
    SELECT m.existencia_anterior,
           LAG(m.existencia_posterior) OVER (PARTITION BY m.empresa_id, m.producto_id
                                             ORDER BY m.fecha, m.movimiento_id) AS previo
      FROM movimientos_inventario m
     WHERE m.empresa_id = 1 AND m.producto_id IN ($TOCADOS)) c
   WHERE c.previo IS NOT NULL AND c.previo <> c.existencia_anterior;")
igual "la cadena de movimientos de esos productos no tiene huecos" "0" "$huecos"

# ── Cierre ──────────────────────────────────────────────────────────────
paso "el programa se detiene y no deja nada escuchando"
detener_backend
sleep 1
if puerto_ocupado "$PUERTO"; then falla "el puerto $PUERTO sigue ocupado"; else ok "el puerto $PUERTO quedó libre"; fi

echo
echo "Tiempos con 20.000 referencias (para anotar en el informe):"
echo "  buscar por código ..: $TIEMPO_BUSQUEDA"
echo "  registrar una venta : $TIEMPO_VENTA"
echo
if [[ "$fallos" -eq 0 ]]; then
  echo "TODO EN VERDE"
  exit 0
fi
echo "$fallos comprobación(es) fallaron"
exit 1

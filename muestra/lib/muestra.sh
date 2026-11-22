#!/usr/bin/env bash
# Arranque y parada de la muestra local de SGIP (Windows 11, Git Bash).
#
# Uso:   bash muestra/lib/muestra.sh arrancar | detener
#        source muestra/lib/muestra.sh      (solo define funciones, no hace nada)
#
# Qué hace «arrancar»:
#   1. comprueba Java, Node y mysqld y, si falta algo, lo dice en claro;
#   2. crea su PROPIA base MySQL en .muestra-datos/ (sin contraseña, solo
#      127.0.0.1, puerto libre al azar entre 34000 y 34999);
#   3. carga el esquema y los datos de la tienda de ejemplo (V8) una sola vez;
#   4. arranca el backend (Flyway aplica de la V32 en adelante);
#   5. crea (o repone) los usuarios de la muestra en cada arranque;
#   6. arranca la web y abre el navegador.
#
# Nunca toca el servicio MySQL del usuario ni su base: no usa su puerto, ni
# sus credenciales, ni lee application.yml ni .env. El backend recibe toda
# su configuración por argumentos --spring.datasource.* y variables.
#
# Los procesos se lanzan ocultos y desligados de la ventana (PowerShell
# Start-Process), de modo que cerrar la ventana negra no apaga la muestra.
# Sus PID y puertos quedan en .muestra-datos/estado. Un PID del estado solo
# se mata si se prueba que es de esta muestra (ver es_nuestro).

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DATOS="${MUESTRA_DATOS:-$RAIZ/.muestra-datos}"
ESTADO="$DATOS/estado"

# Todo borrado (rm -rf) cuelga de DATOS: tiene que ser la carpeta propia de
# la muestra y nada más.
case "$DATOS" in
  */.muestra-datos) ;;
  *) echo "La carpeta de datos de la muestra debe llamarse .muestra-datos (ahora: '${DATOS}')." >&2
     return 1 2>/dev/null || exit 1 ;;
esac

# Usuarios de la muestra. Son claves de prueba SOLO locales; la guía para
# quien presenta está en muestra/LEEME.md (y la prueba las lee de aquí).
MUESTRA_CORREO_ADMIN="admin@muestra.com"
MUESTRA_CLAVE_ADMIN="Admin-Muestra-26"
MUESTRA_CORREO_VENDEDOR="vendedor@muestra.com"
MUESTRA_CLAVE_VENDEDOR="Vende-Muestra-26"
MUESTRA_CORREO_PLATAFORMA="plataforma@muestra.com"
MUESTRA_CLAVE_PLATAFORMA="Plata-Muestra-26"
MUESTRA_PIN_ADMIN="4829"

# Que nada del entorno del usuario se cuele en la conexión a la base.
unset MYSQL_PWD MYSQL_HOST MYSQL_TCP_PORT

# ---- Mensajes -----------------------------------------------------------
decir()  { echo "$*"; }
fallar() { echo >&2; echo "No se pudo continuar: $*" >&2; _FALLO=1; return 1; }

# ---- Utilidades de proceso y puerto -------------------------------------
proceso_vivo() { # proceso_vivo PID [imagen.exe]
  [[ "${1:-}" =~ ^[0-9]+$ ]] || return 1
  local salida
  salida=$(tasklist //FI "PID eq $1" //NH 2>/dev/null | tr -d '\r')
  echo "$salida" | grep -qE "[[:space:]]$1[[:space:]]" || return 1
  [[ -z "${2:-}" ]] || echo "$salida" | grep -qi "^$2"
}

puerto_escucha() {
  [[ -n "${1:-}" ]] || return 1
  # Sin «-p TCP»: así también cuentan los que escuchan en IPv6 ([::1]).
  netstat -ano 2>/dev/null | tr -d '\r' \
    | grep -qE ":$1[[:space:]]+[^[:space:]]+[[:space:]]+LISTENING"
}

puerto_pid() { # PID que escucha en el puerto (vacío si nadie)
  [[ -n "${1:-}" ]] || return 0
  netstat -ano 2>/dev/null | tr -d '\r' \
    | awk -v p=":$1" '$4=="LISTENING" && substr($2,length($2)-length(p)+1)==p {print $5; exit}'
}

puerto_libre_al_azar() { # imprime un puerto libre entre 34000 y 34999
  local p intentos=0
  while ((intentos < 200)); do
    p=$((34000 + RANDOM % 1000))
    if ! puerto_escucha "$p" && [[ " ${_ELEGIDOS:-} " != *" $p "* ]]; then
      _ELEGIDOS="${_ELEGIDOS:-} $p"
      echo "$p"; return 0
    fi
    intentos=$((intentos + 1))
  done
  return 1
}

winpath() { cygpath -m "$1"; }

linea_de_comando() { # línea de comando de un PID de Windows
  [[ "${1:-}" =~ ^[0-9]+$ ]] || return 0
  timeout 20 powershell.exe -NoProfile -NonInteractive -Command \
    "(Get-CimInstance Win32_Process -Filter 'ProcessId=$1').CommandLine" 2>/dev/null </dev/null | tr -d '\r'
}

# Un PID del archivo de estado puede ser viejo y haber pasado a otro
# programa (el MySQL del usuario, por ejemplo). Nada se mata sin probar
# antes que es de ESTA muestra: o lo lanzamos en esta misma ejecución, o su
# línea de comando lleva la carpeta de datos de esta muestra (mysqld, java),
# o es el Vite de la muestra en su puerto.
es_nuestro() { # es_nuestro PID imagen.exe [puerto]
  proceso_vivo "$1" "$2" || return 1
  [[ " ${_LANZADOS:-} " == *" $1 "* ]] && return 0
  local c d
  c=$(linea_de_comando "$1"); d="$(winpath "$DATOS")"
  case "$2" in
    node.exe) [[ "$c" == *vite.js* && "$c" == *"$(winpath "$RAIZ/web")"*                  && "$c" == *"--port ${3:-x} --strictPort"* ]] ;;
    *)        [[ "$c" == *"$d"* ]] ;;
  esac
}

# Lanza un programa oculto y desligado de esta ventana; imprime su PID de Windows.
# El PID vuelve por un archivo y no por la salida de PowerShell: si volviera
# por una tubería, quien llama esperaría hasta que el programa lanzado
# terminara (hereda el extremo de la tubería) y el arranque se colgaría.
lanzar_oculto() { # lanzar_oculto exe "argumentos" carpeta registro
  local pidf pid
  pidf="$(dirname "$4")/.pid.$$.$RANDOM"
  MZ_EXE="$(winpath "$1")" MZ_ARGS="$2" MZ_DIR="$(winpath "$3")" MZ_LOG="$(winpath "$4")" \
  MZ_PID="$(winpath "$pidf")" \
  timeout 60 powershell.exe -NoProfile -NonInteractive -Command '
    $p = Start-Process -FilePath $env:MZ_EXE -ArgumentList $env:MZ_ARGS `
         -WorkingDirectory $env:MZ_DIR -WindowStyle Hidden `
         -RedirectStandardOutput $env:MZ_LOG -RedirectStandardError ($env:MZ_LOG + ".err") -PassThru
    [IO.File]::WriteAllText($env:MZ_PID, [string]$p.Id)' >/dev/null 2>&1 </dev/null
  pid=$(tr -d '\r\n' < "$pidf" 2>/dev/null); rm -f "$pidf"
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  echo "$pid"
}

# ---- Estado en disco ----------------------------------------------------
limpiar_estado_en_memoria() {
  MYSQL_PID=""; MYSQL_PUERTO=""; BACK_PID=""; BACK_PUERTO=""; WEB_PID=""; WEB_PUERTO=""
}
leer_estado() {
  limpiar_estado_en_memoria
  # shellcheck disable=SC1090
  [[ -f "$ESTADO" ]] && source "$ESTADO"
  export MYSQL_PUERTO
  return 0
}
guardar_estado() {
  mkdir -p "$DATOS"
  {
    echo "MYSQL_PID=${MYSQL_PID:-}"; echo "MYSQL_PUERTO=${MYSQL_PUERTO:-}"
    echo "BACK_PID=${BACK_PID:-}";   echo "BACK_PUERTO=${BACK_PUERTO:-}"
    echo "WEB_PID=${WEB_PID:-}";     echo "WEB_PUERTO=${WEB_PUERTO:-}"
  } > "$ESTADO.nuevo" && mv -f "$ESTADO.nuevo" "$ESTADO"
}

# ---- Cerrojo contra el arranque doble ------------------------------------
liberar_cerrojo() {
  if [[ -n "${_CERROJO:-}" && "$_CERROJO" == */.muestra-datos/.lock ]]; then
    rm -rf "$_CERROJO"
  fi
}

# 0 = lo tenemos; 2 = otra ejecución viva ya está arrancando;
# 3 = no se pudo conseguir ni limpiando un cerrojo viejo.
adquirir_cerrojo() {
  local lock="$DATOS/.lock" yo dueno intento
  yo=$(cat /proc/$$/winpid 2>/dev/null || echo $$)
  for intento in 1 2; do
    if mkdir "$lock" 2>/dev/null; then
      echo "$yo" > "$lock/pid"
      _CERROJO="$lock"
      trap liberar_cerrojo EXIT
      trap 'exit 130' INT TERM
      return 0
    fi
    dueno=$(tr -d '\r\n' < "$lock/pid" 2>/dev/null)
    if [[ -z "$dueno" ]]; then sleep 1; dueno=$(tr -d '\r\n' < "$lock/pid" 2>/dev/null); fi
    if [[ -n "$dueno" ]] && proceso_vivo "$dueno" bash.exe \
       && [[ "$(linea_de_comando "$dueno")" == *muestra.sh* ]]; then
      decir "La muestra ya se está iniciando, espera unos minutos."
      return 2
    fi
    # Cerrojo viejo: su dueño ya no existe o no es una ejecución de la muestra.
    [[ "$lock" == */.muestra-datos/.lock ]] && rm -rf "$lock"
  done
  return 3
}

# ---- Herramientas -------------------------------------------------------
buscar_mysql() {
  local d
  MYSQLD_BIN=""; MYSQL_BIN=""
  for d in "$(command -v mysqld 2>/dev/null | xargs -r dirname)" \
           "/c/Program Files/MySQL"/MySQL\ Server\ */bin; do
    [[ -n "$d" && -x "$d/mysqld.exe" && -x "$d/mysql.exe" ]] || continue
    MYSQLD_BIN="$d/mysqld.exe"; MYSQL_BIN="$d/mysql.exe"
    break   # gana el mysqld del PATH; si no, el primero de Program Files
  done
  [[ -n "$MYSQLD_BIN" ]]
}

comprobar_herramientas() {
  local faltan=0 v
  if ! command -v java >/dev/null 2>&1; then
    echo "Falta Java. Instala Java 25 (Temurin/Adoptium) y vuelve a abrir este archivo." >&2
    faltan=1
  else
    # Se salta «Picked up JAVA_TOOL_OPTIONS…» y cualquier otra línea previa.
    v=$(java -version 2>&1 | grep -m1 ' version "' | sed -E 's/.*version "([0-9]+).*/\1/')
    if [[ ! "$v" =~ ^[0-9]+$ || "$v" -lt 25 ]]; then
      echo "Tu Java es demasiado viejo (versión ${v:-?}). Hace falta Java 25 o superior." >&2
      faltan=1
    fi
  fi
  if ! command -v node >/dev/null 2>&1; then
    echo "Falta Node.js. Instala Node 24 y vuelve a abrir este archivo." >&2
    faltan=1
  fi
  if ! buscar_mysql; then
    echo "Falta MySQL Server 9.7 (el programa mysqld). Instálalo; la muestra usa su propia copia aparte y no toca tus datos." >&2
    faltan=1
  fi
  [[ $faltan -eq 0 ]] || return 1
  v=$(node -v 2>/dev/null | sed -E 's/^v([0-9]+).*/\1/')
  [[ "$v" == "24" ]] || echo "Aviso: la muestra se probó con Node 24 y tienes Node ${v:-?}. Seguimos." >&2
  v=$("$MYSQLD_BIN" --no-defaults --version 2>/dev/null | sed -nE 's/.*Ver ([0-9]+\.[0-9]+).*/\1/p' | head -1)
  [[ "$v" == "9.7" ]] || echo "Aviso: la muestra se probó con MySQL 9.7 y tienes ${v:-?}. Seguimos." >&2
  JAVA_BIN="$(command -v java)"; NODE_BIN="$(command -v node)"
  export MUESTRA_MYSQL_BIN="$MYSQL_BIN"
  return 0
}

# Cliente mysql contra LA BASE DE LA MUESTRA: sin leer archivos de
# configuración del usuario, siempre por TCP a 127.0.0.1 y a su puerto.
mysql_muestra() {
  "${MUESTRA_MYSQL_BIN:-mysql}" --no-defaults --protocol=tcp -h127.0.0.1 -P"${MYSQL_PUERTO:?}" -uroot "$@"
}
# Con este nombre lo encuentra database/aplicar.sh (los guiones hijos ven
# las funciones exportadas), de modo que no puede usar otro mysql ni otra base.
mysql() { mysql_muestra "$@"; }

# Al hacer «source» (la prueba) también hace falta saber dónde está mysql.
buscar_mysql && export MUESTRA_MYSQL_BIN="$MYSQL_BIN"

# ---- Pasos de arranque --------------------------------------------------
iniciar_mysql() {
  local dd="$DATOS/mysql" log="$DATOS/registros"
  mkdir -p "$log"
  if [[ ! -f "$DATOS/mysql.inicializado" ]]; then
    decir "Preparando la base de datos de la muestra (solo la primera vez, puede tardar un minuto)…"
    # Solo se borra la ruta exacta de la base de la muestra.
    [[ "$dd" == */.muestra-datos/mysql ]] || { fallar "ruta de datos inesperada."; return 1; }
    rm -rf "$dd"
    "$MYSQLD_BIN" --no-defaults --initialize-insecure \
      "--datadir=$(winpath "$dd")" "--log-error=$(winpath "$log/mysql-init.log")" \
      >/dev/null 2>&1 || { fallar "no se pudo preparar la base. Mira $log/mysql-init.log"; return 1; }
    touch "$DATOS/mysql.inicializado"
  fi
  MYSQL_PUERTO=$(puerto_libre_al_azar) || { fallar "no encontré un puerto libre."; return 1; }
  export MYSQL_PUERTO
  decir "Iniciando la base de datos de la muestra…"
  MYSQL_PID=$(lanzar_oculto "$MYSQLD_BIN" \
    "--no-defaults \"--datadir=$(winpath "$dd")\" --port=$MYSQL_PUERTO --bind-address=127.0.0.1 --mysqlx=OFF \"--log-error=$(winpath "$log/mysql.log")\" --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci --innodb-buffer-pool-size=128M" \
    "$DATOS" "$log/mysql-salida.log") || { fallar "no se pudo iniciar la base de la muestra."; return 1; }
  _LANZADOS="${_LANZADOS:-} $MYSQL_PID"
  guardar_estado
  local i
  for i in $(seq 1 60); do
    mysql_muestra -N -e "SELECT 1" >/dev/null 2>&1 && return 0
    proceso_vivo "$MYSQL_PID" mysqld.exe || { fallar "la base se cerró sola. Mira $log/mysql.log"; return 1; }
    sleep 2
  done
  fallar "la base no respondió a tiempo. Mira $log/mysql.log"
}

cargar_esquema_y_tienda() {
  [[ -f "$DATOS/esquema.cargado" ]] && return 0
  decir "Cargando la tienda de ejemplo (solo la primera vez)…"
  export -f mysql mysql_muestra
  # --recrear es seguro aquí: es la base propia de la muestra, y así un
  # intento anterior cortado a medias no estorba.
  bash "$RAIZ/database/aplicar.sh" --recrear --demo >"$DATOS/registros/aplicar.log" 2>&1 \
    || { fallar "no se pudo crear la tienda de ejemplo. Mira $DATOS/registros/aplicar.log"; return 1; }
  touch "$DATOS/esquema.cargado"
}

jar_del_backend() {
  ls "$RAIZ"/backend/target/backend-*.jar 2>/dev/null | grep -v '\.original$' | head -1
}

construir_backend() {
  local jar
  jar=$(jar_del_backend)
  if [[ -z "$jar" || -n "$(find "$RAIZ/backend/src" "$RAIZ/backend/pom.xml" -newer "$jar" -print -quit 2>/dev/null)" ]]; then
    decir "Preparando el programa (la primera vez tarda varios minutos; espera, no cierres esta ventana)…"
    (cd "$RAIZ/backend" && ./mvnw -B -q -DskipTests package) >"$DATOS/registros/construccion.log" 2>&1 \
      || { fallar "no se pudo preparar el programa. Mira $DATOS/registros/construccion.log"; return 1; }
    jar=$(jar_del_backend)
  fi
  [[ -n "$jar" ]] || { fallar "no encontré el programa ya construido."; return 1; }
  BACK_JAR="$jar"
}

backend_responde() { # 401 sin sesión = el backend está arriba
  [[ "$(curl -s -m 3 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$BACK_PUERTO/api/v1/productos" 2>/dev/null)" == "401" ]]
}

iniciar_backend() {
  construir_backend || return 1
  BACK_PUERTO=$(puerto_libre_al_azar) || { fallar "no encontré un puerto libre."; return 1; }
  mkdir -p "$DATOS/archivos"
  decir "Iniciando el sistema (aplica las últimas migraciones)…"
  local url="jdbc:mysql://127.0.0.1:$MYSQL_PUERTO/sgip?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Bogota"
  export SGIP_ARCHIVOS_RAIZ="$(winpath "$DATOS/archivos")"
  # -Dmuestra.datos no lo lee nadie: solo deja la carpeta de datos en la
  # línea de comando para poder probar luego que el proceso es de la muestra.
  BACK_PID=$(lanzar_oculto "$JAVA_BIN" \
    "\"-Dmuestra.datos=$(winpath "$DATOS")\" -jar \"$(winpath "$BACK_JAR")\" --server.port=$BACK_PUERTO --server.address=127.0.0.1 \"--spring.datasource.url=$url\" --spring.datasource.username=root --spring.datasource.password= --spring.jpa.open-in-view=false" \
    "$DATOS" "$DATOS/registros/backend.log") || { fallar "no se pudo iniciar el sistema."; return 1; }
  _LANZADOS="${_LANZADOS:-} $BACK_PID"
  guardar_estado
  local i
  for i in $(seq 1 120); do
    backend_responde && return 0
    proceso_vivo "$BACK_PID" java.exe || { fallar "el sistema se cerró solo. Mira $DATOS/registros/backend.log"; return 1; }
    sleep 2
  done
  fallar "el sistema no respondió a tiempo. Mira $DATOS/registros/backend.log"
}

hash_de() { # PBKDF2 en el mismo formato que util/Seguridad.derivar del backend
  node -e 'const c=require("crypto");const s=c.randomBytes(16);const h=c.pbkdf2Sync(process.argv[1],s,120000,32,"sha256");const b=x=>x.toString("base64").replace(/=+$/,"");console.log("pbkdf2$120000$"+b(s)+"$"+b(h))' "$1"
}

# En cada arranque y repetible: repone claves, PIN y desbloqueos, y así la
# muestra siempre abre con los mismos usuarios.
cargar_usuarios() {
  decir "Preparando los usuarios de la muestra…"
  {
    echo "SET NAMES utf8mb4;"
    echo "SET @correo_admin='$MUESTRA_CORREO_ADMIN', @correo_vendedor='$MUESTRA_CORREO_VENDEDOR', @correo_plataforma='$MUESTRA_CORREO_PLATAFORMA';"
    echo "SET @h_admin='$(hash_de "$MUESTRA_CLAVE_ADMIN")';"
    echo "SET @h_vendedor='$(hash_de "$MUESTRA_CLAVE_VENDEDOR")';"
    echo "SET @h_plataforma='$(hash_de "$MUESTRA_CLAVE_PLATAFORMA")';"
    echo "SET @h_pin='$(hash_de "$MUESTRA_PIN_ADMIN")';"
    cat "$RAIZ/muestra/datos/usuarios_muestra.sql"
  } | mysql_muestra sgip >"$DATOS/registros/usuarios.log" 2>&1 \
    || { fallar "no se pudieron preparar los usuarios. Mira $DATOS/registros/usuarios.log"; return 1; }
}

iniciar_web() {
  if [[ ! -d "$RAIZ/web/node_modules" ]]; then
    decir "Preparando la pantalla (solo la primera vez; espera)…"
    (cd "$RAIZ/web" && npm ci --no-audit --no-fund) >"$DATOS/registros/npm.log" 2>&1 \
      || { fallar "no se pudo preparar la pantalla. Mira $DATOS/registros/npm.log"; return 1; }
  fi
  if ! puerto_escucha 5173; then
    WEB_PUERTO=5173
  else
    WEB_PUERTO=$(puerto_libre_al_azar) || { fallar "no encontré un puerto libre."; return 1; }
  fi
  decir "Iniciando la pantalla…"
  export SGIP_API_URL="http://127.0.0.1:$BACK_PUERTO"
  WEB_PID=$(lanzar_oculto "$NODE_BIN" \
    "\"$(winpath "$RAIZ/web/node_modules/vite/bin/vite.js")\" --host 127.0.0.1 --port $WEB_PUERTO --strictPort" \
    "$RAIZ/web" "$DATOS/registros/web.log") || { fallar "no se pudo iniciar la pantalla."; return 1; }
  _LANZADOS="${_LANZADOS:-} $WEB_PID"
  guardar_estado
  local i
  for i in $(seq 1 45); do
    [[ "$(curl -s -m 3 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$WEB_PUERTO/" 2>/dev/null)" == "200" ]] && return 0
    proceso_vivo "$WEB_PID" node.exe || { fallar "la pantalla se cerró sola. Mira $DATOS/registros/web.log"; return 1; }
    sleep 1
  done
  fallar "la pantalla no respondió a tiempo. Mira $DATOS/registros/web.log"
}

abrir_navegador() {
  [[ -n "${MUESTRA_SIN_NAVEGADOR:-}" ]] && return 0
  cmd.exe //c start "" "http://127.0.0.1:$WEB_PUERTO" >/dev/null 2>&1
  return 0
}

en_marcha() {
  [[ -n "$MYSQL_PID" && -n "$BACK_PID" && -n "$WEB_PID" ]] || return 1
  es_nuestro "$MYSQL_PID" mysqld.exe && es_nuestro "$BACK_PID" java.exe \
    && es_nuestro "$WEB_PID" node.exe "$WEB_PUERTO" && puerto_escucha "$WEB_PUERTO" \
    && puerto_escucha "$BACK_PUERTO" && puerto_escucha "$MYSQL_PUERTO"
}

# ---- Órdenes ------------------------------------------------------------
arrancar() {
  _FALLO=0
  comprobar_herramientas || return 1
  mkdir -p "$DATOS/registros"
  adquirir_cerrojo
  case $? in
    0) ;;
    2) return 0 ;;
    *) echo "No se pudo iniciar: otra ejecución tiene el cerrojo; intenta de nuevo en un minuto." >&2
       return 1 ;;
  esac
  leer_estado
  if en_marcha; then
    decir "La muestra ya está en marcha."
    decir "Listo: abre http://127.0.0.1:$WEB_PUERTO"
    abrir_navegador
    return 0
  fi
  if [[ -f "$ESTADO" ]]; then
    decir "Había una ejecución anterior a medias o ya apagada; la limpio primero…"
    detener
  fi
  limpiar_estado_en_memoria
  iniciar_mysql && cargar_esquema_y_tienda && iniciar_backend \
    && cargar_usuarios && iniciar_web
  if [[ $_FALLO -ne 0 ]]; then
    detener >/dev/null 2>&1
    return 1
  fi
  decir "Listo: abre http://127.0.0.1:$WEB_PUERTO"
  decir "(Usuarios y claves: muestra/LEEME.md. Para apagar todo: detener-muestra.cmd)"
  abrir_navegador
  return 0
}

matar_arbol() { # matar_arbol PID
  taskkill //PID "$1" //T //F >/dev/null 2>&1
  return 0
}

# Clasifica un PID del estado: 0 = es nuestro (hay que apagarlo);
# 1 = ya no existe; 2 = existe pero NO es de la muestra (no se toca).
clasificar() { # clasificar "nombre" PID imagen.exe [puerto]
  [[ -n "$2" ]] || return 1
  proceso_vivo "$2" || return 1
  if es_nuestro "$2" "$3" "${4:-}"; then return 0; fi
  decir "El registro anterior apuntaba a $1 (PID $2), que no es de la muestra; no lo toco."
  return 2
}

detener() {
  if [[ ! -f "$ESTADO" ]]; then
    decir "La muestra no estaba en marcha."
    return 0
  fi
  leer_estado
  decir "Apagando la muestra…"
  local web=1 back=1 base=1
  clasificar "la pantalla" "$WEB_PID" node.exe "$WEB_PUERTO"; web=$?
  clasificar "el sistema" "$BACK_PID" java.exe; back=$?
  clasificar "la base de datos" "$MYSQL_PID" mysqld.exe; base=$?
  [[ $web -eq 0 ]] && matar_arbol "$WEB_PID"
  [[ $back -eq 0 ]] && matar_arbol "$BACK_PID"
  if [[ $base -eq 0 ]]; then
    # Primero el apagado ordenado, para no dejar la base a medias.
    if buscar_mysql && [[ -n "$MYSQL_PUERTO" ]]; then
      MUESTRA_MYSQL_BIN="$MYSQL_BIN" mysql_muestra -e "SHUTDOWN" >/dev/null 2>&1
    fi
    local i
    for i in $(seq 1 30); do
      proceso_vivo "$MYSQL_PID" mysqld.exe || break
      sleep 1
    done
    proceso_vivo "$MYSQL_PID" mysqld.exe && matar_arbol "$MYSQL_PID"
  fi
  # Solo cuenta como resto lo que escuche un proceso que era nuestro.
  local restos=0 dueno
  for par in "$web:$WEB_PUERTO:$WEB_PID" "$back:$BACK_PUERTO:$BACK_PID" "$base:$MYSQL_PUERTO:$MYSQL_PID"; do
    IFS=: read -r cls pto pid <<<"$par"
    [[ "$cls" == "0" && -n "$pto" ]] || continue
    dueno=$(puerto_pid "$pto")
    [[ -n "$dueno" && "$dueno" == "$pid" ]] && restos=1
  done
  rm -f "$ESTADO"
  if [[ $restos -eq 1 ]]; then
    echo "Algo sigue escuchando; vuelve a ejecutar detener-muestra.cmd." >&2
    return 1
  fi
  decir "Listo: todo apagado."
}

# Solo se ejecuta si lo llaman directamente (no al hacer «source»).
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  case "${1:-}" in
    arrancar) arrancar ;;
    detener)  detener ;;
    *) echo "Uso: muestra.sh arrancar | detener" >&2; exit 2 ;;
  esac
  exit $?
fi

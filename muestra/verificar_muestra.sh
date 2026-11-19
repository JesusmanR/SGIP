#!/usr/bin/env bash
# Prueba de punta a punta del arranque de la muestra.
#
# Detiene todo, arranca, espera al backend, entra como administrador y como
# vendedor, pide una página del catálogo, detiene y comprueba que no queda
# nada escuchando, arranca de nuevo y comprueba que los datos no se
# duplicaron ni se cargaron dos veces. Termina dejando todo detenido.
#
# Uso:  bash muestra/verificar_muestra.sh
set -u

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="$AQUI/lib/muestra.sh"
FALLOS=0

ok()    { echo "  OK    $*"; }
fallo() { echo "  FALLO $*"; FALLOS=$((FALLOS + 1)); }
paso()  { echo; echo "== $*"; }
comprobar() { # comprobar "descripción" comando...
  local d="$1"; shift
  if "$@" >/dev/null 2>&1; then ok "$d"; else fallo "$d"; fi
}

if [[ ! -f "$LIB" ]]; then
  echo "FALLO: no existe $LIB (esta es la prueba en rojo: aún no hay arranque)."
  exit 1
fi
# Al ser «sourced» solo define funciones y constantes; no arranca nada.
# shellcheck source=lib/muestra.sh
source "$LIB"

export MUESTRA_SIN_NAVEGADOR=1

json() { node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>{try{const o=JSON.parse(d);const v=($1);process.stdout.write(v===undefined||v===null?'':String(v))}catch(e){}})"; }

ingresar() { # ingresar correo clave -> token
  curl -s -m 20 -X POST "http://127.0.0.1:$BACK_PUERTO/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"correo\":\"$1\",\"clave\":\"$2\"}" | json 'o.token'
}

esperar_backend() { # hasta 180 s
  local i
  for i in $(seq 1 90); do
    local c
    c=$(curl -s -m 3 -o /dev/null -w '%{http_code}' \
        "http://127.0.0.1:$BACK_PUERTO/api/v1/productos" 2>/dev/null)
    [[ "$c" == "401" ]] && return 0
    sleep 2
  done
  return 1
}

contar() { # contar "SQL" -> número
  mysql_muestra -N -B -e "$1" sgip 2>/dev/null | tr -d '\r'
}

# Gancho de la rama de la muestra: allí existe la caja y esta función abre
# un turno con base 0 y registra una venta de contado (esperando 201).
# En develop todavía no existe /api/v1/caja/turnos y se omite.
verificar_caja() { # verificar_caja token_vendedor
  local codigo
  codigo=$(curl -s -m 10 -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer $1" "http://127.0.0.1:$BACK_PUERTO/api/v1/caja/turnos")
  if [[ "$codigo" == "404" ]]; then
    echo "  OMITIDO la caja: /api/v1/caja/turnos no existe en esta rama"
    return 0
  fi
  # PENDIENTE (rama de la muestra): abrir turno con base 0 y registrar una
  # venta en efectivo de un producto del catálogo. Mientras no se escriba,
  # fallar a propósito para que nadie dé por probada una caja que no se probó.
  fallo "la caja existe (HTTP $codigo) pero verificar_caja() no está implementada"
}

paso "1. Detener y arrancar"
bash "$LIB" detener >/dev/null 2>&1
comprobar "arranca sin error" bash "$LIB" arrancar
leer_estado
BACK_1="${BACK_PID:-}"; WEB_1="${WEB_PID:-}"; MYSQL_1="${MYSQL_PID:-}"
PUERTOS=("${MYSQL_PUERTO:-0}" "${BACK_PUERTO:-0}" "${WEB_PUERTO:-0}")
echo "  puertos: mysql=${MYSQL_PUERTO:-?} backend=${BACK_PUERTO:-?} web=${WEB_PUERTO:-?}"

paso "2. El backend responde"
comprobar "backend sano" esperar_backend

paso "3. Entrar como administrador y como vendedor"
T_ADMIN=$(ingresar "$MUESTRA_CORREO_ADMIN" "$MUESTRA_CLAVE_ADMIN")
[[ -n "$T_ADMIN" ]] && ok "administrador entra" || fallo "administrador no entra"
T_VEND=$(ingresar "$MUESTRA_CORREO_VENDEDOR" "$MUESTRA_CLAVE_VENDEDOR")
[[ -n "$T_VEND" ]] && ok "vendedor entra" || fallo "vendedor no entra"
T_PLAT=$(ingresar "$MUESTRA_CORREO_PLATAFORMA" "$MUESTRA_CLAVE_PLATAFORMA")
[[ -n "$T_PLAT" ]] && ok "usuario de plataforma entra" || fallo "plataforma no entra"
CAMBIO=$(curl -s -m 20 -X POST "http://127.0.0.1:$BACK_PUERTO/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"correo\":\"$MUESTRA_CORREO_ADMIN\",\"clave\":\"$MUESTRA_CLAVE_ADMIN\"}" | json 'o.debeCambiarClave')
[[ "$CAMBIO" == "false" ]] && ok "no obliga a cambiar la clave" || fallo "obliga a cambiar la clave ($CAMBIO)"

paso "4. Una página del catálogo con productos"
N=$(curl -s -m 20 -H "Authorization: Bearer $T_VEND" \
  "http://127.0.0.1:$BACK_PUERTO/api/v1/productos?limite=50" | json 'Array.isArray(o)?o.length:-1')
[[ "${N:-0}" -ge 1 ]] && ok "el catálogo trae $N productos" || fallo "el catálogo no trae productos ($N)"
W=$(curl -s -m 20 "http://127.0.0.1:$WEB_PUERTO/api/v1/productos" -o /dev/null -w '%{http_code}')
[[ "$W" == "401" ]] && ok "la web reenvía /api al backend" || fallo "la web no reenvía /api (HTTP $W)"
verificar_caja "$T_VEND"

paso "5. Detener: no queda nada escuchando"
PRODUCTOS_1=$(contar "SELECT COUNT(*) FROM productos")
USUARIOS_1=$(contar "SELECT COUNT(*) FROM usuarios")
CLIENTES_1=$(contar "SELECT COUNT(*) FROM clientes")
comprobar "detiene sin error" bash "$LIB" detener
sleep 2
for p in "${PUERTOS[@]}"; do
  if puerto_escucha "$p"; then fallo "el puerto $p sigue escuchando"; else ok "puerto $p libre"; fi
done
for pid in "$BACK_1" "$WEB_1" "$MYSQL_1"; do
  if [[ -n "$pid" ]] && proceso_vivo "$pid"; then fallo "el proceso $pid sigue vivo"; else ok "proceso ${pid:-?} terminado"; fi
done

paso "6. Arrancar otra vez: sin duplicados"
comprobar "arranca de nuevo" bash "$LIB" arrancar
leer_estado
comprobar "backend sano otra vez" esperar_backend
PRODUCTOS_2=$(contar "SELECT COUNT(*) FROM productos")
USUARIOS_2=$(contar "SELECT COUNT(*) FROM usuarios")
CLIENTES_2=$(contar "SELECT COUNT(*) FROM clientes")
[[ -n "$PRODUCTOS_1" && "$PRODUCTOS_1" == "$PRODUCTOS_2" ]] && ok "productos: $PRODUCTOS_2" || fallo "productos $PRODUCTOS_1 -> $PRODUCTOS_2"
[[ -n "$USUARIOS_1" && "$USUARIOS_1" == "$USUARIOS_2" ]] && ok "usuarios: $USUARIOS_2" || fallo "usuarios $USUARIOS_1 -> $USUARIOS_2"
[[ -n "$CLIENTES_1" && "$CLIENTES_1" == "$CLIENTES_2" ]] && ok "clientes: $CLIENTES_2" || fallo "clientes $CLIENTES_1 -> $CLIENTES_2"
DUP=$(contar "SELECT COUNT(*) FROM (SELECT correo FROM usuarios GROUP BY correo HAVING COUNT(*)>1) x")
[[ "$DUP" == "0" ]] && ok "ningún correo repetido" || fallo "correos repetidos: $DUP"
T2=$(ingresar "$MUESTRA_CORREO_VENDEDOR" "$MUESTRA_CLAVE_VENDEDOR")
[[ -n "$T2" ]] && ok "el vendedor sigue entrando" || fallo "el vendedor no entra tras reiniciar"

paso "7. Arranque doble: solo abre el navegador"
BACK_2="$BACK_PID"; WEB_2="$WEB_PID"
SALIDA=$(bash "$LIB" arrancar 2>&1)
echo "$SALIDA" | grep -qi "ya está en marcha" && ok "avisa que ya estaba en marcha" || fallo "no detectó el arranque doble"
leer_estado
[[ "$BACK_PID" == "$BACK_2" && "$WEB_PID" == "$WEB_2" ]] && ok "los procesos son los mismos" || fallo "se duplicaron procesos"

paso "Cierre"
PUERTOS=("$MYSQL_PUERTO" "$BACK_PUERTO" "$WEB_PUERTO")
bash "$LIB" detener >/dev/null 2>&1
sleep 2
for p in "${PUERTOS[@]}"; do
  if puerto_escucha "$p"; then fallo "el puerto $p sigue escuchando al final"; fi
done

echo
if [[ $FALLOS -eq 0 ]]; then echo "RESULTADO: todo bien (0 fallos)"; exit 0; fi
echo "RESULTADO: $FALLOS fallo(s)"; exit 1

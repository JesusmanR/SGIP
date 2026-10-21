#!/usr/bin/env python3
"""
Pasa por el analizador de MySQL todas las sentencias escritas a mano.

   python3 verificar_sql_del_backend.py -u root -p
   python3 verificar_sql_del_backend.py --socket=/tmp/my.sock

Los argumentos se entregan tal cual al cliente `mysql`, como en los
guiones de al lado. Se ejecuta sobre una base con la cadena aplicada.

## Por qué existe

El backend tiene dos clases de acceso a datos. Las entidades JPA las
valida Hibernate al arrancar, porque la aplicación corre con `ddl-auto`
en `validate` y se niega a levantar si una columna no cuadra. Las
sentencias que los repositorios escriben a mano con `JdbcClient` no las
valida nadie: son cadenas de texto hasta que alguien las ejecuta.

Y nadie las ejecutaba. Las pruebas de unidad simulan `JdbcClient` a
propósito —lo que comprueban es la lógica de alrededor, no el SQL— y la
única prueba que toca una base de verdad es `contextLoads`, que arranca
el contexto y no pasa por ninguna consulta. De modo que una columna mal
escrita en cualquiera de los repositorios compilaba, pasaba la entrega
en verde y reventaba la primera vez que un usuario abriera esa pantalla.

`PREPARE` resuelve justo eso: el servidor analiza la sentencia, comprueba
que las tablas y las columnas existen y que la sintaxis es válida, y no
ejecuta nada. No hace falta ni una fila de datos.

## Lo que NO comprueba

Que la consulta devuelva lo correcto. Una columna que existe pero no es
la que tocaba pasa por aquí sin ruido, igual que un `WHERE` que acota
mal. Para eso están los guiones de verificación de al lado, que sí
comparan resultados contra las cifras de los requisitos.

Tampoco comprueba una sentencia que este arnés no sepa reconstruir: si
alguna se arma de una forma nueva, se cuenta aparte y el guion termina
en rojo, para que no pase inadvertida.
"""
import re
import subprocess
import sys
from pathlib import Path

RAIZ = (Path(__file__).resolve().parent.parent
        / 'backend/src/main/java/co/com/bspstore/sgip/datos')

# Por dónde entra SQL escrito a mano.
#
# `jdbc.sql(` era el único al principio, y eso dejaba una puerta abierta:
# una sentencia enviada por lotes con `batchUpdate` no la analizaba
# nadie, y habría pasado la entrega en verde sin que nadie la mirara,
# que es justo lo que este guion existe para impedir. Al añadir una
# forma de enviar SQL hay que añadirla aquí.
LLAMADAS = ('jdbc.sql(', 'plantilla.batchUpdate(', 'plantilla.update(',
            'plantilla.query(', 'plantilla.queryForObject(')


def argumento(texto, inicio):
    """El texto entre los paréntesis de jdbc.sql( … ), con anidamiento."""
    i, nivel, dentro, comilla = inicio, 1, False, ''
    while i < len(texto) and nivel:
        c = texto[i]
        if dentro:
            if c == '\\':
                i += 2
                continue
            if texto.startswith(comilla, i):
                dentro = False
                i += len(comilla)
                continue
        elif texto.startswith('"""', i):
            dentro, comilla = True, '"""'
            i += 3
            continue
        elif c == '"':
            dentro, comilla = True, '"'
            i += 1
            continue
        elif c == '(':
            nivel += 1
        elif c == ')':
            nivel -= 1
            if not nivel:
                return texto[inicio:i], i + 1
        i += 1
    return texto[inicio:i], i


def dividir(texto, sep=','):
    """Parte por el separador respetando paréntesis y comillas."""
    fuera, nivel, actual, dentro, comilla = [], 0, '', False, ''
    i = 0
    while i < len(texto):
        c = texto[i]
        if dentro:
            actual += c
            if comilla == '"""' and texto.startswith(comilla, i):
                actual += texto[i + 1:i + 3]
                i += 3
                dentro = False
                continue
            if comilla == '"' and c == '"':
                dentro = False
            i += 1
            continue
        if texto.startswith('"""', i):
            dentro, comilla = True, '"""'
            actual += '"""'
            i += 3
            continue
        if c == '"':
            dentro, comilla = True, '"'
            actual += c
            i += 1
            continue
        if c in '([':
            nivel += 1
        elif c in ')]':
            nivel -= 1
        if c == sep and nivel == 0:
            fuera.append(actual)
            actual = ''
            i += 1
            continue
        actual += c
        i += 1
    fuera.append(actual)
    return [x for x in fuera if x.strip()]


def evaluar(expr, consts):
    """
    Resuelve una expresión Java de cadenas: literales, bloques de texto,
    constantes y `.formatted(…)`.

    Devuelve None ante cualquier otra cosa —una variable, una llamada a
    un método— en vez de adivinar. Un SQL inventado que pasa el análisis
    es peor que uno que no se analiza, porque da por buena una sentencia
    que nadie miró.
    """
    expr = expr.strip()
    m = re.match(r'^(.*?)\.formatted\((.*)\)$', expr, re.S)
    if m:
        base = evaluar(m.group(1), consts)
        if base is None:
            return None
        valores = []
        for a in dividir(m.group(2)):
            v = evaluar(a, consts)
            valores.append(v if v is not None else 'NULL')
        for n, v in enumerate(valores, 1):
            base = base.replace(f'%{n}$s', v)
        for v in valores:
            base = base.replace('%s', v, 1)
        return base

    partes = []
    for trozo in dividir(expr, sep='+'):
        trozo = trozo.strip()
        if trozo.startswith('"""'):
            partes.append(trozo[3:-3].lstrip('\n'))
        elif trozo.startswith('"') and trozo.endswith('"'):
            partes.append(trozo[1:-1])
        elif re.fullmatch(r'[A-Z_][A-Z0-9_]*', trozo) and trozo in consts:
            partes.append(consts[trozo])
        else:
            return None
    return ''.join(partes)


def constantes(texto):
    """Las constantes String del fichero, resueltas también entre sí."""
    crudas = {}
    for m in re.finditer(r'static final String (\w+)\s*=\s*(.*?);\n', texto, re.S):
        crudas[m.group(1)] = m.group(2)
    resueltas = {}
    for _ in range(5):
        for nombre, expr in crudas.items():
            v = evaluar(expr, resueltas)
            if v is not None:
                resueltas[nombre] = v
    return resueltas


def analizar(sql, conexion):
    """PREPARE contra el servidor. Devuelve el ERROR o None."""
    # Los parámetros con nombre de Spring no son SQL: PREPARE quiere `?`.
    final = re.sub(r':(\w+)', '?', sql)
    guion = ("PREPARE p FROM '"
             + final.replace("\\", "\\\\").replace("'", "''")
             + "';\nDEALLOCATE PREPARE p;\n")
    r = subprocess.run(['mysql', *conexion, 'sgip'],
                       input=guion, capture_output=True, text=True)
    for linea in r.stderr.splitlines():
        if linea.startswith('ERROR'):
            return linea, final
    return None, final


def main():
    conexion = sys.argv[1:]
    if not RAIZ.is_dir():
        print(f'No encuentro los repositorios en {RAIZ}')
        return 1

    fallos = total = 0
    sin_analizar = []
    for fichero in sorted(RAIZ.glob('*.java')):
        texto = fichero.read_text(encoding='utf-8')
        if not any(ll in texto for ll in LLAMADAS):
            continue
        consts = constantes(texto)

        # Todas las llamadas del fichero, en el orden en que aparecen,
        # para que el número de sentencia que se informa sea el que se
        # lee bajando por el archivo.
        puntos = []
        for llamada in LLAMADAS:
            desde = 0
            while True:
                j = texto.find(llamada, desde)
                if j < 0:
                    break
                puntos.append((j, llamada))
                desde = j + len(llamada)
        puntos.sort()

        n = 0
        for j, llamada in puntos:
            expr, _ = argumento(texto, j + len(llamada))
            n += 1
            # El SQL es siempre el primer argumento; `batchUpdate` lleva
            # además la lista de valores.
            partes = dividir(expr)
            sql = evaluar(partes[0], consts) if partes else None
            if sql is None:
                sin_analizar.append(f'{fichero.name} · sentencia {n}')
                continue
            total += 1
            err, final = analizar(sql, conexion)
            if err:
                fallos += 1
                print(f'\n  FALLA · {fichero.name} · sentencia {n}')
                print(f'          {err}')
                print(f'          {" ".join(final.split())[:200]}')

    print(f'\n{total} sentencias analizadas contra el esquema, {fallos} con problema')
    if sin_analizar:
        print('\nY estas no se pudieron reconstruir, de modo que nadie las miró:')
        for s in sin_analizar:
            print(f'  · {s}')
        print('Añade su forma a `evaluar()` o la sentencia se queda sin comprobar.')

    if fallos or sin_analizar:
        return 1
    print('TODO EN VERDE')
    return 0


if __name__ == '__main__':
    sys.exit(main())

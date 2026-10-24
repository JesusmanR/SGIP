import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import Barra from '../componentes/Barra.jsx'
import GraficoMovimiento from '../componentes/GraficoMovimiento.jsx'
import { api } from '../servicios/api.js'
import { NUMERO, PESOS, fechaCorta, pesosAbreviados } from '../servicios/formato.js'

/**
 * Panel de indicadores (P-02).
 *
 * «Dar en una sola vista el estado del inventario y las alertas que
 * exigen decisión. Es la primera pantalla tras el acceso del
 * administrador.» Eso último manda sobre todo lo demás: es lo primero
 * que se ve cada mañana, de modo que lo que esté arriba es lo que va a
 * decidir la jornada.
 *
 * Cubre RF-35, RF-49, RF-50 y RF-57 en su parte de lectura, con CU-24
 * como caso de uso. La especificación del mockup declara otros
 * —CU-18 y RF-28, RF-29, RF-34— que no son de esta pantalla: CU-18 es
 * generar cotización y RF-29 es el servicio web del proveedor.
 *
 * ## Los tres estados que el mockup pide
 *
 *   · Cargando: esqueletos en el sitio de los números, no un «cargando»
 *     centrado. La diferencia importa porque la silueta de la pantalla
 *     ya está, y el contenido aparece dentro en lugar de empujarlo.
 *   · Sin datos: todo en cero y un mensaje que orienta. No es un error.
 *   · Sin cargas de precios: la tarjeta invita a configurar un
 *     proveedor en lugar de mostrar una tarjeta vacía.
 *
 * ## Las dos reglas de interfaz
 *
 *   · Importes en pesos con separador de miles de punto, que es lo que
 *     hace `PESOS` en `servicios/formato.js`.
 *   · Agotadas en rojo corporativo y críticas en ámbar. Y las dos
 *     llevan el rótulo escrito al lado del número, porque esos dos
 *     colores son casi el mismo gris —ver la nota de `tokens.css`— y
 *     una cifra que solo se distinga por el tono no comunica nada a
 *     quien no lo distinga.
 */

/** Cuántas unidades pendientes empiezan a merecer su propia línea. */
const PENDIENTES_VISIBLES = 1

export default function Panel() {
  const [panel, establecerPanel] = useState(null)
  const [error, establecerError] = useState('')
  const [cargando, establecerCargando] = useState(true)

  useEffect(() => {
    let vivo = true
    api.panel()
      .then((p) => { if (vivo) establecerPanel(p) })
      .catch((e) => { if (vivo) establecerError(e.message) })
      .finally(() => { if (vivo) establecerCargando(false) })
    return () => { vivo = false }
  }, [])

  if (cargando) return <Esqueleto />

  if (error) {
    return (
      <>
        <Barra />
        <main className="contenido">
          <h1>Panel</h1>
          <p className="aviso error" role="alert">{error}</p>
        </main>
      </>
    )
  }

  const { cifras, criticas, ultimaCarga, cruces, movimientos } = panel
  const vacio = cifras.referenciasActivas === 0

  return (
    <>
      <Barra />
      <main className="contenido">
        <h1>Panel</h1>
        <p className="subtitulo">Resumen de operación</p>

        {vacio && (
          <p className="aviso" role="status">
            Todavía no hay referencias en el catálogo. En cuanto registres la
            primera, aquí verás su existencia, su valor y su movimiento.{' '}
            <Link to="/catalogo">Ir al catálogo</Link>
          </p>
        )}

        <section className="tarjetas-cifra" aria-label="Indicadores">
          <Cifra rotulo="Referencias activas"
                 valor={NUMERO.format(cifras.referenciasActivas)}
                 pie={cifras.altasDeLaSemana > 0
                   ? `${NUMERO.format(cifras.altasDeLaSemana)} esta semana`
                   : 'sin altas esta semana'} />

          <Cifra rotulo="Existencia crítica"
                 valor={NUMERO.format(cifras.enExistenciaCritica)}
                 clase={cifras.enExistenciaCritica > 0 ? 'aviso-texto' : undefined}
                 pie="Requieren reposición" />

          <Cifra rotulo="Agotadas"
                 valor={NUMERO.format(cifras.agotadas)}
                 clase={cifras.agotadas > 0 ? 'peligro-cifra' : undefined}
                 pie="Sin unidades disponibles" />

          <Cifra rotulo="Capital en inventario"
                 valor={pesosAbreviados(cifras.capitalAlCosto)}
                 pie={`Al costo · ${PESOS.format(cifras.capitalAlCosto)}`} />
        </section>

        {cifras.unidadesPendientes >= PENDIENTES_VISIBLES && (
          <p className="aviso" role="status">
            El capital no incluye{' '}
            <strong>{NUMERO.format(cifras.unidadesPendientes)} unidades</strong>{' '}
            comprometidas en ventas sin existencia: una referencia en negativo
            vale cero, no menos que cero, y su deuda se cuenta aparte.
          </p>
        )}

        <div className="panel-rejilla">
          <section className="tarjeta" aria-labelledby="t-criticas">
            <header className="tarjeta-cabecera">
              <h2 id="t-criticas">Existencia crítica</h2>
              {/* Sin nada por debajo del mínimo no hay «todas» que ver:
                  el enlace llevaría a un catálogo filtrado y vacío, que
                  es ofrecer una puerta a un cuarto que no existe. */}
              {criticas.length > 0 && (
                <Link to="/catalogo?estado=CRITICO">Ver todas</Link>
              )}
            </header>

            {criticas.length === 0 ? (
              <p className="vacio-tarjeta">
                Ninguna referencia por debajo de su mínimo. {' '}
                {vacio ? 'Aún no hay catálogo que vigilar.' : 'Nada que reponer hoy.'}
              </p>
            ) : (
              <div className="tabla-ancha tabla-estrecha">
                <table>
                  <thead>
                    <tr>
                      <th scope="col">Producto</th>
                      <th scope="col">Existencia</th>
                      <th scope="col">Mínimo</th>
                      <th scope="col">Estado</th>
                      <th scope="col">Proveedor</th>
                    </tr>
                  </thead>
                  <tbody>
                    {criticas.map((p) => (
                      <tr key={p.productoId}>
                        <td>
                          <Link to={`/catalogo?producto=${p.productoId}`}>{p.nombre}</Link>
                          <span className="codigo">{p.codigo}</span>
                        </td>
                        <td className="numero">{NUMERO.format(p.existencia)}</td>
                        <td className="numero">{NUMERO.format(p.existenciaMinima)}</td>
                        <td>
                          <span className={`insignia ${p.estadoStock.toLowerCase()}`}>
                            {p.estadoStock === 'AGOTADO' ? 'Agotado' : 'Crítico'}
                          </span>
                        </td>
                        <td>
                          {p.proveedor}
                          {p.contactoProveedor && (
                            <span className="codigo">{p.contactoProveedor}</span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <section className="tarjeta" aria-labelledby="t-carga">
            <header className="tarjeta-cabecera">
              <h2 id="t-carga">Última carga de precios</h2>
            </header>

            {!ultimaCarga ? (
              <p className="vacio-tarjeta">
                Sin cargas de precios registradas. Para importar una lista hace
                falta un proveedor con su correspondencia de columnas.{' '}
                <Link to="/importar">Configurar un proveedor</Link>
              </p>
            ) : (
              <>
                <p className="carga-proveedor">{ultimaCarga.proveedor}</p>
                <p className="carga-detalle">
                  {fechaCorta(ultimaCarga.fecha)}
                  {' · '}
                  {NUMERO.format(ultimaCarga.leidos)}{' '}
                  {ultimaCarga.leidos === 1 ? 'registro' : 'registros'}
                </p>
                <p>
                  <span className={`insignia ${INSIGNIA_CARGA[ultimaCarga.estado] ?? 'activo'}`}>
                    {TEXTO_CARGA[ultimaCarga.estado] ?? ultimaCarga.estado}
                  </span>
                </p>

                <dl className="carga-cifras">
                  <div>
                    <dt>aplicados</dt>
                    <dd className="numero">{NUMERO.format(ultimaCarga.aceptados)}</dd>
                  </div>
                  <div>
                    <dt>rechazados</dt>
                    <dd className="numero">{NUMERO.format(ultimaCarga.rechazados)}</dd>
                  </div>
                  <div>
                    <dt>cruzaron el umbral</dt>
                    <dd className="numero">{NUMERO.format(cruces.length)}</dd>
                  </div>
                </dl>

                {ultimaCarga.estado === 'VALIDADA' && (
                  <p className="aviso" role="status">
                    Esta carga está validada y sin aplicar: no ha cambiado ningún
                    costo todavía. <Link to="/importar">Revisarla y decidir</Link>
                  </p>
                )}

                {cruces.length > 0 && <Cruces cruces={cruces} />}
              </>
            )}
          </section>
        </div>

        <section className="tarjeta" aria-labelledby="t-movimiento">
          <header className="tarjeta-cabecera">
            <h2 id="t-movimiento">Movimiento de inventario</h2>
            <span className="tarjeta-nota">Últimos 30 días</span>
          </header>
          <GraficoMovimiento dias={movimientos} />
        </section>
      </main>
    </>
  )
}

const INSIGNIA_CARGA = {
  APLICADA: 'activo',
  VALIDADA: 'critico',
  CARGADA: 'critico',
  RECHAZADA: 'agotado',
}

const TEXTO_CARGA = {
  APLICADA: 'Aplicada',
  VALIDADA: 'Validada, sin aplicar',
  CARGADA: 'Cargada',
  RECHAZADA: 'Rechazada',
}

function Cifra({ rotulo, valor, pie, clase }) {
  return (
    <article className="tarjeta cifra">
      <span className="cifra-rotulo">{rotulo}</span>
      <span className={`cifra-valor${clase ? ` ${clase}` : ''}`}>{valor}</span>
      <span className="cifra-pie">{pie}</span>
    </article>
  )
}

/**
 * El aviso de las referencias que cambiaron de tratamiento tributario
 * (RF-35).
 *
 * Va en ámbar y no en rojo: cruzar el umbral no es un error, es una
 * consecuencia legítima de un costo que subió, y el artículo 424 es
 * quien decide. Lo que hace falta es que nadie se entere por la
 * reclamación de un cliente que vio un 19 % que ayer no estaba.
 *
 * Lleva el precio de antes y el de después porque es lo que el
 * requisito pide y porque es lo que permite decidir: lo que una
 * empresa necesita saber es si el salto compensa.
 */
function Cruces({ cruces }) {
  return (
    <div className="aviso umbral-aviso" role="status">
      <p>
        <strong>
          {NUMERO.format(cruces.length)}{' '}
          {cruces.length === 1 ? 'referencia' : 'referencias'}
        </strong>{' '}
        {cruces.length === 1 ? 'cambió' : 'cambiaron'} de tratamiento tributario
        tras esta carga.
      </p>
      <ul className="umbral-lista">
        {cruces.map((c) => (
          <li key={c.productoId}>
            <span className="codigo-inline">{c.codigo}</span> {c.nombre}
            {': '}
            {PESOS.format(c.precioAntes)} → {PESOS.format(c.precioDespues)}
            {', '}
            {c.tratamientoAntes === 'EXCLUIDO'
              ? 'ahora se factura con IVA'
              : 'ahora se factura excluido'}
          </li>
        ))}
      </ul>
    </div>
  )
}

/**
 * La pantalla mientras llega la respuesta.
 *
 * Repite la silueta —cuatro tarjetas de cifra y dos bloques— en lugar
 * de poner un «cargando» centrado. Así el contenido aparece dentro de
 * una estructura que ya está en su sitio, sin que la pantalla salte
 * cuando llegan los datos.
 *
 * `aria-busy` y el texto oculto son para quien no ve los esqueletos: un
 * bloque gris no anuncia que algo está en camino.
 */
function Esqueleto() {
  return (
    <>
      <Barra />
      <main className="contenido" aria-busy="true">
        <h1>Panel</h1>
        <p className="subtitulo">Resumen de operación</p>
        <p className="oculto-visualmente" role="status">Cargando los indicadores…</p>

        <div className="tarjetas-cifra">
          {[0, 1, 2, 3].map((i) => (
            <article className="tarjeta cifra" key={i} aria-hidden="true">
              <span className="hueso hueso-rotulo" />
              <span className="hueso hueso-cifra" />
              <span className="hueso hueso-pie" />
            </article>
          ))}
        </div>

        <div className="panel-rejilla" aria-hidden="true">
          <section className="tarjeta">
            <span className="hueso hueso-titulo" />
            <span className="hueso hueso-fila" />
            <span className="hueso hueso-fila" />
            <span className="hueso hueso-fila" />
          </section>
          <section className="tarjeta">
            <span className="hueso hueso-titulo" />
            <span className="hueso hueso-fila" />
            <span className="hueso hueso-fila" />
          </section>
        </div>
      </main>
    </>
  )
}

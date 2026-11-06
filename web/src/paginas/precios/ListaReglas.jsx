import { useEffect, useRef, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import Aviso from '../../componentes/comun/Aviso.jsx'
import EncabezadoPagina from '../../componentes/comun/EncabezadoPagina.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import EstadoVacio from '../../componentes/comun/EstadoVacio.jsx'
import TablaOTarjetas from '../../componentes/comun/TablaOTarjetas.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { apiPrecios } from '../../servicios/api/precios.js'
import { porcentajeConDecimales } from '../../servicios/formato.js'
import Cierre from './Cierre.jsx'
import { estadoDeVigencia, fechaDia, hoyISO, textoDeVigencia } from './fechas.js'
import {
  etiquetaDeCalculo, etiquetaDePago, factorAPorcentaje, textoDeAlcance,
} from './reglas.js'

const NUEVA = <Link to="/precios/nueva" className="boton enlace-boton">Nueva regla</Link>

/** El estado va siempre en texto: los colores de estado se confunden en escala de grises (ADR-0009). */
function EstadoRegla({ regla, hoy }) {
  const estado = estadoDeVigencia(regla, hoy)
  const texto = estado === 'rige' ? 'Rige hoy'
    : estado === 'proxima' ? `Empieza el ${fechaDia(regla.vigenteDesde)}` : 'Cerrada'
  return <span className={`estado-regla ${estado}`}>{texto}</span>
}

function Acciones({ regla, hoy, alCerrar }) {
  const estado = estadoDeVigencia(regla, hoy)
  const donde = textoDeAlcance(regla)
  if (estado === 'cerrada') return <span className="sin-acciones">—</span>
  if (estado === 'proxima') {
    return (
      <Link to={`/precios/${regla.reglaId}/corregir`} className="boton secundario menudo enlace-boton" aria-label={`Corregir la regla de ${donde}`}>
        Corregir
      </Link>
    )
  }
  return (
    <span className="acciones-fila">
      <Link to={`/precios/${regla.reglaId}/reemplazar`} className="boton secundario menudo enlace-boton" aria-label={`Reemplazar la regla de ${donde}`}>
        Reemplazar
      </Link>
      <button type="button" className="boton secundario menudo" aria-label={`Cerrar la regla de ${donde}`} onClick={() => alCerrar(regla)}>
        Cerrar
      </button>
    </span>
  )
}

/** Pantalla P-47 · las reglas generales de ganancia de la empresa. */
export default function ListaReglas() {
  const ubicacion = useLocation()
  const navegar = useNavigate()
  // El aviso viaja en el estado de la navegación; se guarda aquí y se borra de
  // la historia para que no reaparezca al recargar la página.
  const [avisoInicial] = useState(ubicacion.state?.aviso)
  useEffect(() => {
    if (avisoInicial) navegar(ubicacion.pathname, { replace: true, state: null })
  }, [])
  const { datos, cargando, error, sinPermiso, recargar } = useDatos(() => apiPrecios.listar(), [])
  const [verCerradas, setVerCerradas] = useState(false)
  const [cerrando, setCerrando] = useState(null)
  const [enviando, setEnviando] = useState(false)
  const [exito, setExito] = useState(avisoInicial ?? null)
  const [fallo, setFallo] = useState(null)
  const enCurso = useRef(false)
  const hoy = hoyISO()

  async function cerrar(regla, hasta) {
    if (enCurso.current) return
    enCurso.current = true
    setEnviando(true)
    setFallo(null)
    setExito(null)
    try {
      await apiPrecios.cerrar(regla.reglaId, hasta)
      setCerrando(null)
      setExito('Regla cerrada. Se conserva en el historial.')
      recargar()
    } catch (e) {
      setCerrando(null)
      setFallo(e.message || 'No se pudo cerrar la regla.')
    } finally {
      enCurso.current = false
      setEnviando(false)
    }
  }

  const columnas = [
    { clave: 'alcance', titulo: 'Se aplica a', principal: true, render: (r) => textoDeAlcance(r) },
    { clave: 'pago', titulo: 'Se cobra', render: (r) => etiquetaDePago(r.formaPago) },
    { clave: 'calculo', titulo: 'Cómo se calcula', render: (r) => etiquetaDeCalculo(r.formaCalculo) },
    {
      clave: 'ganancia', titulo: 'Ganancia', numerica: true,
      render: (r) => porcentajeConDecimales(Number(factorAPorcentaje(r.factor).replace(',', '.'))),
    },
    { clave: 'vigencia', titulo: 'Vigencia', render: (r) => textoDeVigencia(r) },
    { clave: 'estado', titulo: 'Estado', render: (r) => <EstadoRegla regla={r} hoy={hoy} /> },
    { clave: 'acciones', titulo: 'Acciones', render: (r) => <Acciones regla={r} hoy={hoy} alCerrar={setCerrando} /> },
  ]

  let cuerpo
  if (sinPermiso) cuerpo = <EstadoSinPermiso />
  else if (error) cuerpo = <EstadoError error={error} alReintentar={recargar} />
  else if (cargando && !datos) cuerpo = <EstadoCarga mensaje="Cargando reglas…" />
  else if (datos.length === 0) {
    cuerpo = (
      <EstadoVacio
        titulo="No hay reglas generales de ganancia"
        detalle="Está bien así: no son obligatorias. El precio de cada producto se escribe en cada producto; una regla general solo sirve para darle el mismo porcentaje a muchos a la vez."
      />
    )
  } else {
    const visibles = datos.filter((r) => verCerradas || estadoDeVigencia(r, hoy) !== 'cerrada')
    const hayCerradas = datos.some((r) => estadoDeVigencia(r, hoy) === 'cerrada')
    cuerpo = (
      <>
        {hayCerradas && (
          <label className="casilla">
            <input type="checkbox" checked={verCerradas} onChange={(e) => setVerCerradas(e.target.checked)} />
            Ver también las reglas cerradas
          </label>
        )}
        {visibles.length === 0 ? (
          <EstadoVacio titulo="Ninguna regla rige ahora" detalle="Las reglas cerradas se ven marcando la casilla de arriba." />
        ) : (
          <TablaOTarjetas etiqueta="Reglas de ganancia" columnas={columnas} filas={visibles} claveFila={(r) => r.reglaId} />
        )}
      </>
    )
  }

  return (
    <div className="contenido seccion-precios">
      <EncabezadoPagina
        titulo="Reglas de ganancia"
        descripcion="Porcentajes generales por categoría o proveedor, para no escribirlos producto por producto."
        acciones={sinPermiso ? null : NUEVA}
      />
      <div className="nota-seccion">
        <p>
          Las reglas generales son opcionales: el sistema no pone ninguna por su cuenta. El precio y el porcentaje
          de cada producto se escriben en el producto.
        </p>
        <p>
          Una regla general no cambia los productos que tienen precio o porcentaje propio. Para saber cuántos
          alcanzaría, mira el número que aparece al crearla.
        </p>
        <p>
          Una regla que ya rige no se corrige hacia atrás, porque las ventas se explican con ella: se cierra o se
          reemplaza por otra que empieza hoy.
        </p>
      </div>
      {exito && <Aviso tipo="exito">{exito}</Aviso>}
      {fallo && <Aviso tipo="peligro">{fallo}</Aviso>}
      {cerrando && (
        <Cierre
          key={cerrando.reglaId}
          regla={cerrando}
          hoy={hoy}
          enviando={enviando}
          alConfirmar={(hasta) => cerrar(cerrando, hasta)}
          alCancelar={() => setCerrando(null)}
        />
      )}
      {cuerpo}
    </div>
  )
}

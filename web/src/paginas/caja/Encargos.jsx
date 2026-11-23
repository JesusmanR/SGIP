import { useState } from 'react'
import Aviso from '../../componentes/comun/Aviso.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import EstadoVacio from '../../componentes/comun/EstadoVacio.jsx'
import TablaOTarjetas from '../../componentes/comun/TablaOTarjetas.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { entregarEncargo, listarEncargos } from '../../servicios/api/caja.js'
import { esAdministrador } from '../../servicios/sesion.js'
import '../../estilos/caja.css'
import { fechaDocumento } from './formatoCaja.js'
import Pestanas from './Pestanas.jsx'

const PESTANAS = [
  { id: 'PENDIENTE', titulo: 'Pendientes' },
  { id: 'LLEGO', titulo: 'Ya llegó' },
]
const VACIO = {
  PENDIENTE: 'No hay encargos pendientes.',
  LLEGO: 'No hay encargos cuyo producto ya haya llegado.',
}

/**
 * Los productos vendidos como encargo (sin existencia). El estado «Ya llegó» lo calcula el servidor
 * al consultar, según la existencia actual; la entrega al cliente se marca a mano. Solo el administrador.
 */
export default function Encargos() {
  const admin = esAdministrador()
  const [estado, setEstado] = useState('PENDIENTE')
  const [entregando, setEntregando] = useState(null)
  const [fallo, setFallo] = useState(null)
  const encargos = useDatos(() => (admin ? listarEncargos(estado) : Promise.resolve([])), [estado, admin])

  async function entregar(encargo) {
    setFallo(null)
    setEntregando(encargo.itemId)
    try {
      await entregarEncargo(encargo.itemId)
      encargos.recargar()
    } catch (error) {
      setFallo(error.message || 'No se pudo marcar el encargo como entregado.')
    } finally {
      setEntregando(null)
    }
  }

  const columnas = [
    { clave: 'producto', titulo: 'Producto', principal: true, render: (e) => e.producto },
    // Las unidades que de verdad esperan existencia, no las de toda la línea.
    { clave: 'cantidad', titulo: 'Por llegar', numerica: true, render: (e) => e.cantidadEncargo ?? e.cantidad },
    { clave: 'cliente', titulo: 'Cliente', render: (e) => e.cliente },
    { clave: 'venta', titulo: 'Venta', render: (e) => e.consecutivo },
    { clave: 'entrega', titulo: 'Entrega estimada', render: (e) => fechaDocumento(e.entregaEstimada) },
    {
      clave: 'estado', titulo: 'Estado',
      render: (e) => (
        <span className={`insignia ${e.estado === 'LLEGO' ? 'activo' : 'critico'}`}>
          {e.estado === 'LLEGO' ? 'Ya llegó' : 'Pendiente'}
        </span>
      ),
    },
    {
      clave: 'acciones', titulo: 'Acciones',
      render: (e) => (
        <button
          type="button" className="boton secundario menudo" disabled={entregando === e.itemId}
          aria-label={`Marcar entregado ${e.producto}`} onClick={() => entregar(e)}
        >
          Marcar entregado
        </button>
      ),
    },
  ]

  let cuerpo
  if (!admin || encargos.sinPermiso) cuerpo = <EstadoSinPermiso />
  else if (encargos.cargando && !encargos.datos) cuerpo = <EstadoCarga />
  else if (encargos.error) cuerpo = <EstadoError error={encargos.error} alReintentar={encargos.recargar} />
  else if ((encargos.datos ?? []).length === 0) cuerpo = <EstadoVacio titulo={VACIO[estado]} />
  else {
    cuerpo = (
      <TablaOTarjetas
        etiqueta={estado === 'LLEGO' ? 'Encargos que ya llegaron' : 'Encargos pendientes'}
        filas={encargos.datos} claveFila={(e) => e.itemId} columnas={columnas}
      />
    )
  }

  return (
    <div className="contenido seccion-caja">
      <header className="caja-cabecera">
        <p className="caja-eyebrow">Caja</p>
        <h1>Encargos</h1>
        <p className="caja-subtitulo">Productos que se vendieron sin existencia y que el cliente espera.</p>
      </header>
      {fallo && <Aviso tipo="peligro">{fallo}</Aviso>}
      {admin ? (
        <Pestanas etiqueta="Estado del encargo" pestanas={PESTANAS} activa={estado} alCambiar={(id) => { setFallo(null); setEstado(id) }}>
          {cuerpo}
        </Pestanas>
      ) : cuerpo}
    </div>
  )
}

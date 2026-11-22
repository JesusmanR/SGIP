import { useState } from 'react'
import Campo from '../../componentes/comun/Campo.jsx'
import { hoyISO, sumarDias } from '../../servicios/fechas.js'
import '../../estilos/caja.css'
import Dialogo from './Dialogo.jsx'

const PRECIO = /^\d{1,9}$/

/**
 * El formulario del producto que todavía no existe. No llama al servidor: entrega
 * los datos y la pantalla pone una línea marcada como encargo. El producto se crea
 * al cobrar, dentro de la venta, con un solo PIN.
 */
export default function ProductoNuevo({ codigoInicial = '', motivoInicial = '', alAgregar, alCerrar }) {
  const [datos, setDatos] = useState({
    nombre: '', precio: '', codigoBarras: codigoInicial, entregaEstimada: '', motivo: motivoInicial,
  })
  const [intentado, setIntentado] = useState(false)
  const cambiar = (campo) => (e) => setDatos((d) => ({ ...d, [campo]: e.target.value }))

  const hoy = hoyISO()
  const motivos = {}
  if (!datos.nombre.trim()) motivos.nombre = 'Escribe el nombre del producto.'
  if (!PRECIO.test(datos.precio.trim()) || Number(datos.precio) <= 0) {
    motivos.precio = 'Escribe un precio mayor que cero, en pesos enteros y sin puntos.'
  }
  if (!datos.entregaEstimada) motivos.entregaEstimada = 'Elige la fecha de entrega estimada.'
  else if (datos.entregaEstimada < hoy) motivos.entregaEstimada = 'La fecha de entrega no puede ser anterior a hoy.'
  if (!datos.motivo.trim()) motivos.motivo = 'Escribe el motivo del encargo.'
  const mostrar = (campo) => (intentado ? motivos[campo] : undefined)

  function enviar(evento) {
    evento.preventDefault()
    setIntentado(true)
    if (Object.keys(motivos).length > 0) return
    alAgregar({
      nombre: datos.nombre.trim(),
      precioVenta: Number(datos.precio),
      codigoBarras: datos.codigoBarras.trim(),
      entregaEstimada: datos.entregaEstimada,
      motivo: datos.motivo.trim(),
    })
  }

  return (
    <Dialogo titulo="Producto nuevo" alCerrar={alCerrar}>
      <p className="nota">
        El producto se crea al cobrar y se vende como encargo. Necesita la autorización de un administrador.
      </p>
      <form onSubmit={enviar} noValidate>
        <Campo etiqueta="Nombre del producto" motivo={mostrar('nombre')}>
          <input type="text" value={datos.nombre} onChange={cambiar('nombre')} maxLength={160} autoComplete="off" />
        </Campo>
        <Campo etiqueta="Precio de venta (sin IVA)" motivo={mostrar('precio')} ayuda="En pesos, sin puntos ni comas.">
          <input type="text" inputMode="numeric" value={datos.precio} onChange={cambiar('precio')} autoComplete="off" />
        </Campo>
        <Campo etiqueta="Código de barras (opcional)">
          <input type="text" value={datos.codigoBarras} onChange={cambiar('codigoBarras')} maxLength={60} autoComplete="off" />
        </Campo>
        <Campo etiqueta="Fecha de entrega estimada" motivo={mostrar('entregaEstimada')}>
          <input type="date" value={datos.entregaEstimada} min={hoy} max={sumarDias(hoy, 365)} onChange={cambiar('entregaEstimada')} />
        </Campo>
        <Campo etiqueta="Motivo del encargo" motivo={mostrar('motivo')}>
          <input type="text" value={datos.motivo} onChange={cambiar('motivo')} maxLength={200} autoComplete="off" />
        </Campo>
        <div className="caja-acciones">
          <button type="submit" className="boton">Agregar al carrito</button>
          <button type="button" className="boton secundario" onClick={alCerrar}>Cancelar</button>
        </div>
      </form>
    </Dialogo>
  )
}

import { Link } from 'react-router-dom'
import EstadoVacio from '../../componentes/comun/EstadoVacio.jsx'

export default function ProductoNoEncontrado() {
  return (
    <EstadoVacio
      titulo="No encontramos ese producto"
      detalle="Puede que ya no esté en tu catálogo."
      accion={<Link to="/inventario">Volver a movimientos</Link>}
    />
  )
}

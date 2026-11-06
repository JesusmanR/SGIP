/**
 * El aviso de que falta la UVT de un año. Solo informa: ninguna tienda deja
 * de vender porque falte, así que no usa el tono de error ni de alarma.
 * Si la alerta no se pudo consultar o no está activa, no pinta nada: el resto
 * de la pantalla sirve igual.
 */
export default function AlertaUvt({ alerta }) {
  if (!alerta?.activa) return null
  const dias = alerta.diasRestantes
  let cuando = 'ese año ya empezó'
  if (dias === 1) cuando = 'falta 1 día para que empiece'
  else if (dias > 1) cuando = `faltan ${dias} días para que empiece`
  return (
    <div
      className="aviso-estado aviso-estado--info alerta-uvt"
      role="status"
      aria-label={`Falta la UVT del ${alerta.anio}`}
    >
      <strong>Falta la UVT del {alerta.anio}</strong> ({cuando}). Las tiendas siguen vendiendo con
      normalidad: la UVT es opcional y solo cambia el cálculo de quien activó la exclusión por tope.
      Fíjala abajo cuando se publique.
    </div>
  )
}

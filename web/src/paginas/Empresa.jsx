import { useState } from 'react'
import { Link } from 'react-router-dom'
import EncabezadoPagina from '../componentes/comun/EncabezadoPagina.jsx'
import EstadoCarga from '../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../componentes/comun/EstadoSinPermiso.jsx'
import SubirImagen from '../componentes/SubirImagen.jsx'
import { useDatos } from '../hooks/useDatos.js'
import { api } from '../servicios/api.js'
import { prepararImagen } from '../servicios/imagenes.js'
import { leerSesion } from '../servicios/sesion.js'
import '../estilos/empresa.css'

/**
 * Mi empresa.
 *
 * La ven el administrador, el vendedor y el cliente, de modo que aquí no
 * hay ni costos ni márgenes: solo lo que `GET /api/v1/empresa` entrega
 * —razón social, NIT y logo—. El perfil del diseño (pantalla 10) trae
 * además teléfono, correo, dirección y plan; el backend no los guarda
 * y no se fingen campos que no se pueden guardar.
 *
 * Los impuestos son opcionales: no cobrar IVA es un estado válido, así
 * que la pantalla no lo marca como pendiente ni como error. Solo el
 * administrador, que es quien puede configurarlos, ve el enlace.
 */
export default function Empresa() {
  const { datos, cargando, error, sinPermiso, recargar } = useDatos(() => api.empresa())
  const [reemplazo, setReemplazo] = useState(null)
  const [fallo, setFallo] = useState(null)
  const [aviso, setAviso] = useState(null)
  const esAdministrador = leerSesion()?.rol === 'ADMINISTRADOR'
  const empresa = reemplazo ?? datos

  /**
   * Un logo y no varios: SubirImagen entrega siempre un arreglo, pero
   * aquí la casilla no admite selección múltiple y por tanto trae uno.
   *
   * No atrapa el error a propósito: lo recoge SubirImagen y lo anuncia
   * por onFallo. Atraparlo aquí hacía que el componente creyera que la
   * subida había ido bien y volviera a pedir la imagen sin motivo.
   */
  async function subirLogo(archivos) {
    setFallo(null)
    setAviso(null)
    setReemplazo(await api.subirLogo(await prepararImagen(archivos[0])))
    setAviso('Logo actualizado.')
  }

  function fallar(mensaje) {
    setAviso(null)
    setFallo(mensaje)
  }

  return (
    <div className="contenido" aria-busy={cargando || undefined}>
      <EncabezadoPagina
        titulo="Mi empresa"
        descripcion="Los datos con los que tu empresa aparece en el sistema."
      />

      {cargando && !empresa && <EstadoCarga mensaje="Cargando los datos de la empresa…" />}
      {!cargando && sinPermiso && <EstadoSinPermiso />}
      {!cargando && error && !sinPermiso && (
        <EstadoError error={error} alReintentar={recargar} />
      )}

      {empresa && (
        <>
          {fallo && <div className="aviso error" role="alert">{fallo}</div>}
          {aviso && <div className="aviso exito" role="status">{aviso}</div>}

          <section className="tarjeta empresa-perfil" aria-label="Datos de la empresa">
            <SubirImagen
              src={empresa.logoUrl}
              alt={`Logo de ${empresa.razonSocial}`}
              onSubir={subirLogo}
              onFallo={fallar}
              puedeEditar={esAdministrador}
              redondo
            />
            <div className="empresa-datos">
              <p className="titulo-empresa">{empresa.razonSocial}</p>
              <p className="sub-linea">NIT {empresa.nit}</p>
              <p className="sub-linea">
                {esAdministrador
                  ? 'Toca el logo para subir o reemplazarlo. JPEG, PNG o WebP; se ajusta solo, sin recortarlo ni deformarlo.'
                  : 'Solo el administrador puede cambiar el logo.'}
              </p>
            </div>
          </section>

          {esAdministrador && (
            <section className="tarjeta empresa-impuestos" aria-labelledby="t-impuestos">
              <h2 id="t-impuestos">Impuestos</h2>
              <p>
                Son opcionales. Si no cobras IVA no hay nada que configurar y
                tus precios salen sin impuesto.
              </p>
              <Link to="/impuestos">Configurar impuestos</Link>
            </section>
          )}
        </>
      )}
    </div>
  )
}

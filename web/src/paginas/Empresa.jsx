import { useEffect, useState } from 'react'
import Barra from '../componentes/Barra.jsx'
import SubirImagen from '../componentes/SubirImagen.jsx'
import { api } from '../servicios/api.js'
import { leerSesion } from '../servicios/sesion.js'

export default function Empresa() {
  const [empresa, setEmpresa] = useState(null)
  const [error, setError] = useState(null)
  const esAdministrador = leerSesion()?.rol === 'ADMINISTRADOR'

  useEffect(() => {
    api.empresa().then(setEmpresa).catch((e) => setError(e.message))
  }, [])

  /**
   * No atrapa el error a propósito: lo recoge SubirImagen y lo anuncia
   * por onFallo. Atraparlo aquí hacía que el componente creyera que la
   * subida había ido bien y volviera a pedir la imagen sin motivo.
   */
  async function subirLogo(archivo) {
    setError(null)
    setEmpresa(await api.subirLogo(archivo))
  }

  return (
    <>
      <Barra />
      <main className="contenido">
        <h2>Mi empresa</h2>
        {error && <div className="aviso error" role="alert">{error}</div>}

        <div className="tarjeta">
          <div className="fila-logo">
            <SubirImagen
              src={empresa?.logoUrl}
              alt={`Logo de ${empresa?.razonSocial ?? 'la empresa'}`}
              onSubir={subirLogo}
              onFallo={setError}
              puedeEditar={esAdministrador}
              redondo
            />
            <div>
              <p className="titulo-empresa">{empresa?.razonSocial ?? '…'}</p>
              <p className="sub-linea">NIT {empresa?.nit ?? '—'}</p>
              <p className="sub-linea">
                {esAdministrador
                  ? 'Toca el recuadro para subir o reemplazar el logo. JPEG, PNG o WebP. '
                    + 'Se ajusta solo: cabe entero, sin recortarlo ni deformarlo.'
                  : 'Solo el administrador puede cambiar el logo.'}
              </p>
            </div>
          </div>
        </div>
      </main>
    </>
  )
}

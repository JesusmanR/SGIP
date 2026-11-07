import { useEffect, useRef, useState } from 'react'
import { api } from '../servicios/api.js'
import EstadoCarga from './comun/EstadoCarga.jsx'
import EstadoError from './comun/EstadoError.jsx'
import useDialogo from './useDialogo.js'
import '../estilos/producto.css'

/**
 * Administración de las categorías del catálogo (RF-19).
 *
 * Crear, renombrar y desactivar. Desactivar no borra: los productos que
 * usaron la categoría conservan su historial, y por eso el backend se
 * niega a desactivar una que todavía sostenga productos activos. Ese
 * rechazo llega como mensaje y se muestra tal cual, porque dice cuántos
 * productos hay que mover primero.
 */
export default function Categorias({ onCerrar, onCambio }) {
  const [categorias, setCategorias] = useState([])
  const [error, setError] = useState(null)
  const [ocupado, setOcupado] = useState(false)
  const [nueva, setNueva] = useState('')
  // Qué categoría se está renombrando, y con qué texto.
  const [editando, setEditando] = useState(null)
  const [nombreEditado, setNombreEditado] = useState('')
  // «cargando» y «fallo» solo valen mientras no haya una lista que mostrar: un
  // error posterior se dice arriba y la lista que ya hay se queda.
  const [carga, setCarga] = useState('cargando')
  const cargada = useRef(false)
  const dialogo = useDialogo(onCerrar)

  async function recargar() {
    try {
      const frescas = await api.categorias()
      setCategorias(frescas)
      setError(null)
      cargada.current = true
      setCarga('lista')
      onCambio?.(frescas)
      return frescas
    } catch (e) {
      setError(e.message)
      if (!cargada.current) setCarga('fallo')
      return null
    }
  }

  useEffect(() => { recargar() }, [])

  function reintentar() {
    setCarga('cargando')
    recargar()
  }

  /** Envuelve una operación: apaga los botones y muestra lo que falle. */
  async function operar(accion) {
    setOcupado(true)
    setError(null)
    try {
      await accion()
      await recargar()
    } catch (e) {
      // El mensaje del backend es el útil: dice cuántos productos
      // sostiene la categoría o qué nombre está repetido.
      setError(e.message)
    } finally {
      setOcupado(false)
    }
  }

  return (
    <div
      className="velo" role="dialog" aria-modal="true" aria-label="Categorías del catálogo"
      tabIndex={-1} ref={dialogo.ref} onKeyDown={dialogo.onKeyDown}
    >
      <div className="tarjeta panel">
        <div className="panel-cabecera">
          <h2>Categorías</h2>
          <button type="button" className="boton secundario" onClick={onCerrar}>Cerrar</button>
        </div>

        {carga === 'cargando' && <EstadoCarga mensaje="Cargando categorías…" />}
        {carga === 'fallo' && <EstadoError error={error} alReintentar={reintentar} />}

        {carga === 'lista' && (
          <>
          {error && <div className="aviso error" role="alert">{error}</div>}

          <form
            className="panel-acciones"
            onSubmit={(e) => {
              e.preventDefault()
              if (!nueva.trim()) return
              operar(async () => {
                await api.crearCategoria({ nombre: nueva.trim(), descripcion: null })
                setNueva('')
              })
            }}
          >
            <input
              value={nueva}
              onChange={(e) => setNueva(e.target.value)}
              placeholder="Nombre de la categoría nueva"
              maxLength={80}
              aria-label="Nombre de la categoría nueva"
            />
            <button className="boton" type="submit" disabled={ocupado || !nueva.trim()}>
              Añadir
            </button>
          </form>

          <table>
            <thead>
              <tr>
                <th>Categoría</th>
                <th><span className="oculto">Acciones</span></th>
              </tr>
            </thead>
            <tbody>
              {categorias.map((c) => (
                <tr key={c.id}>
                  <td>
                    {editando === c.id ? (
                      <input
                        value={nombreEditado}
                        onChange={(e) => setNombreEditado(e.target.value)}
                        maxLength={80}
                        aria-label={`Nuevo nombre de ${c.nombre}`}
                      />
                    ) : (
                      <>
                        {c.nombre}
                        {c.descripcion && <span className="sub-linea">{c.descripcion}</span>}
                      </>
                    )}
                  </td>
                  <td className="acciones">
                    {editando === c.id ? (
                      <>
                        <button
                          className="boton"
                          disabled={ocupado || !nombreEditado.trim()}
                          onClick={() => operar(async () => {
                            await api.editarCategoria(c.id, {
                              nombre: nombreEditado.trim(),
                              descripcion: c.descripcion ?? null,
                            })
                            setEditando(null)
                          })}
                        >
                          Guardar
                        </button>
                        <button
                          className="boton secundario"
                          onClick={() => setEditando(null)}
                          disabled={ocupado}
                        >
                          Cancelar
                        </button>
                      </>
                    ) : (
                      <>
                        <button
                          className="enlace"
                          disabled={ocupado}
                          onClick={() => { setEditando(c.id); setNombreEditado(c.nombre) }}
                        >
                          renombrar
                        </button>
                        {' · '}
                        <button
                          className="enlace"
                          disabled={ocupado}
                          onClick={() => operar(() => api.desactivarCategoria(c.id))}
                        >
                          desactivar
                        </button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          {categorias.length === 0 && (
            <p className="sub-linea">Todavía no hay categorías.</p>
          )}
          </>
        )}
      </div>
    </div>
  )
}

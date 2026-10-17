import { useEffect, useState } from 'react'
import { api } from '../servicios/api.js'

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

  async function recargar() {
    try {
      const frescas = await api.categorias()
      setCategorias(frescas)
      setError(null)
      onCambio?.(frescas)
      return frescas
    } catch (e) {
      setError(e.message)
      return null
    }
  }

  useEffect(() => { recargar() }, [])

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
    <div className="velo" role="dialog" aria-modal="true" aria-label="Categorías del catálogo">
      <div className="tarjeta panel">
        <div className="panel-cabecera">
          <h3>Categorías</h3>
          <button className="boton secundario" onClick={onCerrar}>Cerrar</button>
        </div>

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
          <p style={{ color: 'var(--tinta-3)' }}>Todavía no hay categorías.</p>
        )}
      </div>
    </div>
  )
}

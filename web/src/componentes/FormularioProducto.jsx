import { useState } from 'react'
import { api } from '../servicios/api.js'

/**
 * Edición de un producto del catálogo (RF-15).
 *
 * No hay campo de existencia, y no es un olvido: la existencia solo
 * cambia por movimientos de inventario, que registran el valor anterior
 * y el posterior. Un formulario que la dejara escribir produciría un
 * salto sin explicación en la única tabla que permite reconstruir qué
 * pasó con una referencia. El dato se muestra, en gris y sin control,
 * para que quien edita sepa cuál es sin poder tocarlo.
 */
export default function FormularioProducto({ producto, categorias, proveedores, onCerrar, onGuardado }) {
  const [datos, setDatos] = useState({
    categoriaId: producto.categoriaId ?? '',
    proveedorId: producto.proveedorId ?? '',
    codigo: producto.codigo ?? '',
    sku: producto.sku ?? '',
    codigoBarras: producto.codigoBarras ?? '',
    nombre: producto.nombre ?? '',
    presentacion: producto.presentacion ?? '',
    descripcion: producto.descripcion ?? '',
    costo: producto.costo ?? '',
    existenciaMinima: producto.existenciaMinima ?? '',
  })
  const [error, setError] = useState(null)
  const [guardando, setGuardando] = useState(false)

  const cambiar = (campo) => (e) => setDatos((d) => ({ ...d, [campo]: e.target.value }))

  async function guardar(e) {
    e.preventDefault()
    setGuardando(true)
    setError(null)
    try {
      const fresco = await api.editarProducto(producto.productoId, {
        ...datos,
        // Los selectores devuelven cadenas; el backend espera números.
        categoriaId: Number(datos.categoriaId),
        proveedorId: Number(datos.proveedorId),
        existenciaMinima: datos.existenciaMinima === '' ? null : Number(datos.existenciaMinima),
      })
      onGuardado(fresco)
    } catch (e2) {
      // El mensaje del backend es el que sirve: dice qué código está
      // repetido o qué referencia es de otra empresa.
      setError(e2.message)
      setGuardando(false)
    }
  }

  return (
    <div className="velo" role="dialog" aria-modal="true" aria-label={`Editar ${producto.nombre}`}>
      <form className="tarjeta panel" onSubmit={guardar}>
        <div className="panel-cabecera">
          <h3>Editar producto</h3>
          <button type="button" className="boton secundario" onClick={onCerrar}>Cancelar</button>
        </div>

        {error && <div className="aviso error" role="alert">{error}</div>}

        <label className="campo">
          <span>Nombre</span>
          <input value={datos.nombre} onChange={cambiar('nombre')} required maxLength={160} />
        </label>

        <div className="rejilla">
          <label className="campo">
            <span>Categoría</span>
            <select value={datos.categoriaId} onChange={cambiar('categoriaId')} required>
              <option value="">Elige una</option>
              {categorias.map((c) => <option key={c.id} value={c.id}>{c.nombre}</option>)}
            </select>
          </label>

          <label className="campo">
            <span>Proveedor</span>
            <select value={datos.proveedorId} onChange={cambiar('proveedorId')} required>
              <option value="">Elige uno</option>
              {proveedores.map((p) => (
                <option key={p.id} value={p.id}>{p.razonSocial ?? p.nombre}</option>
              ))}
            </select>
          </label>
        </div>

        <div className="rejilla">
          <label className="campo">
            <span>Código</span>
            <input value={datos.codigo} onChange={cambiar('codigo')} required maxLength={60} />
          </label>

          <label className="campo">
            <span>SKU</span>
            <input
              value={datos.sku}
              onChange={cambiar('sku')}
              maxLength={40}
              placeholder="Si lo dejas vacío se usa el código"
            />
          </label>
        </div>

        <div className="rejilla">
          <label className="campo">
            <span>Presentación</span>
            <input value={datos.presentacion} onChange={cambiar('presentacion')} maxLength={60} />
          </label>

          <label className="campo">
            <span>Código de barras</span>
            <input value={datos.codigoBarras} onChange={cambiar('codigoBarras')} maxLength={60} />
          </label>
        </div>

        <div className="rejilla">
          <label className="campo">
            <span>Costo</span>
            <input
              type="number" step="0.01" min="0.01"
              value={datos.costo} onChange={cambiar('costo')} required
            />
          </label>

          <label className="campo">
            <span>Existencia mínima</span>
            <input
              type="number" step="1" min="0"
              value={datos.existenciaMinima} onChange={cambiar('existenciaMinima')}
            />
          </label>
        </div>

        <label className="campo">
          <span>Descripción</span>
          <input value={datos.descripcion} onChange={cambiar('descripcion')} maxLength={400} />
        </label>

        <p className="sub-linea">
          Existencia actual: {producto.existencia}. Se cambia con movimientos de
          inventario, no desde aquí, para que todo cambio quede registrado con su
          valor anterior y posterior.
        </p>

        <div className="panel-acciones">
          <button className="boton" type="submit" disabled={guardando}>
            {guardando ? 'Guardando…' : 'Guardar cambios'}
          </button>
        </div>
      </form>
    </div>
  )
}

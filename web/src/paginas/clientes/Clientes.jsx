import { useEffect, useRef, useState } from 'react'
import FormularioCliente from '../../componentes/clientes/FormularioCliente.jsx'
import EncabezadoPagina from '../../componentes/comun/EncabezadoPagina.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import EstadoVacio from '../../componentes/comun/EstadoVacio.jsx'
import TablaOTarjetas from '../../componentes/comun/TablaOTarjetas.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { actualizarCliente, crearCliente, listarClientes } from '../../servicios/api/caja.js'
import { PESOS } from '../../servicios/formato.js'
import '../../estilos/caja.css'
import '../../estilos/clientes.css'

const NUEVO = { tipoDocumento: 'CC', identificacion: '' }

/**
 * Los clientes de la empresa: quien vende o administra busca, crea y corrige. El tipo va junto
 * al número porque la búsqueda por documento mira el principio del número y no distingue el tipo.
 * El saldo a favor solo se muestra: lo mueven las devoluciones, no esta pantalla.
 */
export default function Clientes() {
  const [texto, setTexto] = useState('')
  // Cada búsqueda o cambio de página es una consulta nueva (un objeto nuevo), aunque pida lo mismo.
  const [consulta, setConsulta] = useState({ buscar: '', pagina: 0 })
  // null = sin formulario; { cliente } = alta (cliente null) o corrección de ese cliente.
  const [formulario, setFormulario] = useState(null)
  const panel = useRef(null)
  const lista = useDatos(() => listarClientes(consulta), [consulta])

  useEffect(() => {
    if (formulario) panel.current?.focus()
  }, [formulario])

  const buscar = (evento) => {
    evento.preventDefault()
    setConsulta({ buscar: texto.trim(), pagina: 0 })
  }
  const irA = (pagina) => setConsulta({ ...consulta, pagina })
  const terminar = () => { setFormulario(null); lista.recargar() }

  const columnas = [
    {
      clave: 'nombre', titulo: 'Cliente', principal: true,
      render: (c) => (
        <>
          {c.nombre}
          {c.ciudad && <span className="sub-linea">{c.ciudad}</span>}
        </>
      ),
    },
    { clave: 'documento', titulo: 'Documento', render: (c) => `${c.tipoDocumento} ${c.identificacion}` },
    {
      clave: 'contacto', titulo: 'Contacto',
      render: (c) => (c.correo || c.telefono ? (
        <>
          {c.correo && <span>{c.correo}</span>}
          {c.telefono && <span className="sub-linea">{c.telefono}</span>}
        </>
      ) : '—'),
    },
    {
      clave: 'saldo', titulo: 'Saldo a favor', numerica: true,
      render: (c) => (Number(c.saldoAFavor) > 0
        ? PESOS.format(c.saldoAFavor)
        : <span className="clientes-saldo-cero">—</span>),
    },
    {
      clave: 'acciones', titulo: 'Acciones',
      render: (c) => (c.editable
        ? (
          <button
            type="button" className="boton secundario" aria-label={`Editar a ${c.nombre}`}
            onClick={() => setFormulario({ cliente: c })}
          >
            Editar
          </button>
        )
        : <span className="insignia">Del sistema</span>),
    },
  ]

  const pagina = lista.datos
  const totalPaginas = pagina ? Math.max(1, Math.ceil(pagina.total / pagina.tamano)) : 1

  let cuerpo
  if (lista.cargando) cuerpo = <EstadoCarga />
  else if (lista.sinPermiso) cuerpo = <EstadoSinPermiso />
  else if (lista.error) cuerpo = <EstadoError error={lista.error} alReintentar={lista.recargar} />
  else if (pagina.elementos.length === 0) {
    cuerpo = consulta.buscar
      ? <EstadoVacio titulo={`Ningún cliente coincide con «${consulta.buscar}».`} detalle="Prueba con otro nombre o con el principio del número de documento." />
      : <EstadoVacio titulo="Todavía no hay clientes." detalle="Crea el primero con «Nuevo cliente»." />
  } else {
    cuerpo = (
      <>
        <TablaOTarjetas etiqueta="Clientes" filas={pagina.elementos} claveFila={(c) => c.id} columnas={columnas} />
        <div className="clientes-paginacion">
          <p className="clientes-total">{pagina.total === 1 ? '1 cliente' : `${pagina.total} clientes`}</p>
          {totalPaginas > 1 && (
            <nav className="clientes-paginas" aria-label="Páginas de clientes">
              <button type="button" className="boton secundario" disabled={pagina.pagina <= 0} onClick={() => irA(pagina.pagina - 1)}>
                Anterior
              </button>
              <span>Página {pagina.pagina + 1} de {totalPaginas}</span>
              <button
                type="button" className="boton secundario" disabled={pagina.pagina + 1 >= totalPaginas}
                onClick={() => irA(pagina.pagina + 1)}
              >
                Siguiente
              </button>
            </nav>
          )}
        </div>
      </>
    )
  }

  const editando = formulario?.cliente
  return (
    <div className="contenido seccion-caja seccion-clientes">
      <EncabezadoPagina
        titulo="Clientes"
        subtitulo="Busca a quien te compra, crea clientes nuevos y corrige sus datos."
        acciones={(
          <button type="button" className="boton" onClick={() => setFormulario({ cliente: null })}>Nuevo cliente</button>
        )}
      />

      {formulario && (
        <section className="clientes-panel" ref={panel} tabIndex={-1} aria-labelledby="clientes-panel-titulo">
          <h2 id="clientes-panel-titulo">{editando ? `Editar a ${editando.nombre}` : 'Cliente nuevo'}</h2>
          <FormularioCliente
            key={editando?.id ?? 'nuevo'}
            documentoEditable
            domicilioEditable
            inicial={editando ?? NUEVO}
            titulo={editando ? 'Editar cliente' : 'Cliente nuevo'}
            textoGuardar={editando ? 'Guardar cambios' : 'Guardar cliente'}
            alCancelar={() => setFormulario(null)}
            alGuardar={async (cuerpoCliente) => {
              await (editando ? actualizarCliente(editando.id, cuerpoCliente) : crearCliente(cuerpoCliente))
              terminar()
            }}
          />
        </section>
      )}

      <form className="caja-filtros clientes-busqueda" role="search" onSubmit={buscar}>
        <label className="campo">
          <span>Buscar cliente</span>
          <input
            type="search" value={texto} onChange={(e) => setTexto(e.target.value)}
            placeholder="Nombre o documento" maxLength={80} autoComplete="off"
          />
        </label>
        <button type="submit" className="boton">Buscar</button>
      </form>

      {cuerpo}
    </div>
  )
}

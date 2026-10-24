/**
 * Pantalla P-02 · Panel de indicadores.
 *
 * Su especificación dice que el panel debe «dar en una sola vista el
 * estado del inventario y las alertas que exigen decisión», y enumera
 * tres estados a diseñar y dos reglas que la interfaz debe hacer
 * cumplir. Lo que se prueba aquí sale de esas dos listas, no del
 * código: los tres estados existen y se distinguen, los importes van
 * con separador de miles de punto, y la cifra de agotadas no se apoya
 * solo en el color.
 */
import { render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import Panel from './Panel.jsx'
import { api } from '../servicios/api.js'
import { guardarSesion } from '../servicios/sesion.js'
import { diaCorto } from '../servicios/formato.js'
import { PANEL, PANEL_VACIO } from '../prueba/panel.js'

vi.mock('../servicios/api.js', () => ({
  api: { panel: vi.fn(), salir: vi.fn() },
  ErrorApi: class extends Error {},
}))

function montar() {
  return render(<MemoryRouter><Panel /></MemoryRouter>)
}

/**
 * Monta y espera a que el panel esté cargado.
 *
 * No es azúcar. El esqueleto de carga lleva su propio
 * `role="status"` —«Cargando los indicadores…»— de modo que un
 * `findAllByRole('status')` resuelve con ESE y no con los avisos del
 * panel: resuelve en cuanto aparece el primero, y el primero es el del
 * esqueleto. Dos pruebas de este archivo pasaron a leer el esqueleto
 * por eso, y fallaron por el motivo correcto. Esperar a la región de
 * indicadores es esperar a que el esqueleto ya no esté.
 */
async function montarCargado() {
  montar()
  return screen.findByRole('region', { name: /indicadores/i })
}

/** Una copia del panel con un trozo cambiado, sin tocar el original. */
function panelCon(cambios) {
  return { ...PANEL, ...cambios }
}

beforeEach(() => {
  sessionStorage.clear()
  guardarSesion({ token: 'abc', nombre: 'Ana', rol: 'ADMINISTRADOR' })
  api.panel.mockResolvedValue(PANEL)
})
afterEach(() => vi.clearAllMocks())


describe('las cuatro cifras', () => {
  it('muestra las cuatro con su rótulo escrito', async () => {
    montar()
    const indicadores = await screen.findByRole('region', { name: /indicadores/i })
    expect(within(indicadores).getByText('Referencias activas')).toBeInTheDocument()
    expect(within(indicadores).getByText('Existencia crítica')).toBeInTheDocument()
    expect(within(indicadores).getByText('Agotadas')).toBeInTheDocument()
    expect(within(indicadores).getByText('Capital en inventario')).toBeInTheDocument()
  })

  it('el capital lleva el importe exacto además del abreviado', async () => {
    montar()
    // La regla de la especificación dice «importes en pesos colombianos
    // con separador de miles de punto». El abreviado del mockup —«$ 84,4
    // M»— no sirve para comparar con el panel de la semana pasada, de
    // modo que el exacto va al lado y no en un título flotante, que en
    // una pantalla táctil no se puede leer.
    await waitFor(() => expect(screen.getByText(/84\.350\.000/)).toBeInTheDocument())
    // «84,3» y no «84,4»: `toFixed` redondea sobre el binario y 84,35
    // millones queda justo por debajo. Está anotado en `formato.js`,
    // y la prueba lo fija para que nadie lo «arregle» sin leerlo.
    expect(screen.getByText(/84,3 M/)).toBeInTheDocument()
  })

  it('las unidades pendientes no se esconden cuando las hay', async () => {
    api.panel.mockResolvedValue(panelCon({
      cifras: { ...PANEL.cifras, unidadesPendientes: 3 },
    }))
    await montarCargado()
    // El capital no las resta —ADR-0019— y un capital que ignora la
    // deuda sin decirlo se lee como un inventario más sano de lo que es.
    const avisos = screen.getAllByRole('status')
    expect(avisos.some((a) => /3 unidades/.test(a.textContent))).toBe(true)
  })

  it('sin unidades pendientes no aparece esa línea', async () => {
    montar()
    await screen.findByRole('region', { name: /indicadores/i })
    expect(screen.queryByText(/unidades.*comprometidas/i)).not.toBeInTheDocument()
  })
})


describe('las dos reglas de interfaz', () => {
  it('la cifra de agotadas no comunica solo con el color', async () => {
    montar()
    const indicadores = await screen.findByRole('region', { name: /indicadores/i })
    // `--peligro` y `--aviso` son casi el mismo gris: 78,8 frente a 91,7
    // sobre 255, según la nota de tokens.css. Una cifra en rojo sin
    // rótulo escrito no dice nada a quien no distingue los dos tonos, ni
    // en una impresión en blanco y negro.
    const agotadas = within(indicadores).getByText('Agotadas').closest('article')
    expect(agotadas).not.toBeNull()
    expect(agotadas.textContent).toMatch(/Sin unidades disponibles/)
  })

  it('agotadas en rojo y críticas en ámbar, cada una con su clase', async () => {
    api.panel.mockResolvedValue(panelCon({
      cifras: { ...PANEL.cifras, agotadas: 12, enExistenciaCritica: 37 },
    }))
    montar()
    const indicadores = await screen.findByRole('region', { name: /indicadores/i })
    const agotadas = within(indicadores).getByText('12')
    const criticas = within(indicadores).getByText('37')
    expect(agotadas.className).toMatch(/peligro-cifra/)
    expect(criticas.className).toMatch(/aviso-texto/)
  })

  it('en cero ninguna de las dos se pinta de alarma', async () => {
    api.panel.mockResolvedValue(PANEL_VACIO)
    montar()
    const indicadores = await screen.findByRole('region', { name: /indicadores/i })
    const ceros = within(indicadores).getAllByText('0')
    expect(ceros.every((c) => !/peligro-cifra|aviso-texto/.test(c.className))).toBe(true)
  })
})


describe('la tabla corta de existencias críticas', () => {
  it('pone las agotadas antes que las críticas', async () => {
    montar()
    const seccion = await screen.findByRole('region', { name: /existencia crítica/i })
    const filas = within(seccion).getAllByRole('row').slice(1)
    expect(filas[0].textContent).toMatch(/SRV-001/)
    expect(filas[1].textContent).toMatch(/MON-001/)
  })

  it('cada referencia enlaza a su ficha', async () => {
    montar()
    const seccion = await screen.findByRole('region', { name: /existencia crítica/i })
    const enlace = within(seccion).getByRole('link', { name: 'Servidor de torre' })
    expect(enlace).toHaveAttribute('href', '/catalogo?producto=7')
  })

  it('trae el teléfono del proveedor, que es a quien hay que llamar', async () => {
    montar()
    const seccion = await screen.findByRole('region', { name: /existencia crítica/i })
    expect(within(seccion).getAllByText('6076111111').length).toBeGreaterThan(0)
  })

  it('sin referencias críticas dice que no hay nada que reponer', async () => {
    api.panel.mockResolvedValue(panelCon({ criticas: [] }))
    montar()
    const seccion = await screen.findByRole('region', { name: /existencia crítica/i })
    expect(within(seccion).getByText(/Nada que reponer hoy/)).toBeInTheDocument()
    // Y sin enlace a «ver todas»: llevaría a un catálogo filtrado y
    // vacío, que es ofrecer una puerta a un cuarto que no existe.
    expect(within(seccion).queryByRole('link', { name: /ver todas/i })).not.toBeInTheDocument()
  })
})


describe('la tarjeta de la última carga', () => {
  it('muestra proveedor, fecha, registros y estado', async () => {
    montar()
    const seccion = await screen.findByRole('region', { name: /última carga/i })
    expect(within(seccion).getByText('Mayorista Tech SAS')).toBeInTheDocument()
    expect(within(seccion).getByText(/412 registros/)).toBeInTheDocument()
    expect(within(seccion).getByText('Aplicada')).toBeInTheDocument()
    expect(within(seccion).getByText('398')).toBeInTheDocument()
    expect(within(seccion).getByText('14')).toBeInTheDocument()
  })

  it('sin cargas registradas invita a configurar un proveedor', async () => {
    api.panel.mockResolvedValue(PANEL_VACIO)
    montar()
    // Es el tercero de los tres estados que el mockup pide diseñar, y
    // no es una tarjeta con ceros: una tarjeta con ceros diría que hubo
    // una carga que no leyó nada, que es otra cosa.
    const seccion = await screen.findByRole('region', { name: /última carga/i })
    expect(within(seccion).getByText(/Sin cargas de precios registradas/)).toBeInTheDocument()
    expect(within(seccion).getByRole('link', { name: /configurar un proveedor/i }))
      .toHaveAttribute('href', '/importar')
  })

  it('una carga validada avisa de que todavía no cambió ningún costo', async () => {
    api.panel.mockResolvedValue(panelCon({
      ultimaCarga: { ...PANEL.ultimaCarga, estado: 'VALIDADA', fechaAplicacion: null },
      cruces: [],
    }))
    montar()
    const seccion = await screen.findByRole('region', { name: /última carga/i })
    expect(within(seccion).getByText('Validada, sin aplicar')).toBeInTheDocument()
    expect(within(seccion).getByText(/no ha cambiado ningún costo/)).toBeInTheDocument()
  })
})


describe('el aviso de cruce del umbral de IVA (RF-35)', () => {
  it('lista las referencias que cambiaron con su precio antes y después', async () => {
    montar()
    const seccion = await screen.findByRole('region', { name: /última carga/i })
    const aviso = within(seccion).getByText(/cambiaron de tratamiento tributario/)
      .closest('div')
    expect(aviso.textContent).toMatch(/PC-003/)
    expect(aviso.textContent).toMatch(/2\.580\.000/)
    expect(aviso.textContent).toMatch(/2\.640\.000/)
  })

  it('dice en qué dirección cruzó cada una, no solo que cruzó', async () => {
    montar()
    const seccion = await screen.findByRole('region', { name: /última carga/i })
    const aviso = within(seccion).getByText(/cambiaron de tratamiento tributario/)
      .closest('div')
    // Lo que una empresa necesita saber es si a partir de ahora cobra
    // IVA o deja de cobrarlo. «Cruzó el umbral» no lo dice.
    expect(aviso.textContent).toMatch(/ahora se factura con IVA/)
    expect(aviso.textContent).toMatch(/ahora se factura excluido/)
  })

  it('cuenta los cruces en la tarjeta aunque no haya ninguno', async () => {
    api.panel.mockResolvedValue(panelCon({ cruces: [] }))
    montar()
    const seccion = await screen.findByRole('region', { name: /última carga/i })
    expect(within(seccion).getByText('cruzaron el umbral')).toBeInTheDocument()
    expect(within(seccion).queryByText(/cambiaron de tratamiento/)).not.toBeInTheDocument()
  })

  it('con un solo cruce lo dice en singular', async () => {
    api.panel.mockResolvedValue(panelCon({ cruces: [PANEL.cruces[1]] }))
    montar()
    const seccion = await screen.findByRole('region', { name: /última carga/i })
    expect(within(seccion).getByText(/cambió de tratamiento tributario/)).toBeInTheDocument()
  })
})


describe('el gráfico de movimiento', () => {
  it('va con un resumen que se puede leer sin verlo', async () => {
    montar()
    const grafico = await screen.findByRole('img', { name: /movimiento de inventario/i })
    // Un `<svg>` de barras no dice nada a un lector de pantalla, y el
    // RNF-13 no admite que un elemento comunique solo por forma.
    expect(grafico).toHaveAccessibleName(/60 unidades ingresadas/)
    expect(grafico).toHaveAccessibleName(/0 despachadas/)
  })

  it('trae el detalle en una tabla, oculta a la vista pero no al lector', async () => {
    montar()
    await screen.findByRole('img', { name: /movimiento de inventario/i })
    const tabla = screen.getByRole('table', { name: /movimiento de inventario/i })
    // El encabezado de fila se compara con lo que el formateador
    // produce, no con una cadena escrita a mano: `es-CO` escribe «24 de
    // oct» y no «24 oct», y fijar la cadena aquí convertiría la prueba
    // en una afirmación sobre el idioma del entorno.
    expect(within(tabla).getByRole('rowheader', { name: diaCorto('2026-10-24') }))
      .toBeInTheDocument()
  })

  it('sin movimientos lo dice en lugar de dibujar un eje vacío', async () => {
    api.panel.mockResolvedValue(PANEL_VACIO)
    montar()
    const seccion = await screen.findByRole('region', { name: /movimiento de inventario/i })
    expect(within(seccion).getByText(/Sin movimientos de inventario/)).toBeInTheDocument()
    expect(within(seccion).queryByRole('img')).not.toBeInTheDocument()
  })
})


describe('los estados de carga y de error', () => {
  it('mientras llega la respuesta anuncia que está cargando', async () => {
    let resolver
    api.panel.mockReturnValue(new Promise((r) => { resolver = r }))
    montar()
    // El esqueleto es para quien ve; `role="status"` es para quien no.
    expect(screen.getByRole('status')).toHaveTextContent(/Cargando los indicadores/)
    expect(screen.getByRole('main')).toHaveAttribute('aria-busy', 'true')
    resolver(PANEL)
    await screen.findByRole('region', { name: /indicadores/i })
  })

  it('una empresa sin catálogo recibe una explicación y no un panel mudo', async () => {
    api.panel.mockResolvedValue(PANEL_VACIO)
    await montarCargado()
    const avisos = screen.getAllByRole('status')
    expect(avisos.some((a) => /Todavía no hay referencias/.test(a.textContent))).toBe(true)
  })

  it('con catálogo no aparece el mensaje de primera instalación', async () => {
    montar()
    await screen.findByRole('region', { name: /indicadores/i })
    expect(screen.queryByText(/Todavía no hay referencias/)).not.toBeInTheDocument()
  })

  it('un fallo del servidor se muestra como alerta y no como panel en cero', async () => {
    api.panel.mockRejectedValue(new Error('No se pudo completar la operación.'))
    montar()
    // Un panel en cero y un panel que no se pudo leer son cosas
    // distintas: el primero dice «no tienes nada» y el segundo «no sé».
    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('No se pudo completar la operación.')
    expect(screen.queryByText('Referencias activas')).not.toBeInTheDocument()
  })
})

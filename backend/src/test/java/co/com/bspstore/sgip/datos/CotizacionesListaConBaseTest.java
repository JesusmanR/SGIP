package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.Cliente;
import co.com.bspstore.sgip.api.dto.CotizacionDetalle;
import co.com.bspstore.sgip.api.dto.CotizacionEnLista;
import co.com.bspstore.sgip.api.dto.LineaCotizada;
import co.com.bspstore.sgip.api.dto.PaginaDeCotizaciones;
import co.com.bspstore.sgip.api.dto.SolicitudCotizacion;
import co.com.bspstore.sgip.logica.CotizacionNoAnulableException;
import co.com.bspstore.sgip.logica.FiltroInvalidoException;
import co.com.bspstore.sgip.logica.RecursoNoEncontradoException;
import co.com.bspstore.sgip.logica.ServicioCotizaciones;
import co.com.bspstore.sgip.modelo.TipoDocumento;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La lista, la búsqueda por número y la anulación de cotizaciones (RF-37) contra el esquema real. El reloj es el
 * de {@link CotizacionConBaseTest}: la prueba lo mueve para que una cotización venza. Crea sus empresas y las
 * borra al terminar.
 *
 * Las consultas se cuentan como {@code ClientesListaConBaseTest}: con un espía sobre {@code JdbcClient}.
 */
@Tag("conBase")
@SpringBootTest
@Import(CotizacionConBaseTest.Configuracion.class)
class CotizacionesListaConBaseTest {

    /** El miércoles 1 de abril de 2026, a las 10:00 en Bogotá: la cotización vence el 10 de abril. */
    private static final String UNO_DE_ABRIL = "2026-04-01T15:00:00Z";
    /** Ya pasó el 10 de abril. */
    private static final String TRECE_DE_ABRIL = "2026-04-13T15:00:00Z";

    @Autowired ServicioCotizaciones cotizaciones;
    @Autowired CotizacionConBaseTest.RelojDePrueba reloj;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioClientes clientes;
    @Autowired PlatformTransactionManager gestor;
    @MockitoSpyBean JdbcClient jdbc;

    private CajaDePrueba caja;
    private CajaDePrueba otra;
    private Integer producto;

    @BeforeEach
    void prepararDatosPropios() {
        caja = CajaDePrueba.crear("Lista de cotizaciones", jdbc, plataforma, categorias, proveedores, clientes);
        reloj.fijar(UNO_DE_ABRIL);
        vigenciaDe(caja, 5);
        producto = caja.producto("A", 500, "10000");
    }

    @AfterEach
    void borrarLoQueCreo() {
        if (caja != null) {
            caja.borrar();
        }
        if (otra != null) {
            otra.borrar();
        }
    }

    // ------------------------------------------------------- ayudas

    private void vigenciaDe(CajaDePrueba de, int dias) {
        jdbc.sql("""
                INSERT INTO empresa_config (empresa_id, cotizacion_vigencia_dias) VALUES (:e, :dias)
                ON DUPLICATE KEY UPDATE cotizacion_vigencia_dias = :dias
                """).param("e", de.empresa.empresaId).param("dias", dias).update();
    }

    private CotizacionDetalle crear(CajaDePrueba de, Integer usuario, Integer cliente, Integer producto) {
        return cotizaciones.crear(de.empresa.empresaId, usuario,
                new SolicitudCotizacion(cliente, List.of(new LineaCotizada(producto, 1)), null));
    }

    private CotizacionDetalle crear() {
        return crear(caja, caja.vendedorId, caja.clienteId, producto);
    }

    private Integer clienteNuevo(String nombre, String identificacion) {
        return clientes.insertar(caja.empresa.empresaId,
                new Cliente(null, nombre, TipoDocumento.CC, identificacion, null, "3001112233", "Calle 1", "Cali", false),
                LocalDateTime.of(2026, 3, 1, 8, 0), "CAJA");
    }

    private void convertir(Long id) {
        jdbc.sql("UPDATE cotizaciones SET estado = 'CONVERTIDA' WHERE empresa_id = :e AND cotizacion_id = :id")
                .param("e", caja.empresa.empresaId).param("id", id).update();
    }

    private void anularDirecto(Long id) {
        jdbc.sql("""
                UPDATE cotizaciones SET estado = 'ANULADA', anulada_por = :u, anulada_en = '2026-04-02 09:00:00',
                                        motivo_anulacion = 'Duplicada'
                 WHERE empresa_id = :e AND cotizacion_id = :id
                """).param("e", caja.empresa.empresaId).param("u", caja.administradorId).param("id", id).update();
    }

    private PaginaDeCotizaciones lista(String estado, String texto) {
        return cotizaciones.listar(caja.empresa.empresaId, estado, texto, 0, 100);
    }

    private static List<Long> ids(PaginaDeCotizaciones pagina) {
        return pagina.elementos().stream().map(CotizacionEnLista::id).toList();
    }

    private Map<String, Object> fila(Long id) {
        return jdbc.sql("SELECT * FROM cotizaciones WHERE empresa_id = :e AND cotizacion_id = :id")
                .param("e", caja.empresa.empresaId).param("id", id).query().singleRow();
    }

    private long sentenciasDe(Runnable operacion) {
        Mockito.clearInvocations(jdbc);
        operacion.run();
        return Mockito.mockingDetails(jdbc).getInvocations().stream()
                .filter(llamada -> llamada.getMethod().getName().equals("sql"))
                .count();
    }

    // ------------------------------------------------------- lista

    @Test
    void filtraPorEstado() {
        CotizacionDetalle vencida = crear();           // vence el 10 de abril
        reloj.fijar(TRECE_DE_ABRIL);
        CotizacionDetalle vigente = crear();
        CotizacionDetalle convertida = crear();
        CotizacionDetalle anulada = crear();
        convertir(convertida.id());
        anularDirecto(anulada.id());

        assertEquals(List.of(vigente.id()), ids(lista("VIGENTE", null)));
        assertEquals(List.of(vencida.id()), ids(lista("VENCIDA", null)));
        assertEquals(List.of(convertida.id()), ids(lista("CONVERTIDA", null)));
        assertEquals(List.of(anulada.id()), ids(lista("ANULADA", null)));
        // Sin filtro salen las cuatro, la más reciente primero, cada una con su estado calculado.
        PaginaDeCotizaciones todas = lista(null, null);
        assertEquals(List.of(anulada.id(), convertida.id(), vigente.id(), vencida.id()), ids(todas));
        assertEquals(List.of("ANULADA", "CONVERTIDA", "VIGENTE", "VENCIDA"),
                todas.elementos().stream().map(CotizacionEnLista::estado).toList());
        assertEquals(4, todas.total());
        assertEquals(1, lista("VENCIDA", null).total());
        // El estado guardado de la vencida sigue siendo VIGENTE: VENCIDA no se escribe.
        assertEquals("VIGENTE", fila(vencida.id()).get("estado"));
    }

    @Test
    void elDiaDelVencimientoSigueVigente() {
        CotizacionDetalle c = crear();

        reloj.fijar("2026-04-10T15:00:00Z");
        assertEquals(List.of(c.id()), ids(lista("VIGENTE", null)));
        assertEquals(List.of(), ids(lista("VENCIDA", null)));

        reloj.fijar("2026-04-10T04:59:00Z");   // las 11:59 p. m. del 9 de abril en Bogotá
        assertEquals(List.of(c.id()), ids(lista("VIGENTE", null)));

        reloj.fijar("2026-04-11T05:00:00Z");   // la medianoche del 11 en Bogotá
        assertEquals(List.of(), ids(lista("VIGENTE", null)));
        assertEquals(List.of(c.id()), ids(lista("VENCIDA", null)));
    }

    @Test
    void cadaFilaTrae() {
        Integer cliente = clienteNuevo("María Gómez", "1098001");
        CotizacionDetalle c = crear(caja, caja.vendedorId, cliente, producto);

        CotizacionEnLista fila = lista(null, null).elementos().get(0);

        assertEquals(c.id(), fila.id());
        assertEquals("COT-00000001", fila.numero());
        assertEquals(c.fechaEmision(), fila.fechaEmision());
        assertEquals(c.fechaVencimiento(), fila.fechaVencimiento());
        assertEquals("María Gómez", fila.clienteNombre());
        assertEquals(0, c.total().compareTo(fila.total()));
        assertEquals("VIGENTE", fila.estado());
    }

    @Test
    void estadoQueNoExisteEs400() {
        assertThrows(FiltroInvalidoException.class, () -> lista("PENDIENTE", null));
        assertThrows(FiltroInvalidoException.class, () -> lista("BORRADOR", null));
    }

    @Test
    void paginaYTamanoFueraDeRangoSon400() {
        assertThrows(FiltroInvalidoException.class, () -> cotizaciones.listar(caja.empresa.empresaId, null, null, -1, 20));
        assertThrows(FiltroInvalidoException.class, () -> cotizaciones.listar(caja.empresa.empresaId, null, null, 0, 0));
    }

    @Test
    void paginaDeAUnoEnOrdenDescendente() {
        CotizacionDetalle a = crear();
        CotizacionDetalle b = crear();
        CotizacionDetalle c = crear();

        PaginaDeCotizaciones primera = cotizaciones.listar(caja.empresa.empresaId, null, null, 0, 2);
        PaginaDeCotizaciones segunda = cotizaciones.listar(caja.empresa.empresaId, null, null, 1, 2);

        assertEquals(List.of(c.id(), b.id()), ids(primera));
        assertEquals(List.of(a.id()), ids(segunda));
        assertEquals(3, segunda.total());
        assertEquals(1, segunda.pagina());
        assertEquals(2, segunda.tamano());
    }

    @Test
    void buscaPorTexto() {
        Integer maria = clienteNuevo("María Gómez", "1098001");
        Integer pedro = clienteNuevo("Pedro Ruiz", "2077002");
        CotizacionDetalle deMaria = crear(caja, caja.vendedorId, maria, producto);
        CotizacionDetalle dePedro = crear(caja, caja.vendedorId, pedro, producto);

        // Por número, escrito de cualquiera de las formas.
        assertEquals(List.of(deMaria.id()), ids(lista(null, "COT-00000001")));
        assertEquals(List.of(dePedro.id()), ids(lista(null, "cot-2")));
        assertEquals(List.of(dePedro.id()), ids(lista(null, " 2 ")));
        // Por nombre del cliente, sin distinguir mayúsculas ni tildes.
        assertEquals(List.of(deMaria.id()), ids(lista(null, "MARIA")));
        assertEquals(List.of(dePedro.id()), ids(lista(null, "ruiz")));
        // Por identificación.
        assertEquals(List.of(deMaria.id()), ids(lista(null, "1098001")));
        assertEquals(List.of(dePedro.id()), ids(lista(null, "2077")));
        // Nada.
        assertEquals(List.of(), ids(lista(null, "zzz-no-existe")));
        assertEquals(0, lista(null, "zzz-no-existe").total());
        // El texto en blanco no filtra y se combina con el estado.
        assertEquals(2, lista(null, "   ").total());
        convertir(deMaria.id());
        assertEquals(List.of(deMaria.id()), ids(lista("CONVERTIDA", "maria")));
        assertEquals(List.of(), ids(lista("VIGENTE", "maria")));
    }

    @Test
    void buscarConComodinesEsLiteral() {
        Integer conPorcentaje = clienteNuevo("Cien%Por Ciento", "8001");
        Integer sinPorcentaje = clienteNuevo("Cien Por Ciento", "8002");
        CotizacionDetalle a = crear(caja, caja.vendedorId, conPorcentaje, producto);
        crear(caja, caja.vendedorId, sinPorcentaje, producto);

        assertEquals(List.of(a.id()), ids(lista(null, "n%P")));
        assertEquals(1, lista(null, "%").total(), "el comodín suelto solo trae al que lo tiene escrito");
    }

    @Test
    void consultasConstantesCon5Y50Cotizaciones() {
        for (int i = 0; i < 5; i++) {
            crear();
        }
        lista(null, null);
        long conCinco = sentenciasDe(() -> assertEquals(5, lista(null, null).elementos().size()));
        long conCincoYBusqueda = sentenciasDe(() -> lista("VIGENTE", "COT-1"));

        for (int i = 5; i < 50; i++) {
            crear();
        }
        long conCincuenta = sentenciasDe(() -> assertEquals(50, lista(null, null).elementos().size()));
        long conCincuentaYBusqueda = sentenciasDe(() -> lista("VIGENTE", "COT-1"));

        assertEquals(conCinco, conCincuenta, "con cinco fueron " + conCinco + " consultas y con cincuenta, " + conCincuenta);
        assertEquals(conCincoYBusqueda, conCincuentaYBusqueda);
        assertEquals(conCinco, conCincuentaYBusqueda);
        assertTrue(conCincuenta <= 2, "una página costó " + conCincuenta + " consultas");
    }

    @Test
    void otraEmpresaNoAparece() {
        otra = CajaDePrueba.crear("Otra tienda", jdbc, plataforma, categorias, proveedores, clientes);
        vigenciaDe(otra, 5);
        Integer deOtra = otra.producto("A", 5, "10000");
        CotizacionDetalle propia = crear();
        CotizacionDetalle ajena = crear(otra, otra.vendedorId, otra.clienteId, deOtra);

        PaginaDeCotizaciones mia = lista(null, null);
        PaginaDeCotizaciones suya = cotizaciones.listar(otra.empresa.empresaId, null, null, 0, 100);

        assertEquals(List.of(propia.id()), ids(mia));
        assertEquals(1, mia.total());
        assertEquals(List.of(ajena.id()), ids(suya));
        // Buscar por el cliente de la otra no la trae.
        assertEquals(0, lista(null, "Cliente " + otra.empresa.marca.toLowerCase()).total());
    }

    // ------------------------------------------------------- por número

    @Test
    void porNumero() {
        CotizacionDetalle doce = null;
        for (int i = 0; i < 12; i++) {
            doce = crear();
        }
        assertEquals("COT-00000012", doce.numero());
        Long esperado = doce.id();

        for (String escrito : List.of("12", "cot-12", "COT-00000012", "Cot-0012", "  012 ", "COT-12")) {
            CotizacionDetalle c = cotizaciones.porNumero(caja.empresa.empresaId, escrito);
            assertEquals(esperado, c.id(), "escrito «" + escrito + "»");
            assertEquals("COT-00000012", c.numero());
            assertEquals(1, c.lineas().size(), "trae las líneas, como el detalle por id");
        }
        for (String malo : List.of("abc", "cot-", "COT-12x", "1 2", "", "   ", "-12", "99", "0",
                "123456789012345678901234567890")) {
            assertThrows(RecursoNoEncontradoException.class,
                    () -> cotizaciones.porNumero(caja.empresa.empresaId, malo), "escrito «" + malo + "»");
        }
        assertThrows(RecursoNoEncontradoException.class, () -> cotizaciones.porNumero(caja.empresa.empresaId, null));
    }

    @Test
    void elNumeroDeOtraEmpresaEsUn404() {
        otra = CajaDePrueba.crear("Otra tienda", jdbc, plataforma, categorias, proveedores, clientes);
        vigenciaDe(otra, 5);
        Integer deOtra = otra.producto("A", 5, "10000");
        crear();                                              // COT-00000001 de la propia
        crear(otra, otra.vendedorId, otra.clienteId, deOtra); // COT-00000001 de la otra
        crear(otra, otra.vendedorId, otra.clienteId, deOtra); // COT-00000002 solo de la otra

        assertEquals("COT-00000001", cotizaciones.porNumero(caja.empresa.empresaId, "1").numero());
        assertThrows(RecursoNoEncontradoException.class, () -> cotizaciones.porNumero(caja.empresa.empresaId, "2"));
        assertEquals("COT-00000002", cotizaciones.porNumero(otra.empresa.empresaId, "cot-2").numero());
    }

    @Test
    void porNumeroMarcaLaVencidaYElPrecioDeHoy() {
        CotizacionDetalle c = crear();
        reloj.fijar(TRECE_DE_ABRIL);

        CotizacionDetalle vencida = cotizaciones.porNumero(caja.empresa.empresaId, "1");

        assertEquals(c.id(), vencida.id());
        assertEquals("VENCIDA", vencida.estado());
        assertEquals(0, new java.math.BigDecimal("10000").compareTo(vencida.lineas().get(0).precioHoy()));
    }

    // ------------------------------------------------------- anular

    @Test
    void anular() {
        CotizacionDetalle c = crear();
        reloj.fijar("2026-04-02T16:30:15Z");

        CotizacionDetalle anulada = cotizaciones.anular(caja.empresa.empresaId, caja.vendedorId, false, c.id(),
                "El cliente ya compró en otra parte");

        assertEquals("ANULADA", anulada.estado());
        assertEquals("El cliente ya compró en otra parte", anulada.motivoAnulacion());
        Map<String, Object> fila = fila(c.id());
        assertEquals("ANULADA", fila.get("estado"));
        assertEquals(caja.vendedorId, fila.get("anulada_por"));
        assertEquals("El cliente ya compró en otra parte", fila.get("motivo_anulacion"));
        LocalDateTime cuando = jdbc.sql("SELECT anulada_en FROM cotizaciones WHERE cotizacion_id = :id")
                .param("id", c.id()).query(LocalDateTime.class).single();
        assertEquals(LocalDateTime.of(2026, 4, 2, 11, 30, 15), cuando, "la hora de Bogotá del reloj de la aplicación");
        assertEquals(List.of(c.id()), ids(lista("ANULADA", null)));
        assertEquals(List.of(), ids(lista("VIGENTE", null)));
        assertEquals(1, caja.cuantas("cotizacion_items"), "las líneas se conservan");
    }

    @Test
    void anularUnaVencida() {
        CotizacionDetalle c = crear();
        reloj.fijar(TRECE_DE_ABRIL);
        assertEquals("VENCIDA", cotizaciones.detalle(caja.empresa.empresaId, c.id()).estado());

        CotizacionDetalle anulada = cotizaciones.anular(caja.empresa.empresaId, caja.administradorId, true, c.id(), "Ya no aplica");

        assertEquals("ANULADA", anulada.estado());
        assertEquals(caja.administradorId, fila(c.id()).get("anulada_por"));
    }

    @Test
    void anularDeNuevoEsUn409() {
        CotizacionDetalle c = crear();
        cotizaciones.anular(caja.empresa.empresaId, caja.vendedorId, false, c.id(), "Primera vez");

        CotizacionNoAnulableException e = assertThrows(CotizacionNoAnulableException.class,
                () -> cotizaciones.anular(caja.empresa.empresaId, caja.vendedorId, false, c.id(), "Segunda vez"));

        assertEquals("Solo se anula una cotización vigente o vencida.", e.getMessage());
        assertEquals("Primera vez", fila(c.id()).get("motivo_anulacion"), "no se pisa el motivo original");
    }

    @Test
    void anularUnaConvertidaEsUn409() {
        CotizacionDetalle c = crear();
        convertir(c.id());

        assertThrows(CotizacionNoAnulableException.class,
                () -> cotizaciones.anular(caja.empresa.empresaId, caja.administradorId, true, c.id(), "Tarde"));

        assertEquals("CONVERTIDA", fila(c.id()).get("estado"));
        assertNull(fila(c.id()).get("anulada_por"));
    }

    @Test
    void unVendedorSoloAnulaLaQueCreoYElAdminCualquiera() {
        CotizacionDetalle deAdmin = crear(caja, caja.administradorId, caja.clienteId, producto);
        CotizacionDetalle deVendedor = crear(caja, caja.vendedorId, caja.clienteId, producto);

        assertThrows(AccessDeniedException.class,
                () -> cotizaciones.anular(caja.empresa.empresaId, caja.vendedorId, false, deAdmin.id(), "No es mía"));
        assertEquals("VIGENTE", fila(deAdmin.id()).get("estado"));

        CotizacionDetalle propia = cotizaciones.anular(caja.empresa.empresaId, caja.vendedorId, false, deVendedor.id(), "Es mía");
        assertEquals("ANULADA", propia.estado());
        CotizacionDetalle ajena = cotizaciones.anular(caja.empresa.empresaId, caja.administradorId, true, deAdmin.id(), "Soy admin");
        assertEquals("ANULADA", ajena.estado());
    }

    @Test
    void unVendedorSinPermisoNoSabeElEstadoDeLaAjena() {
        // El permiso se comprueba antes que el estado: una anulada ajena da 403, no 409.
        CotizacionDetalle deAdmin = crear(caja, caja.administradorId, caja.clienteId, producto);
        anularDirecto(deAdmin.id());

        assertThrows(AccessDeniedException.class,
                () -> cotizaciones.anular(caja.empresa.empresaId, caja.vendedorId, false, deAdmin.id(), "No es mía"));
    }

    @Test
    void anularUnaDeOtraEmpresaOInexistenteEsUn404() {
        otra = CajaDePrueba.crear("Otra tienda", jdbc, plataforma, categorias, proveedores, clientes);
        CotizacionDetalle c = crear();

        assertThrows(RecursoNoEncontradoException.class,
                () -> cotizaciones.anular(otra.empresa.empresaId, otra.administradorId, true, c.id(), "Ajena"));
        assertThrows(RecursoNoEncontradoException.class,
                () -> cotizaciones.anular(caja.empresa.empresaId, caja.administradorId, true, 987_654_321L, "No existe"));

        assertEquals("VIGENTE", fila(c.id()).get("estado"));
    }

    @Test
    void elMotivoSeRecortaYUnoEnBlancoOMuyLargoEs400() {
        CotizacionDetalle c = crear();

        assertThrows(FiltroInvalidoException.class,
                () -> cotizaciones.anular(caja.empresa.empresaId, caja.administradorId, true, c.id(), "     "));
        assertThrows(FiltroInvalidoException.class,
                () -> cotizaciones.anular(caja.empresa.empresaId, caja.administradorId, true, c.id(), null));
        assertThrows(FiltroInvalidoException.class,
                () -> cotizaciones.anular(caja.empresa.empresaId, caja.administradorId, true, c.id(), "x".repeat(201)));
        assertEquals("VIGENTE", fila(c.id()).get("estado"));

        // 200 letras con espacios alrededor valen: el tamaño se mide después de recortar.
        String justo = "y".repeat(200);
        CotizacionDetalle anulada = cotizaciones.anular(caja.empresa.empresaId, caja.administradorId, true, c.id(),
                "  " + justo + "  ");

        assertEquals(justo, anulada.motivoAnulacion());
        assertEquals(justo, fila(c.id()).get("motivo_anulacion"));
    }

    @Test
    void anularTomaLaFilaYVeElEstadoYaConfirmado() throws Exception {
        CotizacionDetalle c = crear();
        CountDownLatch bloqueada = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            // Otra transacción (la conversión de la tarea 6) toma la fila, la convierte y tarda en confirmar.
            Future<?> conversion = hilos.submit(() -> new TransactionTemplate(gestor).executeWithoutResult(t -> {
                jdbc.sql("SELECT estado FROM cotizaciones WHERE empresa_id = :e AND cotizacion_id = :id FOR UPDATE")
                        .param("e", caja.empresa.empresaId).param("id", c.id()).query(String.class).single();
                jdbc.sql("UPDATE cotizaciones SET estado = 'CONVERTIDA' WHERE empresa_id = :e AND cotizacion_id = :id")
                        .param("e", caja.empresa.empresaId).param("id", c.id()).update();
                bloqueada.countDown();
                try {
                    soltar.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(bloqueada.await(30, TimeUnit.SECONDS));

            Future<CotizacionDetalle> anulacion = hilos.submit(() ->
                    cotizaciones.anular(caja.empresa.empresaId, caja.administradorId, true, c.id(), "Compite"));
            Thread.sleep(800);
            assertFalse(anulacion.isDone(), "la anulación espera a quien tiene la fila");

            soltar.countDown();
            conversion.get(30, TimeUnit.SECONDS);
            ExecutionException e = assertThrows(ExecutionException.class, () -> anulacion.get(30, TimeUnit.SECONDS));
            assertInstanceOf(CotizacionNoAnulableException.class, e.getCause(),
                    "al entrar ve la cotización ya convertida, no la vigente que leyó antes");
        } finally {
            soltar.countDown();
            hilos.shutdownNow();
        }
        assertEquals("CONVERTIDA", fila(c.id()).get("estado"));
        assertNull(fila(c.id()).get("motivo_anulacion"));
    }
}

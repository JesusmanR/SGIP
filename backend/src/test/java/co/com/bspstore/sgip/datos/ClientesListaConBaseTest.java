package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.Cliente;
import co.com.bspstore.sgip.api.dto.ClienteEnLista;
import co.com.bspstore.sgip.api.dto.PaginaDeClientes;
import co.com.bspstore.sgip.logica.FiltroInvalidoException;
import co.com.bspstore.sgip.logica.ServicioClientes;
import co.com.bspstore.sgip.logica.ServicioSaldoAFavor;
import co.com.bspstore.sgip.modelo.TipoDocumento;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La lista paginada de clientes contra el esquema real: lo que el SQL filtra, ordena, cuenta y suma
 * (el saldo a favor). Crea sus empresas y las borra al terminar.
 *
 * Las consultas se cuentan como {@code ListasDeCajaConsultasConBaseTest}: con un espía sobre
 * {@code JdbcClient}.
 */
@Tag("conBase")
@SpringBootTest
class ClientesListaConBaseTest {

    @Autowired ServicioClientes servicio;
    @Autowired ServicioSaldoAFavor saldos;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioClientes clientes;
    @MockitoSpyBean JdbcClient jdbc;

    private CajaDePrueba caja;
    private CajaDePrueba otra;

    @BeforeEach
    void prepararDatosPropios() {
        caja = CajaDePrueba.crear("Lista de clientes", jdbc, plataforma, categorias, proveedores, clientes);
        otra = CajaDePrueba.crear("Otra tienda", jdbc, plataforma, categorias, proveedores, clientes);
        caja.entrarComoAdministrador();
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

    private Integer nuevo(CajaDePrueba de, String nombre, String identificacion) {
        return nuevo(de, nombre, identificacion, "Calle 1", "Cali");
    }

    private Integer nuevo(CajaDePrueba de, String nombre, String identificacion, String direccion, String ciudad) {
        return clientes.insertar(de.empresa.empresaId,
                new Cliente(null, nombre, TipoDocumento.CC, identificacion, null, "3001112233", direccion, ciudad, false),
                LocalDateTime.of(2026, 11, 1, 8, 0), "CAJA");
    }

    private void conSaldo(CajaDePrueba de, Integer cliente, String saldo) {
        jdbc.sql("INSERT INTO saldos_cliente (empresa_id, cliente_id, saldo) VALUES (:e, :c, :s)")
                .param("e", de.empresa.empresaId).param("c", cliente).param("s", new BigDecimal(saldo)).update();
    }

    private static List<String> nombres(PaginaDeClientes pagina) {
        return pagina.elementos().stream().map(ClienteEnLista::nombre).toList();
    }

    @Test
    void listaSoloLosDeLaEmpresa() {
        nuevo(caja, "Ana Propia", "7001");
        nuevo(otra, "Beto Ajeno", "7002");

        PaginaDeClientes pagina = servicio.listar(null, 0, 100);

        // El Consumidor final, el cliente de la caja de prueba y el propio: tres. El ajeno no sale.
        assertEquals(3, pagina.total());
        assertTrue(nombres(pagina).contains("Ana Propia"));
        assertFalse(nombres(pagina).contains("Beto Ajeno"));
    }

    @Test
    void buscaPorNombreYPorDocumento() {
        nuevo(caja, "María Gómez", "1098001");
        nuevo(caja, "Pedro Mariño", "1098002");
        nuevo(caja, "Luis Torres", "5551098");

        // Por contenido del nombre, sin distinguir mayúsculas ni tildes.
        assertEquals(List.of("María Gómez", "Pedro Mariño"), nombres(servicio.listar("MARI", 0, 20)));
        // Por prefijo del documento: «1098» abre los dos primeros y no el que lo trae al final.
        assertEquals(List.of("María Gómez", "Pedro Mariño"), nombres(servicio.listar("1098", 0, 20)));
        assertEquals(1, servicio.listar("1098001", 0, 20).total());
        assertEquals(0, servicio.listar("zzz-no-existe", 0, 20).total());
    }

    @Test
    void buscarConComodinesEsLiteral() {
        nuevo(caja, "Cien%Por Ciento", "8001");
        nuevo(caja, "Cien Por Ciento", "8002");
        nuevo(caja, "Guion_Bajo", "8003");
        nuevo(caja, "GuionXBajo", "8004");
        nuevo(caja, "Barra\\Inversa", "8005");

        assertEquals(List.of("Cien%Por Ciento"), nombres(servicio.listar("n%P", 0, 20)));
        assertEquals(List.of("Guion_Bajo"), nombres(servicio.listar("n_B", 0, 20)));
        assertEquals(List.of("Barra\\Inversa"), nombres(servicio.listar("a\\I", 0, 20)));
        // Un comodín suelto no abre todo: solo lo trae quien lo tiene escrito en el nombre.
        assertEquals(1, servicio.listar("%", 0, 20).total());
    }

    @Test
    void paginaYTotalCorrectos() {
        for (int i = 1; i <= 7; i++) {
            nuevo(caja, "Zeta " + i, "6000" + i);
        }

        PaginaDeClientes primera = servicio.listar("Zeta", 0, 3);
        PaginaDeClientes tercera = servicio.listar("Zeta", 2, 3);
        PaginaDeClientes cuarta = servicio.listar("Zeta", 3, 3);

        assertEquals(7, primera.total());
        assertEquals(List.of("Zeta 1", "Zeta 2", "Zeta 3"), nombres(primera));
        assertEquals(List.of("Zeta 7"), nombres(tercera));
        assertEquals(List.of(), nombres(cuarta));
        assertEquals(7, cuarta.total());
        assertEquals(3, cuarta.pagina());
        assertEquals(3, cuarta.tamano());
    }

    @Test
    void ordenaPorNombreYDespuesPorId() {
        Integer primero = nuevo(caja, "Mismo Nombre", "6101");
        Integer segundo = nuevo(caja, "Mismo Nombre", "6102");

        List<Integer> ids = servicio.listar("Mismo Nombre", 0, 20).elementos().stream()
                .map(ClienteEnLista::id).toList();

        assertEquals(List.of(primero, segundo), ids);
    }

    @Test
    void paginaNegativaEs400() {
        assertThrows(FiltroInvalidoException.class, () -> servicio.listar(null, -1, 20));
        assertThrows(FiltroInvalidoException.class, () -> servicio.listar(null, 0, 0));
    }

    @Test
    void tamanoMayorQue100SeLimita() {
        for (int i = 0; i < 105; i++) {
            nuevo(caja, "Muchos " + String.format("%03d", i), "62" + String.format("%03d", i));
        }

        PaginaDeClientes pagina = servicio.listar("Muchos", 0, 1000);

        assertEquals(100, pagina.tamano());
        assertEquals(100, pagina.elementos().size());
        assertEquals(105, pagina.total());
    }

    @Test
    void saldoIgualAlDelEndpointDeSaldo() {
        Integer ana = nuevo(caja, "Ana Con Saldo", "6301");
        conSaldo(caja, ana, "12500.50");

        ClienteEnLista fila = servicio.listar("Ana Con Saldo", 0, 20).elementos().get(0);

        assertEquals(new BigDecimal("12500.50"), fila.saldoAFavor());
        assertEquals(saldos.consultar(ana).saldo(), fila.saldoAFavor());
    }

    @Test
    void clienteSinFilaDeSaldoTraeCero() {
        Integer beto = nuevo(caja, "Beto Sin Saldo", "6401");

        ClienteEnLista fila = servicio.listar("Beto Sin Saldo", 0, 20).elementos().get(0);

        assertEquals(new BigDecimal("0.00"), fila.saldoAFavor());
        assertEquals(saldos.consultar(beto).saldo(), fila.saldoAFavor());
    }

    @Test
    void consumidorFinalNoEsEditable() {
        nuevo(caja, "Cliente Normal", "6501");

        List<ClienteEnLista> todos = servicio.listar(null, 0, 100).elementos();

        ClienteEnLista consumidor = todos.stream()
                .filter(c -> ServicioClientes.IDENTIFICACION_CONSUMIDOR_FINAL.equals(c.identificacion()))
                .findFirst().orElseThrow();
        assertEquals(ServicioClientes.TIPO_CONSUMIDOR_FINAL, consumidor.tipoDocumento());
        assertFalse(consumidor.editable());
        assertEquals(new BigDecimal("0.00"), consumidor.saldoAFavor());
        todos.stream().filter(c -> c != consumidor).forEach(c -> assertTrue(c.editable(), c.nombre()));
    }

    @Test
    void traeDireccionYCiudad() {
        nuevo(caja, "Con Domicilio", "6601", "Carrera 9 # 10-11", "Bucaramanga");

        ClienteEnLista fila = servicio.listar("Con Domicilio", 0, 20).elementos().get(0);

        assertEquals("Carrera 9 # 10-11", fila.direccion());
        assertEquals("Bucaramanga", fila.ciudad());
        assertEquals("3001112233", fila.telefono());
        assertEquals(TipoDocumento.CC, fila.tipoDocumento());
        assertEquals("6601", fila.identificacion());
    }

    @Test
    void autorizoDatosSigueLoGuardado() {
        Integer autorizado = nuevo(caja, "Autorizado", "6701");
        Integer sinAutorizar = nuevo(caja, "Sin Autorizar", "6702");
        jdbc.sql("UPDATE clientes SET autorizacion_datos = NULL, medio_autorizacion = NULL WHERE cliente_id = :id")
                .param("id", sinAutorizar).update();

        ClienteEnLista si = servicio.listar("Autorizado", 0, 20).elementos().get(0);
        assertTrue(si.autorizoDatos());
        assertEquals(autorizado, si.id());
        assertFalse(servicio.listar("Sin Autorizar", 0, 20).elementos().get(0).autorizoDatos());
    }

    @Test
    void consultasConstantesCon1Y50Clientes() {
        nuevo(caja, "Lote 000", "6800");
        servicio.listar("Lote", 0, 100);
        long conUno = sentenciasDe(() -> assertEquals(1, servicio.listar("Lote", 0, 100).elementos().size()));

        for (int i = 1; i < 50; i++) {
            Integer id = nuevo(caja, "Lote " + String.format("%03d", i), "68" + String.format("%02d", i));
            if (i % 2 == 0) {
                conSaldo(caja, id, "1000.00");
            }
        }
        long conCincuenta = sentenciasDe(() -> assertEquals(50, servicio.listar("Lote", 0, 100).elementos().size()));
        long conBusquedaPorDocumento = sentenciasDe(() -> servicio.listar("68", 0, 20));

        assertEquals(conUno, conCincuenta, "con uno fueron " + conUno + " consultas y con cincuenta, " + conCincuenta);
        assertEquals(conUno, conBusquedaPorDocumento);
        assertTrue(conCincuenta <= 2, "una página costó " + conCincuenta + " consultas");
    }

    private long sentenciasDe(Runnable operacion) {
        Mockito.clearInvocations(jdbc);
        operacion.run();
        return Mockito.mockingDetails(jdbc).getInvocations().stream()
                .filter(llamada -> llamada.getMethod().getName().equals("sql"))
                .count();
    }
}

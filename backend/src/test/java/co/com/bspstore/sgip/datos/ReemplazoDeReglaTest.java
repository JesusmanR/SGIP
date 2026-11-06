package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.ReglaDeMargen;
import co.com.bspstore.sgip.api.dto.SolicitudReglaMargen;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.logica.RegistroRepetidoException;
import co.com.bspstore.sgip.logica.ServicioPrecios;
import co.com.bspstore.sgip.logica.ServicioProducto;
import co.com.bspstore.sgip.modelo.Categoria;
import co.com.bspstore.sgip.modelo.Producto;
import co.com.bspstore.sgip.modelo.Proveedor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reemplazar una regla de ganancia vigente, de punta a punta contra la base:
 * lo que importa es que o se cierra la vieja y se abre la nueva, o no pasa
 * nada. Un cierre sin la apertura dejaría productos sin precio de venta.
 *
 * Las fechas son fijas y lejanas a propósito: en la CI la JVM corre en UTC y
 * la conexión en America/Bogota, de modo que «hoy» puede diferir un día, y las
 * fechas se escriben y se leen siempre por JDBC.
 *
 * Crea su propia empresa y lo borra todo al terminar.
 */
@Tag("conBase")
@SpringBootTest
class ReemplazoDeReglaTest {

    private static final LocalDate DESDE_VIEJA = LocalDate.of(2020, 1, 1);
    private static final LocalDate DESDE_NUEVA = LocalDate.of(2024, 1, 1);

    @Autowired ServicioPrecios precios;
    @Autowired ServicioProducto productos;
    @Autowired ProductoRepositorio repositorioProductos;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired JdbcClient jdbc;

    private Integer empresaId;
    private Integer categoriaId;
    private Integer proveedorId;
    private String marca;

    @BeforeEach
    void prepararDatosPropios() {
        marca = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        empresaId = plataforma.insertarEmpresa("R" + marca, "Prueba de reemplazo " + marca,
                null, null, null);
        jdbc.sql("""
                INSERT INTO usuarios (empresa_id, rol_id, nombre, correo, clave_hash)
                VALUES (:empresa, (SELECT rol_id FROM roles WHERE nombre = 'ADMINISTRADOR'),
                        'Prueba', :correo, 'no-sirve-para-ingresar')
                """)
                .param("empresa", empresaId)
                .param("correo", "reemplazo-" + marca.toLowerCase() + "@ejemplo.co")
                .update();
        Integer usuarioId = jdbc.sql("SELECT usuario_id FROM usuarios WHERE empresa_id = :empresa")
                .param("empresa", empresaId)
                .query(Integer.class).single();
        categoriaId = categorias.save(new Categoria(empresaId, "Prueba", null)).getId();
        proveedorId = proveedores.save(new Proveedor(empresaId, "Proveedor", "R" + marca)).getId();

        var u = new UsuarioAutenticado(usuarioId, "prueba@ejemplo.co", "ADMINISTRADOR", empresaId);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
    }

    @AfterEach
    void borrarLoQueCreo() {
        SecurityContextHolder.clearContext();
        if (empresaId == null) {
            return;
        }
        for (String tabla : List.of("reglas_margen", "productos", "proveedores", "categorias",
                "usuarios", "empresa_config", "empresas")) {
            jdbc.sql("DELETE FROM " + tabla + " WHERE empresa_id = :empresa")
                    .param("empresa", empresaId)
                    .update();
        }
    }

    // ---------------------------------------------------------------- ayudas

    private Integer reglaGeneral(String forma, String factor, LocalDate desde, LocalDate hasta) {
        jdbc.sql("""
                INSERT INTO reglas_margen (empresa_id, forma_pago, forma_calculo, factor,
                                           vigente_desde, vigente_hasta)
                VALUES (:e, :forma, 'SOBRE_COSTO', :factor, :desde, :hasta)
                """)
                .param("e", empresaId).param("forma", forma)
                .param("factor", new BigDecimal(factor))
                .param("desde", desde).param("hasta", hasta)
                .update();
        return jdbc.sql("SELECT LAST_INSERT_ID()").query(Integer.class).single();
    }

    private SolicitudReglaMargen nueva(String factor, LocalDate desde) {
        return new SolicitudReglaMargen(null, null, "CONTADO", "SOBRE_COSTO",
                new BigDecimal(factor), desde, null);
    }

    /**
     * Llama a {@code ServicioPrecios.reemplazar(reglaId, solicitud)} por su
     * nombre: así esta prueba compila, y falla por la razón correcta, antes
     * de que el método exista.
     */
    @SuppressWarnings("unchecked")
    private Optional<ReglaDeMargen> reemplazar(Integer reglaId, SolicitudReglaMargen s) throws Throwable {
        try {
            var metodo = ServicioPrecios.class.getMethod("reemplazar", Integer.class,
                    SolicitudReglaMargen.class);
            return (Optional<ReglaDeMargen>) metodo.invoke(precios, reglaId, s);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private LocalDate hasta(Integer reglaId) {
        return jdbc.sql("SELECT vigente_hasta FROM reglas_margen WHERE regla_id = :r")
                .param("r", reglaId).query(LocalDate.class).optional().orElse(null);
    }

    private int cuantas() {
        return jdbc.sql("SELECT COUNT(*) FROM reglas_margen WHERE empresa_id = :e")
                .param("e", empresaId).query(Integer.class).single();
    }

    private Integer producto(String costo) {
        String codigo = "P-" + marca;
        Producto p = new Producto(empresaId, categoriaId, proveedorId, codigo, codigo,
                "Producto " + codigo, new BigDecimal(costo));
        p.fijarExistenciaMinima(0);
        return repositorioProductos.save(p).getId();
    }

    // ----------------------------------------------------------------- pruebas

    @Test
    void reemplazarCierraLaViejaElDiaAntesYAbreLaNueva() throws Throwable {
        Integer vieja = reglaGeneral("CONTADO", "1.2000", DESDE_VIEJA, null);

        ReglaDeMargen creada = reemplazar(vieja, nueva("1.5000", DESDE_NUEVA)).orElseThrow();

        assertEquals(DESDE_NUEVA.minusDays(1), hasta(vieja));
        assertEquals(DESDE_NUEVA, creada.vigenteDesde());
        assertNull(creada.vigenteHasta());
        assertEquals(0, new BigDecimal("1.5000").compareTo(creada.factor()));
        assertEquals(2, cuantas());
    }

    @Test
    void siLaCreacionFallaPorUnSolapeLaViejaQuedaIntacta() {
        Integer vieja = reglaGeneral("CONTADO", "1.2000", DESDE_VIEJA, null);
        // Otra regla del mismo alcance que ya ocupa el futuro: la nueva chocaría con ella.
        reglaGeneral("CONTADO", "1.3000", LocalDate.of(2030, 1, 1), null);

        assertThrows(RegistroRepetidoException.class,
                () -> reemplazar(vieja, nueva("1.5000", DESDE_NUEVA)));

        assertNull(hasta(vieja), "la vieja debía seguir sin cierre");
        assertEquals(2, cuantas(), "no debía crearse ninguna regla");
    }

    @Test
    void elCambioDePrecioSeVeEnLaVista() throws Throwable {
        Integer vieja = reglaGeneral("CONTADO", "1.2000", DESDE_VIEJA, null);
        reglaGeneral("CREDITO", "1.2000", DESDE_VIEJA, null);
        Integer id = producto("1000");
        assertEquals(0, new BigDecimal("1200").compareTo(productos.buscar(id).orElseThrow().precioContado()));

        reemplazar(vieja, nueva("1.5000", DESDE_NUEVA));

        assertEquals(0, new BigDecimal("1500").compareTo(productos.buscar(id).orElseThrow().precioContado()));
        assertEquals(0, new BigDecimal("1200").compareTo(productos.buscar(id).orElseThrow().precioCredito()));
    }

    @Test
    void unaReglaAjenaOInexistenteNoSeReemplaza() throws Throwable {
        assertTrue(reemplazar(987654321, nueva("1.5000", DESDE_NUEVA)).isEmpty());
        assertEquals(0, cuantas());
    }
}

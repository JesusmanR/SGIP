package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.SolicitudPrecio;
import co.com.bspstore.sgip.logica.DatoTributarioInvalidoException;
import co.com.bspstore.sgip.logica.ServicioConfiguracionTributaria;
import co.com.bspstore.sgip.logica.ServicioPrecios;
import co.com.bspstore.sgip.modelo.Producto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Una sola fuente de «hoy» (M5): el día lo pone la aplicación, con su reloj en
 * America/Bogota, y no el {@code CURDATE()} del motor.
 *
 * Las pruebas no dependen de que la base y la aplicación estén en días
 * distintos por la hora a la que corran: el reloj se fija en un año que no es
 * el de la base (2020 y 2040), de modo que el día de la aplicación y el del
 * motor difieren siempre. Los servicios se construyen a mano con ese reloj y los
 * repositorios de verdad, y las fechas se escriben y se leen por JDBC como
 * {@code LocalDate}, el mismo camino que la aplicación.
 *
 * Crea su propia empresa y lo borra todo al terminar.
 */
@Tag("conBase")
@SpringBootTest
class HoyDeLaAplicacionConBaseTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    @Autowired RepositorioPrecios repositorioPrecios;
    @Autowired RepositorioConfiguracionTributaria repositorioTributario;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProductoRepositorio repositorioProductos;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired JdbcClient jdbc;

    private EmpresaDePrueba empresa;
    private int consecutivo;

    @BeforeEach
    void prepararDatosPropios() {
        empresa = EmpresaDePrueba.crear("Hoy de la aplicacion", plataforma, jdbc, categorias, proveedores);
        empresa.entrar();
    }

    @AfterEach
    void borrarLoQueCreo() {
        if (empresa != null) {
            empresa.borrar(jdbc);
        }
    }

    private static Clock elDia(LocalDate dia) {
        return Clock.fixed(dia.atTime(10, 0).atZone(BOGOTA).toInstant(), BOGOTA);
    }

    private Integer productoSinReglas() {
        consecutivo++;
        String codigo = "D" + consecutivo + "-" + empresa.marca;
        Producto p = new Producto(empresa.empresaId, empresa.categoriaId, empresa.proveedorId,
                codigo, codigo, "Producto " + codigo, new BigDecimal("1000.00"));
        p.fijarExistenciaMinima(0);
        return repositorioProductos.save(p).getId();
    }

    private record ReglaPropia(String formaPago, BigDecimal precioFijo, LocalDate desde, LocalDate hasta) {}

    private List<ReglaPropia> reglasPropias(Integer id) {
        return jdbc.sql("""
                SELECT forma_pago, precio_fijo, vigente_desde AS desde, vigente_hasta AS hasta
                  FROM reglas_margen
                 WHERE empresa_id = :e AND producto_id = :p
                 ORDER BY forma_pago, vigente_desde
                """).param("e", empresa.empresaId).param("p", id).query(ReglaPropia.class).list();
    }

    // ------------------------------------------------- reglas de IVA

    /**
     * Con la base por delante de la aplicación, una regla que la aplicación cree por
     * empezar pero que la base ya tiene vigente no se borra: rige y explica ventas.
     * El DELETE lleva la doble guarda (el {@code hoy} de la aplicación y el
     * {@code CURDATE()} del motor), y el servicio dice por qué no pudo (ADR-0030).
     */
    @Test
    void unaReglaDeIvaQueLaBaseYaTieneVigenteNoSeRetiraAunqueLaAplicacionCreaQueNoEmpieza() {
        Integer iva19 = jdbc.sql("SELECT tipo_impuesto_id FROM tipos_impuesto WHERE codigo = 'IVA_19'")
                .query(Integer.class).single();
        Integer regla = repositorioTributario.insertarRegla(empresa.empresaId, empresa.categoriaId,
                null, iva19, LocalDate.of(2025, 1, 1), null, null);
        var servicio = new ServicioConfiguracionTributaria(repositorioTributario, categorias,
                repositorioProductos, elDia(LocalDate.of(2020, 1, 1)));

        assertThrows(DatoTributarioInvalidoException.class, () -> servicio.cerrarRegla(regla, null));

        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM reglas_iva WHERE empresa_id = :e")
                .param("e", empresa.empresaId).query(Integer.class).single(),
                "la regla vigente para la base sigue ahí");
    }

    @Test
    void laReglaDeIvaVigenteSeMarcaConElDiaDeLaAplicacion() {
        Integer iva19 = jdbc.sql("SELECT tipo_impuesto_id FROM tipos_impuesto WHERE codigo = 'IVA_19'")
                .query(Integer.class).single();
        Integer regla = repositorioTributario.insertarRegla(empresa.empresaId, empresa.categoriaId,
                null, iva19, LocalDate.of(2040, 1, 1), LocalDate.of(2040, 12, 31), null);
        var servicio = new ServicioConfiguracionTributaria(repositorioTributario, categorias,
                repositorioProductos, elDia(LocalDate.of(2040, 6, 15)));

        assertTrue(servicio.reglas().stream().filter(r -> r.reglaId().equals(regla))
                .findFirst().orElseThrow().vigente(), "el 15 de junio de 2040 rige una regla de todo 2040");
    }

    // ------------------------------------------------- reglas de ganancia

    @Test
    void laReglaDeGananciaVigenteSeMarcaConElDiaDeLaAplicacion() {
        Integer regla = repositorioPrecios.insertar(empresa.empresaId, empresa.categoriaId, null,
                "CONTADO", "SOBRE_COSTO", new BigDecimal("1.2000"),
                LocalDate.of(2040, 1, 1), LocalDate.of(2040, 12, 31));
        var servicio = new ServicioPrecios(repositorioPrecios, elDia(LocalDate.of(2040, 6, 15)));

        assertTrue(servicio.buscar(regla).orElseThrow().vigente(),
                "el 15 de junio de 2040 rige una regla de todo 2040");
    }

    @Test
    void laReglaPropiaSeFechaConElDiaDeLaAplicacionYElCambioSiguienteLaCierraElDiaAntes() {
        Integer id = productoSinReglas();
        SolicitudPrecio mil = new SolicitudPrecio(null, new BigDecimal("1250"));
        SolicitudPrecio otro = new SolicitudPrecio(null, new BigDecimal("1300"));

        new ServicioPrecios(repositorioPrecios, elDia(LocalDate.of(2040, 6, 15)))
                .escribirPrecios(empresa.empresaId, id, new BigDecimal("1000.00"), mil, null, false);
        new ServicioPrecios(repositorioPrecios, elDia(LocalDate.of(2040, 6, 16)))
                .escribirPrecios(empresa.empresaId, id, new BigDecimal("1000.00"), otro, null, false);

        List<ReglaPropia> reglas = reglasPropias(id);
        assertEquals(2, reglas.size());
        assertEquals(LocalDate.of(2040, 6, 15), reglas.get(0).desde());
        assertEquals(LocalDate.of(2040, 6, 15), reglas.get(0).hasta(),
                "la primera termina el día anterior al que empieza la segunda");
        assertEquals(LocalDate.of(2040, 6, 16), reglas.get(1).desde());
        assertNull(reglas.get(1).hasta());
    }

    @Test
    void elAlcanceNoCuentaLosProductosConReglaPropiaVigenteSegunElDiaDeLaAplicacion() {
        Integer conReglas = productoSinReglas();
        productoSinReglas();
        for (String forma : List.of("CONTADO", "CREDITO")) {
            jdbc.sql("""
                    INSERT INTO reglas_margen (empresa_id, producto_id, forma_pago, forma_calculo,
                                               precio_fijo, vigente_desde)
                    VALUES (:e, :p, :forma, 'SOBRE_COSTO', 1250, :desde)
                    """)
                    .param("e", empresa.empresaId).param("p", conReglas).param("forma", forma)
                    .param("desde", LocalDate.of(2040, 6, 1)).update();
        }
        var servicio = new ServicioPrecios(repositorioPrecios, elDia(LocalDate.of(2040, 6, 15)));

        assertEquals(1, servicio.alcance(null, null),
                "el que ya tiene precio propio en las dos formas de pago no lo cambiaría la regla");
    }
}

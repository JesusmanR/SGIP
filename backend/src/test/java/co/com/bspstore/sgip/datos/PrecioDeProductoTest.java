package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.CruceDeUmbral;
import co.com.bspstore.sgip.api.dto.FilaDeCarga;
import co.com.bspstore.sgip.api.dto.ProductoEnCatalogo;
import co.com.bspstore.sgip.api.dto.SolicitudEdicionProducto;
import co.com.bspstore.sgip.api.dto.SolicitudPrecio;
import co.com.bspstore.sgip.api.dto.SolicitudProducto;
import co.com.bspstore.sgip.api.dto.SolicitudReglaMargen;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * El porcentaje y el precio de un producto, de punta a punta contra la base
 * (ADR-0025): lo que un repositorio simulado no puede demostrar, porque lo que
 * se prueba es el SQL y lo que la vista contesta después.
 *
 * Crea su propia empresa y lo borra todo al terminar, de modo que no depende de
 * los datos de demostración ni del orden de las pruebas.
 */
@Tag("conBase")
@SpringBootTest
class PrecioDeProductoTest {

    private static final String UVT_DE_PRUEBA = "PRECIO-PRODUCTO UVT de prueba";

    @Autowired ServicioProducto productos;
    @Autowired ServicioPrecios precios;
    @Autowired ProductoRepositorio repositorioProductos;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioImportacion importacion;
    @Autowired RepositorioInventario inventario;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transacciones;

    private Integer empresaId;
    private Integer usuarioId;
    private Integer categoriaId;
    private Integer proveedorId;
    private String marca;
    private int consecutivo;

    @BeforeEach
    void prepararDatosPropios() {
        marca = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        empresaId = plataforma.insertarEmpresa("T" + marca, "Prueba de precios " + marca,
                null, null, null);
        jdbc.sql("""
                INSERT INTO usuarios (empresa_id, rol_id, nombre, correo, clave_hash)
                VALUES (:empresa, (SELECT rol_id FROM roles WHERE nombre = 'ADMINISTRADOR'),
                        'Prueba', :correo, 'no-sirve-para-ingresar')
                """)
                .param("empresa", empresaId)
                .param("correo", "precio-" + marca.toLowerCase() + "@ejemplo.co")
                .update();
        usuarioId = jdbc.sql("SELECT usuario_id FROM usuarios WHERE empresa_id = :empresa")
                .param("empresa", empresaId)
                .query(Integer.class).single();
        categoriaId = categorias.save(new Categoria(empresaId, "Prueba", null)).getId();
        proveedorId = proveedores.save(new Proveedor(empresaId, "Proveedor", "P" + marca)).getId();

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
        for (String tabla : List.of("detalle_carga_precios", "cargas_precios", "historico_costos",
                "movimientos_inventario", "reglas_iva", "reglas_margen", "productos",
                "proveedores", "categorias", "usuarios", "empresa_config", "empresas")) {
            jdbc.sql("DELETE FROM " + tabla + " WHERE empresa_id = :empresa")
                    .param("empresa", empresaId)
                    .update();
        }
        jdbc.sql("DELETE FROM uvt_anual WHERE resolucion = :r").param("r", UVT_DE_PRUEBA).update();
    }

    // ---------------------------------------------------------------- ayudas

    private static SolicitudPrecio porcentaje(String v) {
        return new SolicitudPrecio(new BigDecimal(v), null);
    }

    private static SolicitudPrecio precio(String v) {
        return new SolicitudPrecio(null, new BigDecimal(v));
    }

    private String codigoNuevo() {
        consecutivo++;
        return "P" + consecutivo + "-" + marca;
    }

    private Integer alta(String costo, SolicitudPrecio contado, SolicitudPrecio credito, int existencia) {
        String codigo = codigoNuevo();
        return productos.crear(new SolicitudProducto(categoriaId, proveedorId, codigo, codigo, null,
                "Producto " + codigo, null, null, new BigDecimal(costo), existencia, 0,
                contado, credito)).getId();
    }

    /** Un producto que no tiene regla propia: lo que ya había antes del ADR-0025. */
    private Integer sinReglaPropia(String costo) {
        String codigo = codigoNuevo();
        Producto p = new Producto(empresaId, categoriaId, proveedorId, codigo, codigo,
                "Producto " + codigo, new BigDecimal(costo));
        p.fijarExistenciaMinima(0);
        return repositorioProductos.save(p).getId();
    }

    private void editar(Integer id, String costo, SolicitudPrecio contado, SolicitudPrecio credito,
                        Boolean creditoIgual) {
        ProductoEnCatalogo p = leer(id);
        productos.actualizar(id, new SolicitudEdicionProducto(categoriaId, proveedorId, p.codigo(),
                p.sku(), null, p.nombre(), null, null, new BigDecimal(costo), 0,
                contado, credito, creditoIgual, p.version()));
    }

    private ProductoEnCatalogo leer(Integer id) {
        return productos.buscar(id).orElseThrow();
    }

    private void reglasGenerales(String contado, String credito) {
        jdbc.sql("""
                INSERT INTO reglas_margen (empresa_id, forma_pago, forma_calculo, factor, vigente_desde)
                VALUES (:e, 'CONTADO', 'SOBRE_COSTO', :contado, '2026-01-01'),
                       (:e, 'CREDITO', 'SOBRE_COSTO', :credito, '2026-01-01')
                """)
                .param("e", empresaId)
                .param("contado", new BigDecimal(contado))
                .param("credito", new BigDecimal(credito))
                .update();
    }

    private int reglasPropias(Integer id, String forma) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM reglas_margen
                 WHERE empresa_id = :e AND producto_id = :p AND forma_pago = :f
                """)
                .param("e", empresaId).param("p", id).param("f", forma)
                .query(Integer.class).single();
    }

    /** Como si la regla propia hubiera empezado antes: las fechas no se pueden simular de otra forma. */
    private void laReglaEmpezoHaceDias(Integer id, String forma, int dias) {
        jdbc.sql("""
                UPDATE reglas_margen SET vigente_desde = CURDATE() - INTERVAL :d DAY
                 WHERE empresa_id = :e AND producto_id = :p AND forma_pago = :f
                """)
                .param("d", dias).param("e", empresaId).param("p", id).param("f", forma)
                .update();
    }

    private static void igualA(String esperado, BigDecimal obtenido) {
        assertEquals(0, new BigDecimal(esperado).compareTo(obtenido),
                "esperaba " + esperado + " y dio " + obtenido);
    }

    /** Empresa responsable que aplica el umbral, con la UVT del año en curso. Devuelve el umbral en pesos. */
    private BigDecimal umbralDe(Integer... productosDeUmbral) {
        jdbc.sql("""
                INSERT INTO uvt_anual (anio, valor, resolucion)
                SELECT YEAR(CURDATE()), 52374.00, :r
                 WHERE NOT EXISTS (SELECT 1 FROM uvt_anual WHERE anio = YEAR(CURDATE()))
                """).param("r", UVT_DE_PRUEBA).update();
        jdbc.sql("""
                INSERT INTO empresa_config (empresa_id, responsable_iva, aplica_exclusion_umbral)
                VALUES (:e, TRUE, TRUE)
                """).param("e", empresaId).update();
        for (Integer id : productosDeUmbral) {
            jdbc.sql("""
                    INSERT INTO reglas_iva (empresa_id, producto_id, tipo_impuesto_id, vigente_desde)
                    SELECT :e, :p, tipo_impuesto_id, '2026-01-01'
                      FROM tipos_impuesto WHERE codigo = 'EXCL_UMBRAL_PC'
                    """).param("e", empresaId).param("p", id).update();
        }
        return jdbc.sql("""
                SELECT umbral_pesos FROM v_precios_producto
                 WHERE empresa_id = :e AND producto_id = :p
                """)
                .param("e", empresaId).param("p", productosDeUmbral[0])
                .query(BigDecimal.class).single();
    }

    // ----------------------------------------------------------------- alta

    @Test
    void elAltaConPorcentajeDaElPrecioYEscribeElMovimientoInicial() {
        Integer id = alta("1000.00", porcentaje("20"), null, 5);

        ProductoEnCatalogo p = leer(id);
        igualA("1200", p.precioContado());
        igualA("1200", p.precioCredito());
        igualA("20.00", p.margenContado());
        igualA("20.00", p.porcentajeContado());
        assertNull(p.precioFijoContado());
        assertEquals(5, p.existencia());

        var kardex = inventario.kardex(empresaId, id, 10);
        assertEquals(1, kardex.size());
        assertEquals("INGRESO", kardex.get(0).tipo());
    }

    @Test
    void elAltaConPrecioLoExponeComoFijoSinBase() {
        Integer id = alta("1000.00", precio("1250"), null, 0);

        ProductoEnCatalogo p = leer(id);
        igualA("1250", p.precioContado());
        igualA("1250", p.precioFijoContado());
        igualA("25.00", p.margenContado());
        assertNull(p.porcentajeContado());
        assertNull(p.baseContado());
    }

    @Test
    void reabrirLaFichaDevuelveElPorcentajeEscritoAunqueElMargenRealSeaOtro() {
        Integer id = alta("7.00", porcentaje("20"), null, 0);

        ProductoEnCatalogo p = leer(id);
        igualA("8", p.precioContado());
        igualA("14.29", p.margenContado());
        igualA("20.00", p.porcentajeContado());
    }

    // ---------------------------------------- qué se conserva si sube el costo

    @Test
    void siSeEscribioElPorcentajeYSubeElCostoCambiaElPrecio() {
        Integer id = alta("1000.00", porcentaje("20"), null, 0);

        editar(id, "1500.00", null, null, null);

        igualA("1800", leer(id).precioContado());
        igualA("20.00", leer(id).margenContado());
        assertEquals(1, reglasPropias(id, "CONTADO"), "cambiar el costo no escribe ninguna regla");
    }

    @Test
    void siSeEscribioElPrecioYSubeElCostoCambiaElMargen() {
        Integer id = alta("1000.00", precio("1250"), null, 0);

        editar(id, "1100.00", null, null, null);

        igualA("1250", leer(id).precioContado());
        igualA("13.64", leer(id).margenContado());
        assertEquals(1, reglasPropias(id, "CONTADO"));
    }

    // ----------------------------------------------------------------- historia

    @Test
    void cambiarElPrecioCierraAyerLaReglaQueVeniaYAbreOtraDesdeHoy() {
        Integer id = alta("1000.00", precio("1250"), null, 0);
        laReglaEmpezoHaceDias(id, "CONTADO", 30);

        editar(id, "1000.00", precio("1300"), null, false);

        assertEquals(2, reglasPropias(id, "CONTADO"));
        assertEquals(1, jdbc.sql("""
                SELECT COUNT(*) FROM reglas_margen
                 WHERE empresa_id = :e AND producto_id = :p AND forma_pago = 'CONTADO'
                   AND precio_fijo = 1250 AND vigente_hasta = CURDATE() - INTERVAL 1 DAY
                """).param("e", empresaId).param("p", id).query(Integer.class).single());
        igualA("1300", leer(id).precioContado());
    }

    @Test
    void unaReglaQueEmpezoAyerSeCierraElMismoDiaQueEmpezo() {
        Integer id = alta("1000.00", precio("1250"), null, 0);
        laReglaEmpezoHaceDias(id, "CONTADO", 1);

        // ck_reglas_margen_rango admite vigente_hasta = vigente_desde.
        editar(id, "1000.00", precio("1300"), null, false);

        assertEquals(2, reglasPropias(id, "CONTADO"));
        igualA("1300", leer(id).precioContado());
    }

    @Test
    void dosCambiosElMismoDiaCorrigenLaMismaFila() {
        Integer id = alta("1000.00", precio("1250"), null, 0);
        laReglaEmpezoHaceDias(id, "CONTADO", 30);

        editar(id, "1000.00", precio("1300"), null, false);
        editar(id, "1000.00", porcentaje("40"), null, false);

        assertEquals(2, reglasPropias(id, "CONTADO"));
        ProductoEnCatalogo p = leer(id);
        igualA("1400", p.precioContado());
        igualA("40.00", p.porcentajeContado());
        assertNull(p.precioFijoContado());
    }

    @Test
    void cambiarUnaReglaQueEmpezoHoyNoIntentaCerrarlaAyer() {
        Integer id = alta("1000.00", precio("1250"), null, 0);

        editar(id, "1000.00", precio("1300"), null, false);

        assertEquals(1, reglasPropias(id, "CONTADO"));
        igualA("1300", leer(id).precioContado());
    }

    @Test
    void volverAEscribirLoMismoNoDejaOtraFila() {
        Integer id = alta("1000.00", porcentaje("20"), null, 0);
        laReglaEmpezoHaceDias(id, "CONTADO", 30);

        editar(id, "1000.00", porcentaje("20.00"), null, false);

        assertEquals(1, reglasPropias(id, "CONTADO"));
    }

    // ---------------------------------------------- quien sigue una regla mayor

    @Test
    void corregirElNombreNoSacaAlProductoDeLaReglaGeneral() {
        reglasGenerales("1.2000", "1.2500");
        Integer id = sinReglaPropia("1000.00");

        editar(id, "1000.00", null, null, null);

        assertEquals(0, reglasPropias(id, "CONTADO"));
        assertEquals(0, reglasPropias(id, "CREDITO"));
        igualA("1200", leer(id).precioContado());
        igualA("20.00", leer(id).porcentajeContado());
    }

    @Test
    void sinNingunaReglaElPrecioEsNuloYEditarLoDejaAsi() {
        Integer id = sinReglaPropia("1000.00");

        editar(id, "1200.00", null, null, null);

        ProductoEnCatalogo p = leer(id);
        assertNull(p.precioContado());
        assertNull(p.precioCredito());
        assertNull(p.margenContado());
    }

    @Test
    void unaReglaPropiaNoLeCambiaElPrecioAOtroProducto() {
        reglasGenerales("1.2000", "1.2500");
        Integer vecino = sinReglaPropia("1000.00");

        alta("1000.00", precio("5000"), null, 0);

        igualA("1200", leer(vecino).precioContado());
    }

    @Test
    void unaReglaPropiaVencidaDevuelveElProductoALaGeneral() {
        reglasGenerales("1.2000", "1.2500");
        Integer id = alta("1000.00", precio("5000"), null, 0);
        jdbc.sql("""
                UPDATE reglas_margen
                   SET vigente_desde = CURDATE() - INTERVAL 5 DAY,
                       vigente_hasta = CURDATE() - INTERVAL 1 DAY
                 WHERE empresa_id = :e AND producto_id = :p
                """).param("e", empresaId).param("p", id).update();

        igualA("1200", leer(id).precioContado());
        igualA("1250", leer(id).precioCredito());
    }

    // ----------------------------------------------------------------- crédito

    @Test
    void elCreditoQueEraIgualSigueAlContado() {
        Integer id = alta("1000.00", precio("1200"), null, 0);

        editar(id, "1000.00", precio("1300"), null, null);

        igualA("1300", leer(id).precioContado());
        igualA("1300", leer(id).precioCredito());
    }

    @Test
    void elCreditoEscritoNoSigueAlContadoHastaQueSeLeDiceQueVuelva() {
        Integer id = alta("1000.00", precio("1200"), precio("1500"), 0);

        editar(id, "1000.00", precio("1300"), null, false);
        igualA("1300", leer(id).precioContado());
        igualA("1500", leer(id).precioCredito());

        editar(id, "1000.00", null, null, true);
        igualA("1300", leer(id).precioContado());
        igualA("1300", leer(id).precioCredito());
    }

    @Test
    void igualarElCreditoAUnContadoHeredadoCopiaLaFormaDeLaRegla() {
        jdbc.sql("""
                INSERT INTO reglas_margen (empresa_id, forma_pago, forma_calculo, factor, vigente_desde)
                VALUES (:e, 'CONTADO', 'SOBRE_PRECIO', 1.2000, '2026-01-01'),
                       (:e, 'CREDITO', 'SOBRE_COSTO', 1.9000, '2026-01-01')
                """).param("e", empresaId).update();
        Integer id = sinReglaPropia("1000.00");
        igualA("1250", leer(id).precioContado());
        igualA("1900", leer(id).precioCredito());

        editar(id, "1000.00", null, null, true);

        igualA("1250", leer(id).precioCredito());
        assertEquals(0, reglasPropias(id, "CONTADO"), "el contado sigue en la regla general");
    }

    // ------------------------------------------- dos personas a la vez

    /**
     * Quien iguala el crédito espera a quien tiene el producto, y copia lo que ese
     * dejó, no lo que veía al empezar.
     *
     * Son dos hilos pero no hay carrera: el que tiene el producto no suelta hasta
     * que la base dice que el otro está esperando, de modo que el orden es siempre
     * este. Sin el `FOR UPDATE` el otro no llega a esperar y la prueba lo dice; con
     * el contado leído de la vista, copia 1.200, que es la foto de antes.
     */
    @Test
    void igualarElCreditoEsperaAlQueTieneElProductoYCopiaLoQueEseDejo() throws Exception {
        Integer id = alta("1000.00", porcentaje("20"), precio("1500"), 0);
        SecurityContext contexto = SecurityContextHolder.getContext();
        ExecutorService otroHilo = Executors.newSingleThreadExecutor();
        try {
            Future<?> quienIguala = new TransactionTemplate(transacciones).execute(estado -> {
                jdbc.sql("""
                        SELECT producto_id FROM productos
                         WHERE empresa_id = :e AND producto_id = :p FOR UPDATE
                        """).param("e", empresaId).param("p", id).query(Integer.class).single();

                Future<?> enEspera = otroHilo.submit(() -> {
                    SecurityContextHolder.setContext(contexto);
                    editar(id, "1000.00", null, null, true);
                });
                esperarAQueSeQuedeBloqueado(enEspera);

                jdbc.sql("""
                        UPDATE reglas_margen SET factor = 1.3000
                         WHERE empresa_id = :e AND producto_id = :p AND forma_pago = 'CONTADO'
                        """).param("e", empresaId).param("p", id).update();
                return enEspera;
            });
            quienIguala.get(60, TimeUnit.SECONDS);
        } finally {
            otroHilo.shutdownNow();
        }
        igualA("1300", leer(id).precioCredito());
    }

    private void esperarAQueSeQuedeBloqueado(Future<?> enEspera) {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < limite) {
            if (enEspera.isDone()) {
                fail("Terminó sin esperar al que tiene el producto: " + comoTermino(enEspera));
            }
            int esperando = jdbc.sql(
                            "SELECT COUNT(*) FROM performance_schema.data_lock_waits")
                    .query(Integer.class).single();
            if (esperando > 0) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        fail("Nadie se quedó esperando el producto: el FOR UPDATE no está poniendo en fila.");
    }

    private static String comoTermino(Future<?> f) {
        try {
            f.get();
            return "sin error";
        } catch (Exception e) {
            return e.toString();
        }
    }

    // ------------------------------------------- las reglas de ámbito no se enteran

    @Test
    void laSimulacionYElAlcanceDeUnaReglaGeneralNoCuentanLosQueTienenReglaPropia() {
        reglasGenerales("1.0500", "1.0500");
        Integer general = sinReglaPropia("1000.00");
        Integer propio = alta("1000.00", porcentaje("5"), null, 0);
        Integer soloContado = sinReglaPropia("1000.00");
        editar(soloContado, "1000.00", porcentaje("5"), null, false);
        BigDecimal umbral = umbralDe(general, propio, soloContado);

        // Al 5 % los tres quedan por debajo del umbral, y al 20 % quedarían encima.
        String costo = umbral.divide(new BigDecimal("1.10"), 2, RoundingMode.HALF_UP).toPlainString();
        for (Integer id : List.of(general, propio, soloContado)) {
            jdbc.sql("UPDATE productos SET costo = :c WHERE empresa_id = :e AND producto_id = :p")
                    .param("c", new BigDecimal(costo)).param("e", empresaId).param("p", id).update();
        }

        // Una regla propia vencida o que aún no empieza no decide hoy: la general sí
        // los alcanza, en las dos formas de pago. Sin esto, la prueba pasaría aunque
        // el conteo ignorara las fechas.
        Integer conReglaVencida = alta("1000.00", porcentaje("5"), null, 0);
        Integer conReglaFutura = alta("1000.00", porcentaje("5"), null, 0);
        jdbc.sql("""
                UPDATE reglas_margen
                   SET vigente_desde = CURDATE() - INTERVAL 5 DAY,
                       vigente_hasta = CURDATE() - INTERVAL 1 DAY
                 WHERE empresa_id = :e AND producto_id = :p
                """).param("e", empresaId).param("p", conReglaVencida).update();
        jdbc.sql("""
                UPDATE reglas_margen
                   SET vigente_desde = CURDATE() + INTERVAL 3 DAY
                 WHERE empresa_id = :e AND producto_id = :p
                """).param("e", empresaId).param("p", conReglaFutura).update();
        assertEquals(2, reglasPropias(conReglaVencida, "CONTADO") + reglasPropias(conReglaVencida, "CREDITO"),
                "el producto vencido tiene su regla propia en las dos formas");
        assertEquals(2, reglasPropias(conReglaFutura, "CONTADO") + reglasPropias(conReglaFutura, "CREDITO"),
                "el producto con regla futura la tiene en las dos formas");

        assertEquals(4, precios.alcance(null, null),
                "el que tiene regla propia vigente en las dos formas no cuenta; el de una sola, "
                        + "el de regla vencida y el de regla futura, sí");

        List<CruceDeUmbral> contado = precios.simular(new SolicitudReglaMargen(null, null, "CONTADO",
                "SOBRE_COSTO", new BigDecimal("1.2000"), LocalDate.now(), null));
        assertEquals(List.of(general), contado.stream().map(CruceDeUmbral::productoId).toList());

        List<CruceDeUmbral> credito = precios.simular(new SolicitudReglaMargen(null, null, "CREDITO",
                "SOBRE_COSTO", new BigDecimal("1.2000"), LocalDate.now(), null));
        assertEquals(List.of(general, soloContado).stream().sorted().toList(),
                credito.stream().map(CruceDeUmbral::productoId).sorted().toList());
    }

    @Test
    void unaReglaPropiaNoPasaPorReglaGeneralEnLaPantallaDeReglas() {
        Integer id = alta("1000.00", precio("1250"), null, 0);
        assertEquals(1, reglasPropias(id, "CONTADO"));

        assertTrue(precios.listar().isEmpty(), "las reglas de producto no se listan aquí");

        // Tampoco chocan: categoría y proveedor nulos parecen una regla general.
        var general = precios.crear(new SolicitudReglaMargen(null, null, "CONTADO",
                "SOBRE_COSTO", new BigDecimal("1.2000"), LocalDate.now(), null));
        assertEquals(1, precios.listar().size());
        assertEquals(general.reglaId(), precios.listar().get(0).reglaId());
    }

    // ----------------------------------------------------------------- importación

    @Test
    void aplicarUnaListaConservaElPrecioFijoYRecalculaElPorcentaje() {
        Integer fijo = alta("1000.00", precio("1250"), null, 0);
        Integer conPorcentaje = alta("1000.00", porcentaje("20"), null, 0);
        BigDecimal umbral = umbralDe(fijo, conPorcentaje);
        // Con este costo, el 20 % pasa del umbral. El precio fijo no se mueve.
        BigDecimal costoNuevo = umbral.setScale(2, RoundingMode.HALF_UP);

        Long carga = importacion.insertarCarga(empresaId, proveedorId, usuarioId, "lista.csv", 2, 2, 0);
        importacion.insertarDetalles(empresaId, carga, List.of(
                new FilaDeCarga(2, fijo, "A", null, costoNuevo, new BigDecimal("1000.00"), true, null, null),
                new FilaDeCarga(3, conPorcentaje, "B", null, costoNuevo, new BigDecimal("1000.00"), true, null, null)));

        // La vista previa dice la verdad: solo cruza el que cambia de precio.
        List<CruceDeUmbral> cruces = importacion.cruzarianAlAplicar(empresaId, carga);
        assertEquals(List.of(conPorcentaje), cruces.stream().map(CruceDeUmbral::productoId).toList());
        assertEquals(1200, cruces.get(0).precioAntes());
        assertEquals(alVeintePorCiento(costoNuevo), cruces.get(0).precioDespues());
        assertFalse(cruces.stream().anyMatch(c -> c.precioDespues() == null));

        importacion.aplicarCostos(empresaId, carga);

        ProductoEnCatalogo despuesFijo = leer(fijo);
        igualA("1250", despuesFijo.precioContado());
        assertTrue(despuesFijo.margenContado().signum() < 0,
                "el costo pasó el precio: el margen tiene que salir negativo");

        ProductoEnCatalogo despuesPorcentaje = leer(conPorcentaje);
        assertEquals(alVeintePorCiento(costoNuevo),
                despuesPorcentaje.precioContado().intValueExact());
        igualA("20.00", despuesPorcentaje.porcentajeContado());
    }

    private static int alVeintePorCiento(BigDecimal costo) {
        return costo.multiply(new BigDecimal("1.2")).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }
}

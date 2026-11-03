package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.FilaDeCarga;
import co.com.bspstore.sgip.api.dto.SolicitudProducto;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;

/**
 * Qué pasa cuando alguien edita un producto mientras otra operación lo
 * cambia por debajo (arreglo 3 de la puesta a punto).
 *
 * Las dos transacciones se entrelazan en un solo hilo y sin esperas:
 * la «A» es la edición, que lee el producto y tarda; en mitad de ella se
 * ejecuta, en una transacción propia ya confirmada, lo que hace la otra
 * operación; después la «A» guarda. Es el mismo orden de sucesos que en
 * producción, pero sin depender de que el reloj los ponga así.
 *
 * Necesita la base con el esquema aplicado: se excluye sin ella con
 * {@code -DexcludedGroups=conBase}. Crea su propia empresa y lo borra
 * todo al terminar, de modo que no depende de los datos de demostración
 * ni del orden de las pruebas.
 */
@Tag("conBase")
@SpringBootTest
class ProductoConcurrenciaTest {

    @Autowired ProductoRepositorio productos;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    // Espía y no simulacro: las demás pruebas de esta clase siguen usando
    // el repositorio real; solo una le ordena fallar al escribir el movimiento.
    @MockitoSpyBean RepositorioInventario inventario;
    @Autowired ServicioProducto servicioProducto;
    @Autowired RepositorioImportacion importacion;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager gestor;

    private TransactionTemplate transaccion;
    private TransactionTemplate transaccionAparte;

    private Integer empresaId;
    private Integer proveedorId;
    private Integer usuarioId;
    private Integer productoId;
    private Integer categoriaId;
    private String marca;

    @BeforeEach
    void prepararDatosPropios() {
        transaccion = new TransactionTemplate(gestor);
        transaccionAparte = new TransactionTemplate(gestor);
        transaccionAparte.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        marca = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();

        empresaId = plataforma.insertarEmpresa("T" + marca, "Prueba concurrencia " + marca,
                null, null, null);

        jdbc.sql("""
                INSERT INTO usuarios (empresa_id, rol_id, nombre, correo, clave_hash)
                VALUES (:empresa, (SELECT rol_id FROM roles WHERE nombre = 'ADMINISTRADOR'),
                        'Prueba', :correo, 'no-sirve-para-ingresar')
                """)
                .param("empresa", empresaId)
                .param("correo", "prueba-" + marca.toLowerCase() + "@ejemplo.co")
                .update();
        usuarioId = jdbc.sql("SELECT usuario_id FROM usuarios WHERE empresa_id = :empresa")
                .param("empresa", empresaId)
                .query(Integer.class).single();

        categoriaId = categorias.save(new Categoria(empresaId, "Prueba", null)).getId();
        proveedorId = proveedores.save(new Proveedor(empresaId, "Proveedor de prueba", "P" + marca)).getId();

        Producto p = new Producto(empresaId, categoriaId, proveedorId,
                "CONC-" + marca, "CONC-" + marca, "Producto original", new BigDecimal("100.00"));
        p.fijarExistencias(10, 5);
        productoId = productos.save(p).getId();
    }

    @AfterEach
    void borrarLoQueCreo() {
        SecurityContextHolder.clearContext();
        if (empresaId == null) {
            return;
        }
        // De las hojas hacia la raíz: las claves compuestas por empresa
        // impiden borrar un padre con hijos.
        for (String tabla : List.of("detalle_carga_precios", "cargas_precios",
                "movimientos_inventario", "productos",
                "proveedores", "categorias", "usuarios", "empresas")) {
            jdbc.sql("DELETE FROM " + tabla + " WHERE empresa_id = :empresa")
                    .param("empresa", empresaId)
                    .update();
        }
    }

    private int existenciaEnBase() {
        return jdbc.sql("SELECT existencia FROM productos WHERE empresa_id = :empresa AND producto_id = :producto")
                .param("empresa", empresaId)
                .param("producto", productoId)
                .query(Integer.class).single();
    }

    private BigDecimal costoEnBase() {
        return jdbc.sql("SELECT costo FROM productos WHERE empresa_id = :empresa AND producto_id = :producto")
                .param("empresa", empresaId)
                .param("producto", productoId)
                .query(BigDecimal.class).single();
    }

    /** Lo que hace la pantalla de edición: cambia el nombre y deja lo demás como estaba. */
    private static void editarNombre(Producto p, String nombre) {
        p.reemplazarDatos(p.getCategoriaId(), p.getProveedorId(), p.getCodigo(), p.getSku(),
                nombre, p.getPresentacion(), p.getDescripcion(), p.getCodigoBarras(),
                p.getCosto(), p.getExistenciaMinima());
    }

    /** Un fallo simulado, para reconocer que la excepción vino del movimiento y no de otro sitio. */
    private static final class FalloDelMovimiento extends RuntimeException {
        FalloDelMovimiento() {
            super("fallo simulado al escribir el movimiento");
        }
    }

    private void comoAdministradorDeLaEmpresa() {
        var u = new UsuarioAutenticado(usuarioId, "prueba@ejemplo.co", "ADMINISTRADOR", empresaId);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
    }

    /**
     * El alta con existencia y su movimiento inicial son una sola unidad:
     * si el movimiento no se puede escribir, el producto no puede quedar
     * guardado con una existencia que ningún renglón del kárdex explica.
     */
    @Test
    void siElMovimientoInicialFallaNoQuedaElProducto() {
        comoAdministradorDeLaEmpresa();
        doThrow(new FalloDelMovimiento()).when(inventario).insertarMovimiento(
                anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt(), anyInt(),
                any(), any(), any());
        String codigo = "INI-" + marca;

        assertThrows(FalloDelMovimiento.class, () -> servicioProducto.crear(new SolicitudProducto(
                categoriaId, proveedorId, codigo, codigo, null, "Producto con saldo inicial",
                null, null, new BigDecimal("100.00"), 120, 5)));

        assertFalse(productos.existsByEmpresaIdAndCodigo(empresaId, codigo),
                "el alta se deshace entera: sin movimiento no hay producto");
    }

    @Test
    void unaEdicionNoDevuelveLaExistenciaAUnValorViejo() {
        transaccion.executeWithoutResult(a -> {
            // La edición lee el producto con existencia 10...
            Producto leido = productos.findByEmpresaIdAndId(empresaId, productoId).orElseThrow();

            // ...y mientras tanto un movimiento de inventario lo deja en 50.
            transaccionAparte.executeWithoutResult(b -> inventario.fijarSaldo(empresaId, productoId, 50));

            editarNombre(leido, "Producto editado");
            productos.saveAndFlush(leido);
        });

        assertEquals(50, existenciaEnBase(),
                "guardar una edición no puede escribir la existencia que se leyó al abrirla");
    }

    @Test
    void dosEdicionesSimultaneasNoSePisan() {
        assertThrows(ObjectOptimisticLockingFailureException.class, () ->
                transaccion.executeWithoutResult(a -> {
                    Producto lecturaDeA = productos.findByEmpresaIdAndId(empresaId, productoId).orElseThrow();

                    // La otra persona lee la misma versión, edita y guarda primero.
                    transaccionAparte.executeWithoutResult(b -> {
                        Producto lecturaDeB = productos.findByEmpresaIdAndId(empresaId, productoId).orElseThrow();
                        editarNombre(lecturaDeB, "Editado por B");
                        productos.saveAndFlush(lecturaDeB);
                    });

                    editarNombre(lecturaDeA, "Editado por A");
                    productos.saveAndFlush(lecturaDeA);
                }));
    }

    @Test
    void laImportacionDelataLaEdicionEnCurso() {
        Long cargaId = importacion.insertarCarga(empresaId, proveedorId, usuarioId, "prueba.xlsx", 1, 1, 0);
        // El número de fila arranca en 2: la 1 es el encabezado del archivo (ck_detalle_numero_fila).
        importacion.insertarDetalles(empresaId, cargaId, List.of(new FilaDeCarga(
                2, productoId, "CONC", "Producto de prueba", new BigDecimal("250.00"),
                new BigDecimal("100.00"), true, null, null)));

        assertThrows(ObjectOptimisticLockingFailureException.class, () ->
                transaccion.executeWithoutResult(a -> {
                    Producto leido = productos.findByEmpresaIdAndId(empresaId, productoId).orElseThrow();

                    // Una carga de precios se aplica y confirma mientras la edición sigue abierta.
                    transaccionAparte.executeWithoutResult(b -> importacion.aplicarCostos(empresaId, cargaId));

                    editarNombre(leido, "Producto editado");
                    productos.saveAndFlush(leido);
                }));

        assertEquals(0, new BigDecimal("250.00").compareTo(costoEnBase()),
                "la edición rechazada no puede devolver el costo al valor anterior a la carga");
    }
}

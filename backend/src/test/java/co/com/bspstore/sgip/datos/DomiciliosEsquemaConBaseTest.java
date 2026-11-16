package co.com.bspstore.sgip.datos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El esquema del domicilio, del producto nuevo y del encargo entregado (V45) contra la base
 * real: lo que la migración crea y lo que, además de existir, tiene que rechazar. Una
 * restricción que nunca se ha visto negar nada solo da tranquilidad.
 *
 * Crea sus empresas y las borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class DomiciliosEsquemaConBaseTest {

    @Autowired JdbcClient jdbc;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired RepositorioClientes clientes;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;

    private final List<CajaDePrueba> cajas = new ArrayList<>();
    private CajaDePrueba caja;
    private Long turno;

    @BeforeEach
    void prepararDatosPropios() {
        caja = nuevaCaja();
        turno = caja.abrirTurno(caja.administradorId);
    }

    @AfterEach
    void borrarLoQueCreo() {
        for (CajaDePrueba creada : cajas) {
            creada.borrar();
        }
    }

    private CajaDePrueba nuevaCaja() {
        CajaDePrueba creada = CajaDePrueba.crear("Esquema domicilio", jdbc, plataforma, categorias, proveedores, clientes);
        cajas.add(creada);
        return creada;
    }

    private Long venta(CajaDePrueba de, Long turnoDe, String consecutivo) {
        KeyHolder llave = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO ventas (empresa_id, consecutivo, cliente_id, usuario_id, fecha, forma_pago,
                                    cierre_id, subtotal, descuento, iva, clave_idempotencia, tipo_entrega)
                VALUES (:e, :consecutivo, :cliente, :usuario, '2026-11-12 09:00:00', 'CONTADO',
                        :turno, 10000, 0, 0, :clave, 'DOMICILIO')
                """)
                .param("e", de.empresa.empresaId).param("consecutivo", consecutivo).param("cliente", de.clienteId)
                .param("usuario", de.administradorId).param("turno", turnoDe)
                .param("clave", java.util.UUID.randomUUID().toString()).update(llave);
        return llave.getKeyAs(Number.class).longValue();
    }

    private void domicilio(CajaDePrueba de, Long venta, String direccion, String ciudad, String telefono,
                           String recibe, LocalDateTime entregadoEn, Integer entregadoPor) {
        jdbc.sql("""
                INSERT INTO domicilios_venta (venta_id, empresa_id, direccion, ciudad, telefono, recibe,
                                              entregado_en, entregado_por)
                VALUES (:venta, :e, :direccion, :ciudad, :telefono, :recibe, :en, :por)
                """)
                .param("venta", venta).param("e", de.empresa.empresaId).param("direccion", direccion)
                .param("ciudad", ciudad).param("telefono", telefono).param("recibe", recibe)
                .param("en", entregadoEn).param("por", entregadoPor).update();
    }

    private void domicilio(Long venta) {
        domicilio(caja, venta, "Calle 1 # 2-3", "Cali", "3001112233", "Ana", null, null);
    }

    private Long ventaDeLaCaja() {
        return venta(caja, turno, "V-00000001");
    }

    private void linea(Long venta, Integer producto, int cantidad, int encargo, String entrega) {
        jdbc.sql("""
                INSERT INTO venta_items (venta_id, empresa_id, producto_id, cantidad, precio_unitario, costo_unitario,
                                         tarifa_iva, tratamiento_iva, subtotal, descuento, valor_iva, utilidad,
                                         cantidad_encargo, entrega_estimada)
                VALUES (:venta, :e, :producto, :cantidad, 10000, 1000, 0, 'NO_GRAVADO', 10000, 0, 0, 9000,
                        :encargo, :entrega)
                """)
                .param("venta", venta).param("e", caja.empresa.empresaId).param("producto", producto)
                .param("cantidad", cantidad).param("encargo", encargo)
                .param("entrega", entrega == null ? null : java.sql.Date.valueOf(entrega)).update();
    }

    private String columna(String tabla, String columna, String dato) {
        return jdbc.sql("""
                SELECT %s FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = :t AND column_name = :c
                """.formatted(dato)).param("t", tabla).param("c", columna).query(String.class).optional().orElse(null);
    }

    // ------------------------------------------------------------ domicilios_venta

    @Test
    void elDomicilioGuardaLosDatosDeLaEntregaYNaceSinEntregar() {
        Long venta = ventaDeLaCaja();

        domicilio(venta);

        var fila = jdbc.sql("SELECT * FROM domicilios_venta WHERE empresa_id = :e AND venta_id = :v")
                .param("e", caja.empresa.empresaId).param("v", venta).query().singleRow();
        assertEquals("Calle 1 # 2-3", fila.get("direccion"));
        assertEquals("Cali", fila.get("ciudad"));
        assertEquals("3001112233", fila.get("telefono"));
        assertEquals("Ana", fila.get("recibe"));
        assertNull(fila.get("entregado_en"));
        assertNull(fila.get("entregado_por"));
        assertEquals("200", columna("domicilios_venta", "direccion", "character_maximum_length"));
        assertEquals("80", columna("domicilios_venta", "ciudad", "character_maximum_length"));
        assertEquals("20", columna("domicilios_venta", "telefono", "character_maximum_length"));
        assertEquals("120", columna("domicilios_venta", "recibe", "character_maximum_length"));
    }

    @Test
    void unaVentaTieneUnSoloDomicilio() {
        Long venta = ventaDeLaCaja();
        domicilio(venta);

        assertThrows(DataIntegrityViolationException.class, () -> domicilio(venta));
    }

    @Test
    void unDomicilioSoloCuelgaDeUnaVentaDeSuEmpresa() {
        Long venta = ventaDeLaCaja();
        CajaDePrueba otra = nuevaCaja();

        assertThrows(DataIntegrityViolationException.class,
                () -> domicilio(otra, venta, "Calle 1", "Cali", "3001112233", "Ana", null, null),
                "la clave foránea es compuesta con la empresa");
    }

    @Test
    void elDomicilioNoAdmiteDatosEnBlanco() {
        Long venta = ventaDeLaCaja();

        assertThrows(DataAccessException.class, () -> domicilio(caja, venta, " ", "Cali", "3001112233", "Ana", null, null));
        assertThrows(DataAccessException.class, () -> domicilio(caja, venta, "Calle 1", "", "3001112233", "Ana", null, null));
        assertThrows(DataAccessException.class, () -> domicilio(caja, venta, "Calle 1", "Cali", "  ", "Ana", null, null));
        assertThrows(DataAccessException.class, () -> domicilio(caja, venta, "Calle 1", "Cali", "3001112233", "", null, null));
        assertEquals(0, caja.cuantas("domicilios_venta"));
    }

    @Test
    void laEntregaDeUnDomicilioTraeCuandoYQuienJuntos() {
        Long venta = ventaDeLaCaja();
        LocalDateTime cuando = LocalDateTime.of(2026, 11, 12, 15, 0);

        assertThrows(DataAccessException.class,
                () -> domicilio(caja, venta, "Calle 1", "Cali", "3001112233", "Ana", cuando, null), "sin quién");
        assertThrows(DataAccessException.class,
                () -> domicilio(caja, venta, "Calle 1", "Cali", "3001112233", "Ana", null, caja.administradorId), "sin cuándo");

        domicilio(caja, venta, "Calle 1", "Cali", "3001112233", "Ana", cuando, caja.administradorId);
        assertEquals(1, caja.cuantas("domicilios_venta"));
    }

    @Test
    void quienEntregaUnDomicilioEsDeLaEmpresa() {
        Long venta = ventaDeLaCaja();
        CajaDePrueba otra = nuevaCaja();

        assertThrows(DataIntegrityViolationException.class, () -> domicilio(caja, venta, "Calle 1", "Cali", "3001112233",
                "Ana", LocalDateTime.of(2026, 11, 12, 15, 0), otra.administradorId));
    }

    @Test
    void laListaDeDomiciliosPorDespacharTieneSuIndice() {
        long indices = jdbc.sql("""
                SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'domicilios_venta'
                   AND index_name = 'ix_domicilios_pendientes' AND seq_in_index = 1 AND column_name = 'empresa_id'
                """).query(Long.class).single();
        assertEquals(1, indices);
    }

    // ------------------------------------------------------------ ventas.pago_contra_entrega

    @Test
    void laVentaNaceConPagoCompletoYSoloUnDomicilioPuedeQuedarPorCobrar() {
        Long domicilio = ventaDeLaCaja();
        assertEquals("0", jdbc.sql("SELECT pago_contra_entrega FROM ventas WHERE empresa_id = :e AND venta_id = :v")
                .param("e", caja.empresa.empresaId).param("v", domicilio).query(String.class).single());

        jdbc.sql("UPDATE ventas SET pago_contra_entrega = TRUE WHERE empresa_id = :e AND venta_id = :v")
                .param("e", caja.empresa.empresaId).param("v", domicilio).update();

        assertThrows(DataAccessException.class, () -> jdbc.sql("""
                UPDATE ventas SET tipo_entrega = 'MOSTRADOR' WHERE empresa_id = :e AND venta_id = :v
                """).param("e", caja.empresa.empresaId).param("v", domicilio).update(),
                "contra entrega solo cabe en un domicilio");
    }

    @Test
    void laListaDeLoQueFaltaPorCobrarTieneSuIndice() {
        long indices = jdbc.sql("""
                SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'ventas'
                   AND index_name = 'ix_ventas_por_cobrar' AND seq_in_index = 2 AND column_name = 'estado_pago'
                """).query(Long.class).single();
        assertEquals(1, indices);
    }

    // ------------------------------------------------------------ productos.costo_provisional

    @Test
    void unProductoNaceConElCostoDefinitivo() {
        Integer producto = caja.producto("A", 5, "10000");

        assertEquals("0", jdbc.sql("SELECT costo_provisional FROM productos WHERE empresa_id = :e AND producto_id = :p")
                .param("e", caja.empresa.empresaId).param("p", producto).query(String.class).single());
        assertEquals("NO", columna("productos", "costo_provisional", "is_nullable"));
    }

    // ------------------------------------------------------------ venta_items: la entrega del encargo

    @Test
    void laLineaDeEncargoGuardaQuienYCuandoSeEntrego() {
        Long venta = ventaDeLaCaja();
        Integer producto = caja.producto("A", 0, "10000");
        linea(venta, producto, 3, 3, "2026-12-01");

        jdbc.sql("""
                UPDATE venta_items SET encargo_entregado_en = '2026-12-02 10:00:00', encargo_entregado_por = :u
                 WHERE empresa_id = :e AND venta_id = :v
                """).param("u", caja.administradorId).param("e", caja.empresa.empresaId).param("v", venta).update();

        var fila = jdbc.sql("SELECT encargo_entregado_en, encargo_entregado_por FROM venta_items WHERE venta_id = :v")
                .param("v", venta).query().singleRow();
        assertEquals(caja.administradorId, fila.get("encargo_entregado_por"));
        assertFalse(fila.get("encargo_entregado_en") == null);
    }

    @Test
    void laEntregaDelEncargoVieneEnParejaYSoloEnUnaLineaDeEncargo() {
        Long venta = ventaDeLaCaja();
        Integer conEncargo = caja.producto("A", 0, "10000");
        Integer sinEncargo = caja.producto("B", 5, "10000");
        linea(venta, conEncargo, 3, 3, "2026-12-01");
        linea(venta, sinEncargo, 1, 0, null);
        String actualizar = """
                UPDATE venta_items SET encargo_entregado_en = :en, encargo_entregado_por = :por
                 WHERE empresa_id = :e AND venta_id = :v AND producto_id = :p
                """;

        assertThrows(DataAccessException.class, () -> jdbc.sql(actualizar).param("en", "2026-12-02 10:00:00")
                .param("por", null).param("e", caja.empresa.empresaId).param("v", venta).param("p", conEncargo).update(),
                "sin quién");
        assertThrows(DataAccessException.class, () -> jdbc.sql(actualizar).param("en", null)
                .param("por", caja.administradorId).param("e", caja.empresa.empresaId).param("v", venta)
                .param("p", conEncargo).update(), "sin cuándo");
        assertThrows(DataAccessException.class, () -> jdbc.sql(actualizar).param("en", "2026-12-02 10:00:00")
                .param("por", caja.administradorId).param("e", caja.empresa.empresaId).param("v", venta)
                .param("p", sinEncargo).update(), "una línea sin encargo no se entrega como encargo");
    }

    @Test
    void quienEntregaUnEncargoEsDeLaEmpresa() {
        Long venta = ventaDeLaCaja();
        Integer producto = caja.producto("A", 0, "10000");
        linea(venta, producto, 3, 3, "2026-12-01");
        CajaDePrueba otra = nuevaCaja();

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                UPDATE venta_items SET encargo_entregado_en = '2026-12-02 10:00:00', encargo_entregado_por = :u
                 WHERE empresa_id = :e AND venta_id = :v
                """).param("u", otra.administradorId).param("e", caja.empresa.empresaId).param("v", venta).update());
    }

    @Test
    void laListaDeEncargosTieneSuIndice() {
        long indices = jdbc.sql("""
                SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'venta_items'
                   AND index_name = 'ix_venitems_encargos' AND seq_in_index = 2 AND column_name = 'cantidad_encargo'
                """).query(Long.class).single();
        assertEquals(1, indices);
    }

    // ------------------------------------------------------------ «Sin proveedor» y «Sin categoría»

    private int cuantos(String tabla, String columna, String nombre) {
        return jdbc.sql("SELECT COUNT(*) FROM " + tabla + " WHERE empresa_id = :e AND " + columna + " = :n")
                .param("e", caja.empresa.empresaId).param("n", nombre).query(Integer.class).single();
    }

    @Test
    void sembrarSinProveedorYSinCategoriaLosDejaUnaSolaVezPorEmpresa() {
        assertEquals(0, cuantos("proveedores", "razon_social", "Sin proveedor"));
        assertEquals(0, cuantos("categorias", "nombre", "Sin categoría"));

        plataforma.sembrarSinProveedorYSinCategoria(caja.empresa.empresaId);
        plataforma.sembrarSinProveedorYSinCategoria(caja.empresa.empresaId);

        assertEquals(1, cuantos("proveedores", "razon_social", "Sin proveedor"));
        assertEquals(1, cuantos("categorias", "nombre", "Sin categoría"));
    }

    @Test
    void sembrarReutilizaLosQueLaEmpresaYaTeniaConEseNombre() {
        jdbc.sql("INSERT INTO categorias (empresa_id, nombre) VALUES (:e, 'Sin categoría')")
                .param("e", caja.empresa.empresaId).update();
        jdbc.sql("INSERT INTO proveedores (empresa_id, razon_social, nit) VALUES (:e, 'Sin proveedor', '900123456')")
                .param("e", caja.empresa.empresaId).update();

        plataforma.sembrarSinProveedorYSinCategoria(caja.empresa.empresaId);

        assertEquals(1, cuantos("proveedores", "razon_social", "Sin proveedor"));
        assertEquals(1, cuantos("categorias", "nombre", "Sin categoría"));
    }

    @Test
    void sembrarNoTocaALasOtrasEmpresas() {
        CajaDePrueba otra = nuevaCaja();

        plataforma.sembrarSinProveedorYSinCategoria(caja.empresa.empresaId);

        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM proveedores WHERE empresa_id = :e AND razon_social = 'Sin proveedor'")
                .param("e", otra.empresa.empresaId).query(Integer.class).single());
        assertTrue(cuantos("proveedores", "razon_social", "Sin proveedor") == 1);
    }
}

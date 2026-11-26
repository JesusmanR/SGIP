package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.Cliente;
import co.com.bspstore.sgip.modelo.Producto;
import co.com.bspstore.sgip.modelo.TipoConsecutivo;
import co.com.bspstore.sgip.modelo.TipoDocumento;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El esquema de las cotizaciones listas para vender (V50) contra la base real: lo que la
 * migración crea y lo que, además de existir, tiene que rechazar. Una restricción que nunca se
 * ha visto negar nada solo da tranquilidad.
 *
 * Crea sus empresas y las borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class CotizacionEsquemaConBaseTest {

    @Autowired JdbcClient jdbc;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired RepositorioClientes clientes;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired ProductoRepositorio repositorioProductos;

    private final List<EmpresaDePrueba> empresas = new ArrayList<>();
    private EmpresaDePrueba empresa;
    private Long cotizacionId;

    @BeforeEach
    void prepararDatosPropios() {
        empresa = nuevaEmpresa();
        cotizacionId = cotizacionVigente(empresa, "COT-00000001");
    }

    @AfterEach
    void borrarLoQueCreo() {
        for (EmpresaDePrueba creada : empresas) {
            creada.borrar(jdbc);
        }
    }

    private EmpresaDePrueba nuevaEmpresa() {
        EmpresaDePrueba creada = EmpresaDePrueba.crear("Esquema cotización", plataforma, jdbc, categorias, proveedores);
        empresas.add(creada);
        return creada;
    }

    private Long cotizacionVigente(EmpresaDePrueba de, String consecutivo) {
        Integer cliente = clientes.insertar(de.empresaId,
                new Cliente(null, "Cliente " + de.marca, TipoDocumento.CC, "9" + de.marca, null, null, null, "Cali", false),
                LocalDateTime.of(2026, 11, 1, 8, 0), "CAJA");
        KeyHolder llave = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO cotizaciones (empresa_id, consecutivo, cliente_id, usuario_id, estado,
                                          fecha_emision, fecha_vencimiento, subtotal, iva)
                VALUES (:e, :consecutivo, :cliente, :usuario, 'VIGENTE', '2026-11-25', '2026-12-02', 12345, 2346)
                """)
                .param("e", de.empresaId).param("consecutivo", consecutivo).param("cliente", cliente)
                .param("usuario", de.usuarioId).update(llave);
        return llave.getKeyAs(Number.class).longValue();
    }

    private String columna(String tabla, String columna, String dato) {
        return jdbc.sql("""
                SELECT %s FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = :t AND column_name = :c
                """.formatted(dato)).param("t", tabla).param("c", columna).query(String.class).optional().orElse(null);
    }

    private void anular(Long id, Integer usuario, String cuando, String motivo, EmpresaDePrueba de) {
        jdbc.sql("""
                UPDATE cotizaciones
                   SET estado = 'ANULADA', anulada_por = :usuario, anulada_en = :cuando, motivo_anulacion = :motivo
                 WHERE empresa_id = :e AND cotizacion_id = :id
                """)
                .param("usuario", usuario).param("cuando", cuando).param("motivo", motivo)
                .param("e", de.empresaId).param("id", id).update();
    }

    private void assertViolaElCheck(Runnable accion, String mensaje) {
        DataAccessException error = assertThrows(DataAccessException.class, accion::run, mensaje);
        assertTrue(error.getMessage().contains("ck_cotizaciones_anulacion"),
                mensaje + ": debía violar ck_cotizaciones_anulacion y fue " + error.getMessage());
    }

    // ------------------------------------------------------------ las líneas

    @Test
    void losMontosDeLaLineaNoSonGenerados() {
        for (String monto : List.of("subtotal", "valor_iva")) {
            String extra = columna("cotizacion_items", monto, "extra");
            assertFalse(extra.toLowerCase().contains("generated"), monto + " ya no es generada: " + extra);
            assertEquals("NO", columna("cotizacion_items", monto, "is_nullable"));
            assertEquals("decimal(14,2)", columna("cotizacion_items", monto, "column_type"));
        }
    }

    @Test
    void laLineaGuardaLosMontosTalComoSeCalcularon() {
        Integer producto = repositorioProductos.save(new Producto(empresa.empresaId, empresa.categoriaId,
                empresa.proveedorId, "ART-" + empresa.marca, "ART-" + empresa.marca, "Artículo",
                new BigDecimal("1000.00"))).getId();

        // 1 × 12 345 al 19 %: el IVA con centavos es 2 345,55; el cálculo de la aplicación lo escribe redondeado.
        jdbc.sql("""
                INSERT INTO cotizacion_items (empresa_id, cotizacion_id, producto_id, cantidad, precio_unitario,
                                              tarifa_iva, tratamiento_iva, subtotal, valor_iva)
                VALUES (:e, :cot, :producto, 1, 12345, 0.19, 'GRAVADO', 12345, 2346)
                """).param("e", empresa.empresaId).param("cot", cotizacionId).param("producto", producto).update();

        assertEquals(0, new BigDecimal("2346").compareTo(jdbc.sql(
                        "SELECT valor_iva FROM cotizacion_items WHERE empresa_id = :e AND cotizacion_id = :cot")
                .param("e", empresa.empresaId).param("cot", cotizacionId).query(BigDecimal.class).single()));
    }

    @Test
    void laLineaRechazaMontosNegativos() {
        Integer producto = repositorioProductos.save(new Producto(empresa.empresaId, empresa.categoriaId,
                empresa.proveedorId, "NEG-" + empresa.marca, "NEG-" + empresa.marca, "Artículo",
                new BigDecimal("1000.00"))).getId();
        String insertar = """
                INSERT INTO cotizacion_items (empresa_id, cotizacion_id, producto_id, cantidad, precio_unitario,
                                              tarifa_iva, tratamiento_iva, subtotal, valor_iva)
                VALUES (:e, :cot, :producto, 1, 12345, 0.19, 'GRAVADO', :subtotal, :iva)
                """;

        assertThrows(DataAccessException.class, () -> jdbc.sql(insertar).param("e", empresa.empresaId)
                .param("cot", cotizacionId).param("producto", producto)
                .param("subtotal", new BigDecimal("-1")).param("iva", BigDecimal.ZERO).update(), "subtotal negativo");
        assertThrows(DataAccessException.class, () -> jdbc.sql(insertar).param("e", empresa.empresaId)
                .param("cot", cotizacionId).param("producto", producto)
                .param("subtotal", new BigDecimal("12345")).param("iva", new BigDecimal("-1")).update(),
                "IVA negativo");
    }

    // ------------------------------------------------------------ el consecutivo

    @Test
    void elConsecutivoAdmiteCotizacion() {
        String tipo = columna("consecutivos", "tipo", "column_type");
        assertTrue(tipo.contains("'COTIZACION'"), tipo);

        List<String> delEnum = Arrays.stream(tipo.substring("enum(".length(), tipo.length() - 1).split(","))
                .map(valor -> valor.replace("'", "")).toList();
        List<String> delCodigo = Arrays.stream(TipoConsecutivo.values()).map(Enum::name).toList();
        assertEquals(delEnum, delCodigo, "el enum de Java y el ENUM de la columna, en el mismo orden");

        jdbc.sql("INSERT INTO consecutivos (empresa_id, tipo) VALUES (:e, 'COTIZACION')")
                .param("e", empresa.empresaId).update();
    }

    // ------------------------------------------------------------ la anulación

    @Test
    void anularExigeLosTresDatos() {
        assertViolaElCheck(() -> anular(cotizacionId, empresa.usuarioId, "2026-11-26 10:00:00", null, empresa),
                "sin motivo");
        assertViolaElCheck(() -> anular(cotizacionId, empresa.usuarioId, null, "Cliente desistió", empresa),
                "sin fecha");
        assertViolaElCheck(() -> anular(cotizacionId, null, "2026-11-26 10:00:00", "Cliente desistió", empresa),
                "sin quien anula");

        anular(cotizacionId, empresa.usuarioId, "2026-11-26 10:00:00", "Cliente desistió", empresa);
        assertEquals("ANULADA", jdbc.sql("SELECT estado FROM cotizaciones WHERE empresa_id = :e AND cotizacion_id = :id")
                .param("e", empresa.empresaId).param("id", cotizacionId).query(String.class).single());
    }

    @Test
    void noSePuedeLlenarLaAnulacionSinAnular() {
        assertViolaElCheck(() -> jdbc.sql("""
                UPDATE cotizaciones SET anulada_en = '2026-11-26 10:00:00'
                 WHERE empresa_id = :e AND cotizacion_id = :id
                """).param("e", empresa.empresaId).param("id", cotizacionId).update(),
                "anulada_en en una vigente");
        assertViolaElCheck(() -> jdbc.sql("""
                UPDATE cotizaciones SET motivo_anulacion = 'Sin razón'
                 WHERE empresa_id = :e AND cotizacion_id = :id
                """).param("e", empresa.empresaId).param("id", cotizacionId).update(),
                "motivo en una vigente");
    }

    @Test
    void quienAnulaTieneQueSerDeLaMismaEmpresa() {
        EmpresaDePrueba otra = nuevaEmpresa();

        assertThrows(DataIntegrityViolationException.class,
                () -> anular(cotizacionId, otra.usuarioId, "2026-11-26 10:00:00", "Cliente desistió", empresa),
                "usuario de otra empresa");
    }

    // ------------------------------------------------------------ el índice

    @Test
    void existeElIndiceDeEstado() {
        List<String> columnas = jdbc.sql("""
                SELECT column_name FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'cotizaciones'
                   AND index_name = 'ix_cotizaciones_empresa_estado'
                 ORDER BY seq_in_index
                """).query(String.class).list();

        assertEquals(List.of("empresa_id", "estado", "fecha_vencimiento"), columnas);
    }
}

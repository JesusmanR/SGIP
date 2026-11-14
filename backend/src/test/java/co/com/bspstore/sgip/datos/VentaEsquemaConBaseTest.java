package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.Cliente;
import co.com.bspstore.sgip.modelo.TipoDocumento;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * El esquema de la venta en el mostrador (V44) contra la base real: lo que la migración
 * crea y lo que, además de existir, tiene que rechazar. Una restricción que nunca se ha
 * visto negar nada solo da tranquilidad.
 *
 * Crea sus empresas y las borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class VentaEsquemaConBaseTest {

    private static final String ARTICULO = "ARTICULO";

    @Autowired JdbcClient jdbc;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired RepositorioClientes clientes;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired ProductoRepositorio repositorioProductos;

    private final List<EmpresaDePrueba> empresas = new ArrayList<>();
    private EmpresaDePrueba empresa;
    private Integer clienteId;
    private Integer productoId;
    private Integer medioId;
    private Long turnoId;

    @BeforeEach
    void prepararDatosPropios() {
        empresa = nuevaEmpresa();
        clienteId = cliente(empresa);
        productoId = producto(empresa);
        medioId = medio(empresa, "Efectivo");
        turnoId = turno(empresa);
    }

    @AfterEach
    void borrarLoQueCreo() {
        for (EmpresaDePrueba creada : empresas) {
            creada.borrar(jdbc);
        }
    }

    private EmpresaDePrueba nuevaEmpresa() {
        EmpresaDePrueba creada = EmpresaDePrueba.crear("Esquema venta", plataforma, jdbc, categorias, proveedores);
        empresas.add(creada);
        return creada;
    }

    private Integer cliente(EmpresaDePrueba de) {
        return clientes.insertar(de.empresaId,
                new Cliente(null, "Cliente " + de.marca, TipoDocumento.CC, "9" + de.marca, null, null, null, "Cali", false),
                LocalDateTime.of(2026, 11, 1, 8, 0), "CAJA");
    }

    private Integer producto(EmpresaDePrueba de) {
        var p = new co.com.bspstore.sgip.modelo.Producto(de.empresaId, de.categoriaId, de.proveedorId,
                ARTICULO + "-" + de.marca, ARTICULO + "-" + de.marca, "Artículo", new BigDecimal("1000.00"));
        return repositorioProductos.save(p).getId();
    }

    private Integer medio(EmpresaDePrueba de, String nombre) {
        KeyHolder llave = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO medios_pago (empresa_id, nombre, tipo) VALUES (:e, :n, 'EFECTIVO')")
                .param("e", de.empresaId).param("n", nombre).update(llave);
        return llave.getKeyAs(Number.class).intValue();
    }

    private Long turno(EmpresaDePrueba de) {
        KeyHolder llave = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO cierres_caja (empresa_id, usuario_id, apertura, base_efectivo)
                VALUES (:e, :u, '2026-11-12 08:00:00', 0)
                """).param("e", de.empresaId).param("u", de.usuarioId).update(llave);
        return llave.getKeyAs(Number.class).longValue();
    }

    private Long venta(EmpresaDePrueba de, Integer cliente, Long turno, String consecutivo, String clave) {
        KeyHolder llave = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO ventas (empresa_id, consecutivo, cliente_id, usuario_id, fecha, forma_pago,
                                    cierre_id, subtotal, descuento, iva, clave_idempotencia)
                VALUES (:e, :consecutivo, :cliente, :usuario, '2026-11-12 09:00:00', 'CONTADO',
                        :turno, 10000, 1000, 1710, :clave)
                """)
                .param("e", de.empresaId).param("consecutivo", consecutivo).param("cliente", cliente)
                .param("usuario", de.usuarioId).param("turno", turno).param("clave", clave)
                .update(llave);
        return llave.getKeyAs(Number.class).longValue();
    }

    private String columna(String tabla, String columna, String dato) {
        return jdbc.sql("""
                SELECT %s FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = :t AND column_name = :c
                """.formatted(dato)).param("t", tabla).param("c", columna).query(String.class).optional().orElse(null);
    }

    // ------------------------------------------------------------ consecutivos

    @Test
    void consecutivosTieneLaClaveDeLaEmpresaYElTipoYArrancaEnUno() {
        jdbc.sql("INSERT INTO consecutivos (empresa_id, tipo) VALUES (:e, 'VENTA')")
                .param("e", empresa.empresaId).update();

        assertEquals("1", jdbc.sql("SELECT siguiente FROM consecutivos WHERE empresa_id = :e AND tipo = 'VENTA'")
                .param("e", empresa.empresaId).query(String.class).single());
        assertEquals("enum('VENTA','DEVOLUCION','PRODUCTO_RAPIDO')", columna("consecutivos", "tipo", "column_type"));
        assertThrows(DuplicateKeyException.class, () -> jdbc.sql("INSERT INTO consecutivos (empresa_id, tipo) VALUES (:e, 'VENTA')")
                .param("e", empresa.empresaId).update(), "una sola fila por empresa y tipo");
        // Otro tipo de la misma empresa y el mismo tipo de otra empresa sí entran.
        jdbc.sql("INSERT INTO consecutivos (empresa_id, tipo) VALUES (:e, 'DEVOLUCION')")
                .param("e", empresa.empresaId).update();
        EmpresaDePrueba otra = nuevaEmpresa();
        jdbc.sql("INSERT INTO consecutivos (empresa_id, tipo) VALUES (:e, 'VENTA')").param("e", otra.empresaId).update();
    }

    @Test
    void unConsecutivoRechazaUnTipoInventadoUnaEmpresaAjenaYUnSiguienteMenorQueUno() {
        assertThrows(DataAccessException.class, () -> jdbc.sql(
                        "INSERT INTO consecutivos (empresa_id, tipo) VALUES (:e, 'INVENTADO')")
                .param("e", empresa.empresaId).update());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql(
                        "INSERT INTO consecutivos (empresa_id, tipo) VALUES (-1, 'VENTA')").update());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql(
                        "INSERT INTO consecutivos (empresa_id, tipo, siguiente) VALUES (:e, 'VENTA', 0)")
                .param("e", empresa.empresaId).update());
    }

    // ------------------------------------------------------------ la venta

    @Test
    void laVentaYaNoGuardaElMedioPorqueElMedioVaEnCadaPago() {
        assertEquals(null, columna("ventas", "medio_id", "column_name"));
        assertEquals(0, jdbc.sql("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                 WHERE table_schema = DATABASE() AND table_name = 'ventas'
                   AND constraint_name IN ('fk_ventas_medio', 'fk_ventas_medio_emp')
                """).query(Integer.class).single(), "las claves foráneas del medio también se van");
        assertEquals(0, jdbc.sql("""
                SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'ventas'
                   AND index_name IN ('fk_ventas_medio', 'fk_ventas_medio_emp')
                """).query(Integer.class).single(), "y sus índices");
    }

    @Test
    void laClaveDeIdempotenciaEsUnicaPorEmpresaYAdmiteVariasVentasSinClave() {
        String clave = "11111111-2222-3333-4444-555555555555";
        venta(empresa, clienteId, turnoId, "V-00000001", clave);

        assertThrows(DuplicateKeyException.class,
                () -> venta(empresa, clienteId, turnoId, "V-00000002", clave),
                "la misma clave en la misma empresa");
        // Las ventas anteriores a la V44 no tienen clave, y varias sin clave conviven.
        venta(empresa, clienteId, turnoId, "V-00000003", null);
        venta(empresa, clienteId, turnoId, "V-00000004", null);
        // Otra empresa puede usar la misma clave.
        EmpresaDePrueba otra = nuevaEmpresa();
        venta(otra, cliente(otra), turno(otra), "V-00000001", clave);
        assertEquals("char(36)", columna("ventas", "clave_idempotencia", "column_type"));
        assertEquals("char(64)", columna("ventas", "huella_carga", "column_type"));
    }

    @Test
    void elDescuentoDeLaCompraSeGuardaCompletoOVacioYElPorcentajeNoPasaDeCien() {
        Long id = venta(empresa, clienteId, turnoId, "V-00000010", null);
        String actualizar = "UPDATE ventas SET descuento_tipo = :t, descuento_valor = :v WHERE empresa_id = :e AND venta_id = :id";

        jdbc.sql(actualizar).param("t", "PORCENTAJE").param("v", new BigDecimal("10")).param("e", empresa.empresaId)
                .param("id", id).update();
        jdbc.sql(actualizar).param("t", "PESOS").param("v", new BigDecimal("5000")).param("e", empresa.empresaId)
                .param("id", id).update();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql(actualizar).param("t", "PORCENTAJE")
                .param("v", new BigDecimal("100.01")).param("e", empresa.empresaId).param("id", id).update(),
                "más del 100 %");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql(actualizar).param("t", "PESOS")
                .param("v", null).param("e", empresa.empresaId).param("id", id).update(), "tipo sin valor");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql(actualizar).param("t", null)
                .param("v", new BigDecimal("5")).param("e", empresa.empresaId).param("id", id).update(),
                "valor sin tipo");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql(actualizar).param("t", "PESOS")
                .param("v", new BigDecimal("-1")).param("e", empresa.empresaId).param("id", id).update(),
                "valor negativo");
        assertEquals("MOSTRADOR", jdbc.sql("SELECT tipo_entrega FROM ventas WHERE empresa_id = :e AND venta_id = :id")
                .param("e", empresa.empresaId).param("id", id).query(String.class).single());
        assertEquals("enum('MOSTRADOR','DOMICILIO')", columna("ventas", "tipo_entrega", "column_type"));
    }

    @Test
    void elTotalGeneradoDeLaVentaSigueSiendoSubtotalMenosDescuentoMasIvaMasEnvio() {
        Long id = venta(empresa, clienteId, turnoId, "V-00000011", null);

        assertEquals(0, new BigDecimal("10710").compareTo(jdbc.sql(
                        "SELECT total FROM ventas WHERE empresa_id = :e AND venta_id = :id")
                .param("e", empresa.empresaId).param("id", id).query(BigDecimal.class).single()));
    }

    // ------------------------------------------------------------ las líneas

    private void linea(Long venta, int cantidad, String subtotal, String descuento, String iva, String utilidad,
                       int encargo, String entrega) {
        jdbc.sql("""
                INSERT INTO venta_items (venta_id, empresa_id, producto_id, cantidad, precio_unitario,
                                         costo_unitario, tarifa_iva, tratamiento_iva, subtotal, descuento,
                                         valor_iva, utilidad, cantidad_encargo, entrega_estimada)
                VALUES (:venta, :e, :producto, :cantidad, 10000, 1000, 0.19, 'GRAVADO', :subtotal, :descuento,
                        :iva, :utilidad, :encargo, :entrega)
                """)
                .param("venta", venta).param("e", empresa.empresaId).param("producto", productoId)
                .param("cantidad", cantidad).param("subtotal", new BigDecimal(subtotal))
                .param("descuento", new BigDecimal(descuento)).param("iva", new BigDecimal(iva))
                .param("utilidad", new BigDecimal(utilidad)).param("encargo", encargo).param("entrega", entrega)
                .update();
    }

    @Test
    void losImportesDeLaLineaYaNoSonGeneradosYSeGuardanTalComoSeCalcularon() {
        Long id = venta(empresa, clienteId, turnoId, "V-00000020", null);

        // Precio 10 000 con el 10 % de descuento y el 19 % de IVA sobre lo descontado.
        linea(id, 1, "10000", "1000", "1710", "8000", 0, null);

        var fila = jdbc.sql("""
                SELECT subtotal, descuento, valor_iva, utilidad, cantidad_encargo
                  FROM venta_items WHERE empresa_id = :e AND venta_id = :id
                """).param("e", empresa.empresaId).param("id", id)
                .query((rs, n) -> List.of(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4), new BigDecimal(rs.getInt(5)))).single();
        assertEquals(0, new BigDecimal("10000").compareTo(fila.get(0)));
        assertEquals(0, new BigDecimal("1000").compareTo(fila.get(1)), "el descuento queda en la línea");
        assertEquals(0, new BigDecimal("1710").compareTo(fila.get(2)), "el IVA es sobre lo descontado");
        assertEquals(0, new BigDecimal("8000").compareTo(fila.get(3)));
        for (String generada : List.of("subtotal", "valor_iva", "utilidad")) {
            String extra = columna("venta_items", generada, "extra");
            assertFalse(extra.toLowerCase().contains("generated"), generada + " ya no es generada: " + extra);
            assertEquals("NO", columna("venta_items", generada, "is_nullable"));
        }
        assertEquals("0.00", columna("venta_items", "descuento", "column_default"));
        assertEquals("0", columna("venta_items", "cantidad_encargo", "column_default"));
    }

    @Test
    void laLineaRechazaImportesNegativosYUnDescuentoMayorQueElSubtotalPeroAdmiteUtilidadNegativa() {
        Long id = venta(empresa, clienteId, turnoId, "V-00000021", null);

        assertThrows(DataIntegrityViolationException.class,
                () -> linea(id, 1, "-1", "0", "0", "0", 0, null), "subtotal negativo");
        assertThrows(DataIntegrityViolationException.class,
                () -> linea(id, 1, "10000", "-1", "0", "0", 0, null), "descuento negativo");
        assertThrows(DataIntegrityViolationException.class,
                () -> linea(id, 1, "10000", "0", "-1", "0", 0, null), "IVA negativo");
        assertThrows(DataIntegrityViolationException.class,
                () -> linea(id, 1, "10000", "10001", "0", "0", 0, null), "descuento mayor que el subtotal");
        // Un descuento autorizado puede dejar la venta por debajo del costo: eso no es un error.
        linea(id, 1, "10000", "9500", "95", "-500", 0, null);
    }

    @Test
    void unEncargoExigeFechaYNoPuedeSerMayorQueLaCantidad() {
        Long id = venta(empresa, clienteId, turnoId, "V-00000022", null);

        assertThrows(DataIntegrityViolationException.class,
                () -> linea(id, 5, "50000", "0", "9500", "40000", 2, null), "encargo sin fecha");
        assertThrows(DataIntegrityViolationException.class,
                () -> linea(id, 5, "50000", "0", "9500", "40000", 6, "2026-12-01"), "encargo mayor que la cantidad");
        assertThrows(DataIntegrityViolationException.class,
                () -> linea(id, 5, "50000", "0", "9500", "40000", -1, null), "encargo negativo");
        linea(id, 5, "50000", "0", "9500", "40000", 5, "2026-12-01");
        assertEquals("date", columna("venta_items", "entrega_estimada", "data_type"));
    }

    // ------------------------------------------------------------ los pagos

    private void pago(Long venta, Integer medio, Long cierre, String monto, String recibido, String referencia,
                      EmpresaDePrueba de) {
        jdbc.sql("""
                INSERT INTO pagos_venta (empresa_id, venta_id, medio_id, monto, referencia, recibido, cierre_id,
                                         registrado_por, registrado_en)
                VALUES (:e, :venta, :medio, :monto, :referencia, :recibido, :cierre, :usuario, '2026-11-12 09:00:00')
                """)
                .param("e", empresa.empresaId).param("venta", venta).param("medio", medio)
                .param("monto", new BigDecimal(monto)).param("referencia", referencia)
                .param("recibido", recibido == null ? null : new BigDecimal(recibido)).param("cierre", cierre)
                .param("usuario", de.usuarioId).update();
    }

    @Test
    void cadaPagoGuardaSuMedioSuTurnoYQuienLoRecibio() {
        Long id = venta(empresa, clienteId, turnoId, "V-00000030", null);

        pago(id, medioId, turnoId, "10710", "20000", null, empresa);

        var fila = jdbc.sql("""
                SELECT monto, recibido, cierre_id, registrado_por, DATE_FORMAT(registrado_en, '%Y-%m-%d %H:%i:%s')
                  FROM pagos_venta WHERE empresa_id = :e AND venta_id = :id
                """).param("e", empresa.empresaId).param("id", id)
                .query((rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5))).single();
        assertEquals(List.of("10710.00", "20000.00", String.valueOf(turnoId), String.valueOf(empresa.usuarioId),
                "2026-11-12 09:00:00"), fila);
        assertEquals("YES", columna("pagos_venta", "cierre_id", "is_nullable"),
                "nulo solo con el saldo a favor, que no pasa por la caja");
    }

    @Test
    void unPagoRechazaMontoNoPositivoRecibidoMenorQueElMontoYReferenciasDeOtraEmpresa() {
        Long id = venta(empresa, clienteId, turnoId, "V-00000031", null);
        EmpresaDePrueba otra = nuevaEmpresa();
        Integer medioAjeno = medio(otra, "Efectivo");
        Long turnoAjeno = turno(otra);

        assertThrows(DataIntegrityViolationException.class,
                () -> pago(id, medioId, turnoId, "0", null, null, empresa), "monto cero");
        assertThrows(DataIntegrityViolationException.class,
                () -> pago(id, medioId, turnoId, "-5", null, null, empresa), "monto negativo");
        assertThrows(DataIntegrityViolationException.class,
                () -> pago(id, medioId, turnoId, "10000", "9999", null, empresa), "recibido menor que el monto");
        assertThrows(DataIntegrityViolationException.class,
                () -> pago(id, medioAjeno, turnoId, "10000", null, null, empresa), "medio de otra empresa");
        assertThrows(DataIntegrityViolationException.class,
                () -> pago(id, medioId, turnoAjeno, "10000", null, null, empresa), "turno de otra empresa");
        assertThrows(DataIntegrityViolationException.class,
                () -> pago(id, medioId, turnoId, "10000", null, null, otra), "recibido por un usuario de otra empresa");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                INSERT INTO pagos_venta (empresa_id, venta_id, medio_id, monto, cierre_id, registrado_por, registrado_en)
                VALUES (:e, 999999999, :medio, 100, :cierre, :usuario, '2026-11-12 09:00:00')
                """).param("e", empresa.empresaId).param("medio", medioId).param("cierre", turnoId)
                .param("usuario", empresa.usuarioId).update(), "venta inexistente");
    }

    // ------------------------------------------------------------ el kárdex

    @Test
    void elKardexGuardaQuienAutorizoYSoloDeLaMismaEmpresa() {
        EmpresaDePrueba otra = nuevaEmpresa();
        String insertar = """
                INSERT INTO movimientos_inventario (empresa_id, producto_id, usuario_id, tipo, cantidad,
                                                    existencia_anterior, existencia_posterior, autorizado_por)
                VALUES (:e, :producto, :usuario, 'EGRESO', 1, 5, 4, :autoriza)
                """;

        jdbc.sql(insertar).param("e", empresa.empresaId).param("producto", productoId)
                .param("usuario", empresa.usuarioId).param("autoriza", empresa.usuarioId).update();
        jdbc.sql(insertar).param("e", empresa.empresaId).param("producto", productoId)
                .param("usuario", empresa.usuarioId).param("autoriza", null).update();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql(insertar)
                        .param("e", empresa.empresaId).param("producto", productoId)
                        .param("usuario", empresa.usuarioId).param("autoriza", otra.usuarioId).update(),
                "quien autoriza tiene que ser de la empresa");
        assertEquals(1, jdbc.sql("""
                SELECT COUNT(*) FROM movimientos_inventario
                 WHERE empresa_id = :e AND autorizado_por IS NULL
                """).param("e", empresa.empresaId).query(Integer.class).single(), "el sin autorización queda nulo");
    }
}

package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.api.dto.VentaRegistrada;
import co.com.bspstore.sgip.logica.ServicioVentas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * El esquema de la V46 (RF-68): lo que la base impide aunque la aplicación se equivoque. El saldo no es
 * negativo, un movimiento del saldo dice de dónde viene, todo cuelga de la empresa con claves compuestas
 * (una devolución, un reembolso o un saldo de otra empresa no se pueden escribir) y el consecutivo no se
 * repite dentro de la empresa.
 *
 * Crea sus empresas y las borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class DevolucionesEsquemaConBaseTest {

    @Autowired ServicioVentas ventas;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioClientes clientes;
    @Autowired JdbcClient jdbc;

    private CajaDePrueba caja;
    private CajaDePrueba ajena;
    private Long venta;
    private Long turno;

    @BeforeEach
    void prepararDatosPropios() {
        caja = CajaDePrueba.crear("Esquema de devoluciones", jdbc, plataforma, categorias, proveedores, clientes);
        ajena = CajaDePrueba.crear("Ajena", jdbc, plataforma, categorias, proveedores, clientes);
        turno = caja.abrirTurno(caja.administradorId);
        Integer producto = caja.producto("A", 5, "10000");
        caja.entrarComoAdministrador();
        VentaRegistrada v = ventas.registrar(new SolicitudVenta(UUID.randomUUID(), caja.clienteId,
                List.of(new LineaSolicitada(producto, null, 2, null, null)), null,
                List.of(new PagoSolicitado(caja.efectivoId, new BigDecimal("20000"), null, null)), null, null, null));
        venta = v.id();
    }

    @AfterEach
    void borrarLoQueCreo() {
        if (ajena != null) {
            ajena.borrar();
        }
        if (caja != null) {
            caja.borrar();
        }
    }

    // ------------------------------------------------------- ayudas

    /**
     * Lo que la base rechaza: una clave foránea o única llega como {@code DataIntegrityViolationException} y
     * un CHECK (MySQL 3819) como {@code UncategorizedSQLException}, que {@code ManejadorErrores} responde
     * 400. Las dos son la base diciendo que no.
     */
    private static void laBaseRechaza(Runnable escritura) {
        laBaseRechaza(escritura, "la escritura");
    }

    private static void laBaseRechaza(Runnable escritura, String que) {
        try {
            escritura.run();
        } catch (DataIntegrityViolationException | UncategorizedSQLException esperada) {
            return;
        }
        fail("la base debía rechazar: " + que);
    }

    private Long insertarDevolucion(CajaDePrueba de, Long ventaId, String consecutivo, Long cierre) {
        KeyHolder llave = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO devoluciones (empresa_id, consecutivo, venta_id, motivo, autorizado_por, registrado_por,
                                          cierre_id, creado_en, clave_idempotencia, huella_carga, estado_resultante,
                                          saldo_por_cobrar)
                VALUES (:e, :c, :v, 'Motivo', :u, :u, :cierre, '2026-11-12 09:00:00', :clave, :huella,
                        'DEVUELTA_PARCIAL', 0)
                """).param("e", de.empresa.empresaId).param("c", consecutivo).param("v", ventaId)
                .param("clave", UUID.randomUUID().toString()).param("huella", "0".repeat(64))
                .param("u", de.administradorId).param("cierre", cierre).update(llave);
        return llave.getKeyAs(Number.class).longValue();
    }

    private void insertarSaldo(CajaDePrueba de, Integer cliente, String saldo) {
        jdbc.sql("INSERT INTO saldos_cliente (empresa_id, cliente_id, saldo) VALUES (:e, :c, :s)")
                .param("e", de.empresa.empresaId).param("c", cliente).param("s", new BigDecimal(saldo)).update();
    }

    private void insertarMovimiento(String tipo, String monto, Long devolucion, Long ventaId) {
        jdbc.sql("""
                INSERT INTO movimientos_saldo (empresa_id, cliente_id, tipo, monto, devolucion_id, venta_id, creado_en)
                VALUES (:e, :c, :t, :m, :d, :v, '2026-11-12 09:00:00')
                """).param("e", caja.empresa.empresaId).param("c", caja.clienteId).param("t", tipo)
                .param("m", new BigDecimal(monto)).param("d", devolucion).param("v", ventaId).update();
    }

    // ------------------------------------------------------- la venta

    @Test
    void laVentaAdmiteElEstadoDevueltaParcial() {
        int filas = jdbc.sql("UPDATE ventas SET estado = 'DEVUELTA_PARCIAL' WHERE empresa_id = :e AND venta_id = :v")
                .param("e", caja.empresa.empresaId).param("v", venta).update();

        assertEquals(1, filas);
        assertEquals("DEVUELTA_PARCIAL", jdbc.sql("SELECT estado FROM ventas WHERE venta_id = :v")
                .param("v", venta).query(String.class).single());
    }

    // ------------------------------------------------------- la devolución

    @Test
    void elConsecutivoNoSeRepiteEnLaEmpresaPeroSiEntreEmpresas() {
        insertarDevolucion(caja, venta, "D-00000001", turno);

        laBaseRechaza(() -> insertarDevolucion(caja, venta, "D-00000001", turno));
        // Otra empresa numera desde uno: su D-00000001 no choca con el de esta.
        ajena.abrirTurno(ajena.administradorId);
        ajena.entrarComoAdministrador();
        Integer productoAjeno = ajena.producto("A", 5, "10000");
        VentaRegistrada deLaAjena = ventas.registrar(new SolicitudVenta(UUID.randomUUID(), ajena.clienteId,
                List.of(new LineaSolicitada(productoAjeno, null, 1, null, null)), null,
                List.of(new PagoSolicitado(ajena.efectivoId, new BigDecimal("10000"), null, null)), null, null, null));
        insertarDevolucion(ajena, deLaAjena.id(), "D-00000001", null);
    }

    @Test
    void unaDevolucionNoPuedeApuntarAUnaVentaDeOtraEmpresa() {
        ajena.abrirTurno(ajena.administradorId);
        ajena.entrarComoAdministrador();
        Integer productoAjeno = ajena.producto("A", 5, "10000");
        VentaRegistrada deLaAjena = ventas.registrar(new SolicitudVenta(UUID.randomUUID(), ajena.clienteId,
                List.of(new LineaSolicitada(productoAjeno, null, 1, null, null)), null,
                List.of(new PagoSolicitado(ajena.efectivoId, new BigDecimal("10000"), null, null)), null, null, null));

        laBaseRechaza(() -> insertarDevolucion(caja, deLaAjena.id(), "D-00000009", turno));
    }

    @Test
    void unaDevolucionNoPuedeUsarElTurnoDeOtraEmpresa() {
        Long turnoAjeno = ajena.abrirTurno(ajena.administradorId);

        laBaseRechaza(() -> insertarDevolucion(caja, venta, "D-00000002", turnoAjeno));
    }

    @Test
    void elReembolsoNoPuedeUsarUnMedioDeOtraEmpresaNiUnMontoCeroOMenos() {
        Long devolucion = insertarDevolucion(caja, venta, "D-00000003", turno);

        for (Object[] caso : new Object[][]{{ajena.efectivoId, "1000"}, {caja.efectivoId, "0"}, {caja.efectivoId, "-5"}}) {
            laBaseRechaza(() -> jdbc.sql("""
                    INSERT INTO reembolsos (empresa_id, devolucion_id, medio_id, monto)
                    VALUES (:e, :d, :m, :monto)
                    """).param("e", caja.empresa.empresaId).param("d", devolucion).param("m", caso[0])
                    .param("monto", new BigDecimal((String) caso[1])).update(), String.valueOf(caso[1]));
        }
    }

    @Test
    void unItemNoPuedeTenerCantidadCeroNiValorNegativoNiUnProductoAjeno() {
        Long devolucion = insertarDevolucion(caja, venta, "D-00000004", turno);
        Integer propio = jdbc.sql("SELECT producto_id FROM venta_items WHERE venta_id = :v")
                .param("v", venta).query(Integer.class).single();
        Integer ajeno = ajena.producto("Z", 1, "10000");

        for (Object[] caso : new Object[][]{{propio, 0, "100"}, {propio, 1, "-1"}, {ajeno, 1, "100"}}) {
            laBaseRechaza(() -> jdbc.sql("""
                    INSERT INTO devolucion_items (devolucion_id, empresa_id, producto_id, cantidad, valor)
                    VALUES (:d, :e, :p, :c, :v)
                    """).param("d", devolucion).param("e", caja.empresa.empresaId).param("p", caso[0])
                    .param("c", caso[1]).param("v", new BigDecimal((String) caso[2])).update());
        }
        jdbc.sql("""
                INSERT INTO devolucion_items (devolucion_id, empresa_id, producto_id, cantidad, valor)
                VALUES (:d, :e, :p, 1, 10000)
                """).param("d", devolucion).param("e", caja.empresa.empresaId).param("p", propio).update();
        laBaseRechaza(() -> jdbc.sql("""
                INSERT INTO devolucion_items (devolucion_id, empresa_id, producto_id, cantidad, valor)
                VALUES (:d, :e, :p, 1, 10000)
                """).param("d", devolucion).param("e", caja.empresa.empresaId).param("p", propio).update(),
                "un producto una sola vez por devolución");
    }

    // ------------------------------------------------------- el saldo

    @Test
    void elSaldoNoPuedeSerNegativo() {
        insertarSaldo(caja, caja.clienteId, "100");

        laBaseRechaza(() -> jdbc.sql("""
                UPDATE saldos_cliente SET saldo = -1 WHERE empresa_id = :e AND cliente_id = :c
                """).param("e", caja.empresa.empresaId).param("c", caja.clienteId).update());
        laBaseRechaza(() -> insertarSaldo(caja, caja.clienteId, "5"),
                "un cliente tiene una sola fila de saldo");
    }

    @Test
    void elSaldoNoPuedeSerDeUnClienteDeOtraEmpresa() {
        laBaseRechaza(() -> jdbc.sql("""
                INSERT INTO saldos_cliente (empresa_id, cliente_id, saldo) VALUES (:e, :c, 10)
                """).param("e", caja.empresa.empresaId).param("c", ajena.clienteId).update());
    }

    @Test
    void unMovimientoDelSaldoDiceDeDondeVieneSegunSuTipo() {
        insertarSaldo(caja, caja.clienteId, "100");
        Long devolucion = insertarDevolucion(caja, venta, "D-00000005", turno);

        insertarMovimiento("ABONO", "100", devolucion, null);
        insertarMovimiento("USO", "50", null, venta);

        laBaseRechaza(() -> insertarMovimiento("ABONO", "10", null, null),
                "un abono viene de una devolución");
        laBaseRechaza(() -> insertarMovimiento("ABONO", "10", null, venta),
                "un abono no viene de una venta");
        laBaseRechaza(() -> insertarMovimiento("USO", "10", null, null),
                "un uso se hace en una venta");
        laBaseRechaza(() -> insertarMovimiento("USO", "10", devolucion, venta),
                "un uso no viene de una devolución");
        laBaseRechaza(() -> insertarMovimiento("ABONO", "0", devolucion, null),
                "un monto positivo");
    }
}

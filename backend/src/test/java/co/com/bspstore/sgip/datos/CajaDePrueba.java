package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.Cliente;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.modelo.TipoDocumento;
import co.com.bspstore.sgip.util.Seguridad;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Una empresa con todo lo que una venta del mostrador necesita, para las pruebas con base de la
 * caja: su administrador (con PIN), un vendedor, los medios de pago, el Consumidor final, un
 * cliente, productos con precio y un turno abierto por cajero. Se borra entera con
 * {@link EmpresaDePrueba#borrar}, de las hojas a la raíz.
 *
 * No pasa por los servicios de alta a propósito: escribe las filas con SQL para que la prueba
 * no dependa de lo que esos servicios hagan o dejen de hacer.
 */
final class CajaDePrueba {

    static final String PIN = "483920";

    private final JdbcClient jdbc;
    final EmpresaDePrueba empresa;
    final Integer administradorId;
    final Integer vendedorId;
    final Integer consumidorFinalId;
    final Integer clienteId;
    final Integer efectivoId;
    final Integer tarjetaId;
    final Integer transferenciaId;
    final Integer saldoAFavorId;
    final Integer inactivoId;
    final Integer sinProveedorId;
    final Integer sinCategoriaId;
    private final String marca;

    private CajaDePrueba(JdbcClient jdbc, EmpresaDePrueba empresa, Integer vendedorId, Integer consumidorFinalId,
                         Integer clienteId, Integer efectivoId, Integer tarjetaId, Integer transferenciaId,
                         Integer saldoAFavorId, Integer inactivoId, Integer sinProveedorId,
                         Integer sinCategoriaId) {
        this.jdbc = jdbc;
        this.empresa = empresa;
        this.administradorId = empresa.usuarioId;
        this.vendedorId = vendedorId;
        this.consumidorFinalId = consumidorFinalId;
        this.clienteId = clienteId;
        this.efectivoId = efectivoId;
        this.tarjetaId = tarjetaId;
        this.transferenciaId = transferenciaId;
        this.saldoAFavorId = saldoAFavorId;
        this.inactivoId = inactivoId;
        this.sinProveedorId = sinProveedorId;
        this.sinCategoriaId = sinCategoriaId;
        this.marca = empresa.marca;
    }

    static CajaDePrueba crear(String nombre, JdbcClient jdbc, RepositorioPlataforma plataforma,
                              CategoriaRepositorio categorias, ProveedorRepositorio proveedores,
                              RepositorioClientes clientes) {
        EmpresaDePrueba empresa = EmpresaDePrueba.crear(nombre, plataforma, jdbc, categorias, proveedores);
        String marca = empresa.marca.toLowerCase();
        jdbc.sql("UPDATE usuarios SET pin_hash = :hash WHERE empresa_id = :e AND usuario_id = :u")
                .param("hash", Seguridad.derivar(PIN)).param("e", empresa.empresaId)
                .param("u", empresa.usuarioId).update();
        jdbc.sql("""
                INSERT INTO usuarios (empresa_id, rol_id, nombre, correo, clave_hash, cambio_clave_req)
                VALUES (:e, (SELECT rol_id FROM roles WHERE nombre = 'VENDEDOR'),
                        'Vendedor', :correo, 'no-sirve-para-ingresar', 0)
                """).param("e", empresa.empresaId).param("correo", "vendedor-" + marca + "@ejemplo.co").update();
        Integer vendedor = jdbc.sql("SELECT usuario_id FROM usuarios WHERE correo = :c")
                .param("c", "vendedor-" + marca + "@ejemplo.co").query(Integer.class).single();

        Integer efectivo = medio(jdbc, empresa, "Efectivo", "EFECTIVO", true);
        Integer tarjeta = medio(jdbc, empresa, "Tarjeta", "TARJETA", true);
        Integer transferencia = medio(jdbc, empresa, "Transferencia", "TRANSFERENCIA", true);
        Integer saldo = medio(jdbc, empresa, "Saldo a favor", "SALDO_A_FAVOR", false);
        Integer inactivo = medio(jdbc, empresa, "Cheque", "TRANSFERENCIA", false);

        jdbc.sql("""
                INSERT INTO clientes (empresa_id, razon_social, tipo_documento, nit, ciudad, consumidor_final)
                VALUES (:e, 'Consumidor final', 'CC', '222222222222', 'Cali', 1)
                """).param("e", empresa.empresaId).update();
        Integer consumidor = jdbc.sql("""
                SELECT cliente_id FROM clientes WHERE empresa_id = :e AND consumidor_final = 1
                """).param("e", empresa.empresaId).query(Integer.class).single();
        Integer cliente = clientes.insertar(empresa.empresaId,
                new Cliente(null, "Cliente " + marca, TipoDocumento.CC, "9" + marca, null, "3001112233",
                        "Calle 1", "Cali", false), LocalDateTime.of(2026, 11, 1, 8, 0), "CAJA");
        // El proveedor y la categoría del producto que se crea dentro de la venta: el alta de
        // empresa los siembra, y esta empresa no pasa por el alta.
        KeyHolder sinProveedor = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO proveedores (empresa_id, razon_social, nit) VALUES (:e, 'Sin proveedor', 'SIN-PROVEEDOR')")
                .param("e", empresa.empresaId).update(sinProveedor);
        KeyHolder sinCategoria = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO categorias (empresa_id, nombre) VALUES (:e, 'Sin categoría')")
                .param("e", empresa.empresaId).update(sinCategoria);
        return new CajaDePrueba(jdbc, empresa, vendedor, consumidor, cliente, efectivo, tarjeta, transferencia,
                saldo, inactivo, sinProveedor.getKeyAs(Number.class).intValue(),
                sinCategoria.getKeyAs(Number.class).intValue());
    }

    private static Integer medio(JdbcClient jdbc, EmpresaDePrueba empresa, String nombre, String tipo, boolean activo) {
        KeyHolder llave = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO medios_pago (empresa_id, nombre, tipo, activo) VALUES (:e, :n, :t, :a)")
                .param("e", empresa.empresaId).param("n", nombre).param("t", tipo).param("a", activo).update(llave);
        return llave.getKeyAs(Number.class).intValue();
    }

    // ------------------------------------------------------------ quién entra

    void entrarComoAdministrador() {
        entrar(administradorId, "ADMINISTRADOR");
    }

    void entrarComoVendedor() {
        entrar(vendedorId, "VENDEDOR");
    }

    SecurityContext contextoDe(Integer usuario, String rol) {
        var u = new UsuarioAutenticado(usuario, "prueba@ejemplo.co", rol, empresa.empresaId);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        return c;
    }

    private void entrar(Integer usuario, String rol) {
        SecurityContextHolder.setContext(contextoDe(usuario, rol));
    }

    // ------------------------------------------------------------ turnos

    /** Abre un turno para el cajero, sin pasar por el servicio. */
    Long abrirTurno(Integer cajero) {
        KeyHolder llave = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO cierres_caja (empresa_id, usuario_id, apertura, base_efectivo)
                VALUES (:e, :u, '2026-11-12 08:00:00', 0)
                """).param("e", empresa.empresaId).param("u", cajero).update(llave);
        return llave.getKeyAs(Number.class).longValue();
    }

    void cerrarTurno(Long turno) {
        jdbc.sql("UPDATE cierres_caja SET cierre = apertura WHERE empresa_id = :e AND cierre_id = :id")
                .param("e", empresa.empresaId).param("id", turno).update();
    }

    // ------------------------------------------------------------ productos

    /** Un producto con la existencia y el precio de contado SIN IVA que se le dan, de costo 1000. */
    Integer producto(String letra, int existencia, String precio) {
        Integer id = insertarProducto(letra, existencia, new BigDecimal("1000.00"));
        for (String forma : List.of("CONTADO", "CREDITO")) {
            jdbc.sql("""
                    INSERT INTO reglas_margen (empresa_id, producto_id, forma_pago, forma_calculo, precio_fijo,
                                               vigente_desde)
                    VALUES (:e, :p, :forma, 'SOBRE_COSTO', :precio, '2026-01-01')
                    """).param("e", empresa.empresaId).param("p", id).param("forma", forma)
                    .param("precio", new BigDecimal(precio)).update();
        }
        return id;
    }

    /** Un producto cuyo precio sale de multiplicar el costo por el factor: sirve para precios límite. */
    Integer productoConFactor(String letra, int existencia, String costo, String factor) {
        Integer id = insertarProducto(letra, existencia, new BigDecimal(costo));
        for (String forma : List.of("CONTADO", "CREDITO")) {
            jdbc.sql("""
                    INSERT INTO reglas_margen (empresa_id, producto_id, forma_pago, forma_calculo, factor,
                                               vigente_desde)
                    VALUES (:e, :p, :forma, 'SOBRE_COSTO', :factor, '2026-01-01')
                    """).param("e", empresa.empresaId).param("p", id).param("forma", forma)
                    .param("factor", new BigDecimal(factor)).update();
        }
        return id;
    }

    /** Un producto activo al que ninguna regla le da precio. */
    Integer productoSinPrecio(String letra, int existencia) {
        return insertarProducto(letra, existencia, new BigDecimal("1000.00"));
    }

    private Integer insertarProducto(String letra, int existencia, BigDecimal costo) {
        String codigo = letra + "-" + marca;
        KeyHolder llave = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO productos (empresa_id, categoria_id, proveedor_id, codigo, sku, nombre, costo,
                                       existencia, existencia_minima)
                VALUES (:e, :c, :pr, :codigo, :codigo, :nombre, :costo, :existencia, 0)
                """)
                .param("e", empresa.empresaId).param("c", empresa.categoriaId).param("pr", empresa.proveedorId)
                .param("codigo", codigo).param("nombre", "Producto " + letra).param("costo", costo)
                .param("existencia", existencia).update(llave);
        Integer id = llave.getKeyAs(Number.class).intValue();
        if (existencia != 0) {
            // El kárdex arranca explicando la existencia con la que nace el producto.
            jdbc.sql("""
                    INSERT INTO movimientos_inventario (empresa_id, producto_id, usuario_id, tipo, cantidad,
                                                        existencia_anterior, existencia_posterior, motivo)
                    VALUES (:e, :p, :u, :tipo, :cantidad, 0, :existencia, 'Saldo inicial del producto')
                    """)
                    .param("e", empresa.empresaId).param("p", id).param("u", administradorId)
                    .param("tipo", existencia > 0 ? "INGRESO" : "AJUSTE").param("cantidad", Math.abs(existencia))
                    .param("existencia", existencia).update();
        }
        return id;
    }

    /** La empresa mal sembrada: sin el proveedor ni la categoría del producto nuevo. */
    void quitarSinProveedorYSinCategoria() {
        jdbc.sql("DELETE FROM proveedores WHERE empresa_id = :e AND razon_social = 'Sin proveedor'")
                .param("e", empresa.empresaId).update();
        jdbc.sql("DELETE FROM categorias WHERE empresa_id = :e AND nombre = 'Sin categoría'")
                .param("e", empresa.empresaId).update();
    }

    void desactivarProducto(Integer id) {
        jdbc.sql("UPDATE productos SET activo = 0 WHERE empresa_id = :e AND producto_id = :id")
                .param("e", empresa.empresaId).param("id", id).update();
    }

    /** La empresa responsable de IVA, con el 19 % para todos sus productos. */
    void responsableDeIva19() {
        jdbc.sql("""
                INSERT INTO empresa_config (empresa_id, responsable_iva, aplica_exclusion_umbral)
                VALUES (:e, TRUE, FALSE)
                ON DUPLICATE KEY UPDATE responsable_iva = TRUE, aplica_exclusion_umbral = FALSE
                """).param("e", empresa.empresaId).update();
        jdbc.sql("""
                INSERT INTO reglas_iva (empresa_id, tipo_impuesto_id, vigente_desde)
                SELECT :e, tipo_impuesto_id, '2026-01-01' FROM tipos_impuesto WHERE codigo = 'IVA_19'
                """).param("e", empresa.empresaId).update();
    }

    /** Para pasar por los filtros de la API: una empresa en prueba con la prueba vigente. */
    void conPruebaVigente() {
        jdbc.sql("""
                INSERT INTO suscripciones (empresa_id, plan, inicio_prueba, fin_prueba, estado)
                VALUES (:e, 'BASICO', :inicio, :fin, 'PRUEBA')
                """).param("e", empresa.empresaId).param("inicio", LocalDate.now().minusDays(1))
                .param("fin", LocalDate.now().plusDays(20)).update();
    }

    // ------------------------------------------------------------ lo que se lee

    int existencia(Integer producto) {
        return jdbc.sql("SELECT existencia FROM productos WHERE empresa_id = :e AND producto_id = :p")
                .param("e", empresa.empresaId).param("p", producto).query(Integer.class).single();
    }

    int cuantas(String tabla) {
        return jdbc.sql("SELECT COUNT(*) FROM " + tabla + " WHERE empresa_id = :e")
                .param("e", empresa.empresaId).query(Integer.class).single();
    }

    int movimientosDe(Integer producto, String tipo) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM movimientos_inventario
                 WHERE empresa_id = :e AND producto_id = :p AND tipo = :t
                """).param("e", empresa.empresaId).param("p", producto).param("t", tipo)
                .query(Integer.class).single();
    }

    void borrar() {
        jdbc.sql("DELETE FROM auditoria_accesos WHERE correo LIKE :patron")
                .param("patron", "%" + marca.toLowerCase() + "%").update();
        empresa.borrar(jdbc);
    }
}

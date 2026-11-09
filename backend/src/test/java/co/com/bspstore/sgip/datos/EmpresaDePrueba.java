package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.modelo.Categoria;
import co.com.bspstore.sgip.modelo.Proveedor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

/**
 * Una empresa con su administrador, una categoría y un proveedor, para las
 * pruebas con base que necesitan datos propios. Se borra entera al terminar,
 * de las hojas hacia la raíz, de modo que no depende de la demostración ni del
 * orden de las pruebas.
 */
final class EmpresaDePrueba {

    private static final List<String> TABLAS = List.of("recepciones", "orden_compra_items",
            "ordenes_compra", "historico_costos", "detalle_carga_precios", "cargas_precios",
            "movimientos_inventario", "reglas_iva", "reglas_margen", "productos", "proveedores",
            "categorias", "usuarios", "empresa_config", "empresas");

    final Integer empresaId;
    final Integer usuarioId;
    final Integer categoriaId;
    final Integer proveedorId;
    final String marca;

    private EmpresaDePrueba(Integer empresaId, Integer usuarioId, Integer categoriaId,
                            Integer proveedorId, String marca) {
        this.empresaId = empresaId;
        this.usuarioId = usuarioId;
        this.categoriaId = categoriaId;
        this.proveedorId = proveedorId;
        this.marca = marca;
    }

    static EmpresaDePrueba crear(String nombre, RepositorioPlataforma plataforma, JdbcClient jdbc,
                                 CategoriaRepositorio categorias, ProveedorRepositorio proveedores) {
        String marca = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        Integer empresa = plataforma.insertarEmpresa("E" + marca, nombre + " " + marca,
                null, null, null);
        jdbc.sql("""
                INSERT INTO usuarios (empresa_id, rol_id, nombre, correo, clave_hash)
                VALUES (:empresa, (SELECT rol_id FROM roles WHERE nombre = 'ADMINISTRADOR'),
                        'Prueba', :correo, 'no-sirve-para-ingresar')
                """)
                .param("empresa", empresa)
                .param("correo", "prueba-" + marca.toLowerCase() + "@ejemplo.co")
                .update();
        Integer usuario = jdbc.sql("SELECT usuario_id FROM usuarios WHERE empresa_id = :empresa")
                .param("empresa", empresa)
                .query(Integer.class).single();
        Integer categoria = categorias.save(new Categoria(empresa, "Prueba", null)).getId();
        Integer proveedor = proveedores.save(new Proveedor(empresa, "Proveedor", "P" + marca)).getId();
        return new EmpresaDePrueba(empresa, usuario, categoria, proveedor, marca);
    }

    /** Deja a este hilo autenticado como el administrador de la empresa. */
    void entrar() {
        var u = new UsuarioAutenticado(usuarioId, "prueba@ejemplo.co", "ADMINISTRADOR", empresaId);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
    }

    void borrar(JdbcClient jdbc) {
        SecurityContextHolder.clearContext();
        for (String tabla : TABLAS) {
            jdbc.sql("DELETE FROM " + tabla + " WHERE empresa_id = :empresa")
                    .param("empresa", empresaId)
                    .update();
        }
    }
}

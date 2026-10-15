package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.ProductoEnCatalogo;
import co.com.bspstore.sgip.api.dto.SolicitudProducto;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.datos.CategoriaRepositorio;
import co.com.bspstore.sgip.datos.ConsultaCatalogo;
import co.com.bspstore.sgip.datos.ProductoRepositorio;
import co.com.bspstore.sgip.modelo.Producto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class ServicioProducto {

    /** Un listado sin tope puede traer el inventario entero a la memoria. */
    private static final int LIMITE_MAXIMO = 200;

    private final ProductoRepositorio productos;
    private final CategoriaRepositorio categorias;
    private final ConsultaCatalogo catalogo;

    public ServicioProducto(ProductoRepositorio productos,
                            CategoriaRepositorio categorias,
                            ConsultaCatalogo catalogo) {
        this.productos = productos;
        this.categorias = categorias;
        this.catalogo = catalogo;
    }

    @Transactional(readOnly = true)
    public List<ProductoEnCatalogo> listar(String busqueda, Integer limite) {
        int tope = (limite == null || limite <= 0) ? 50 : Math.min(limite, LIMITE_MAXIMO);
        return catalogo.listar(ContextoEmpresa.empresaActual(), busqueda, tope);
    }

    @Transactional(readOnly = true)
    public Optional<ProductoEnCatalogo> buscar(Integer productoId) {
        return catalogo.buscar(ContextoEmpresa.empresaActual(), productoId);
    }

    @Transactional(readOnly = true)
    public Optional<ProductoEnCatalogo> porCodigoBarras(String codigoBarras) {
        if (codigoBarras == null || codigoBarras.isBlank()) {
            return Optional.empty();
        }
        return catalogo.porCodigoBarras(ContextoEmpresa.empresaActual(), codigoBarras.trim());
    }

    @Transactional
    public Producto crear(SolicitudProducto s) {
        Integer empresa = ContextoEmpresa.empresaActual();

        // La categoría se comprueba contra la empresa del contexto, no
        // contra su sola existencia: si bastara con que existiera, un
        // comercio podría colgar sus productos de la categoría de otro y
        // la clave foránea compuesta de la V11 rechazaría la fila con un
        // error de motor en vez de con un mensaje entendible.
        categorias.findByEmpresaIdAndId(empresa, s.categoriaId())
                .orElseThrow(() -> new ReferenciaAjenaException(
                        "La categoría indicada no existe en esta empresa."));

        String codigo = normalizar(s.codigo());
        // Si no dan SKU, se usa el código. Es lo mismo que hizo la V10 con
        // las filas que ya existían cuando la columna pasó a obligatoria.
        String sku = (s.sku() == null || s.sku().isBlank()) ? codigo : normalizar(s.sku());

        if (productos.existsByEmpresaIdAndCodigo(empresa, codigo)) {
            throw new NombreRepetidoException("Ya existe un producto con el código " + codigo + ".");
        }
        if (productos.existsByEmpresaIdAndSku(empresa, sku)) {
            throw new NombreRepetidoException("Ya existe un producto con el SKU " + sku + ".");
        }

        Producto p = new Producto(empresa, s.categoriaId(), s.proveedorId(),
                codigo, sku, s.nombre(), s.costo());
        p.describir(s.presentacion(), s.descripcion(), s.codigoBarras());
        p.fijarExistencias(
                s.existencia() == null ? 0 : s.existencia(),
                s.existenciaMinima() == null ? 5 : s.existenciaMinima());
        return productos.save(p);
    }

    private static String normalizar(String s) {
        return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
    }
}

package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.ProductoEnCatalogo;
import co.com.bspstore.sgip.api.dto.SolicitudEdicionProducto;
import co.com.bspstore.sgip.api.dto.SolicitudProducto;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.datos.CategoriaRepositorio;
import co.com.bspstore.sgip.datos.ConsultaCatalogo;
import co.com.bspstore.sgip.datos.ProductoRepositorio;
import co.com.bspstore.sgip.datos.ProveedorRepositorio;
import co.com.bspstore.sgip.modelo.Producto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
public class ServicioProducto {

    /** Un listado sin tope puede traer el inventario entero a la memoria. */
    private static final int LIMITE_MAXIMO = 200;

    private final ProductoRepositorio productos;
    private final CategoriaRepositorio categorias;
    private final ProveedorRepositorio proveedores;
    private final ConsultaCatalogo catalogo;

    public ServicioProducto(ProductoRepositorio productos,
                            CategoriaRepositorio categorias,
                            ProveedorRepositorio proveedores,
                            ConsultaCatalogo catalogo) {
        this.productos = productos;
        this.categorias = categorias;
        this.proveedores = proveedores;
        this.catalogo = catalogo;
    }

    /** Los estados que la columna generada estado_stock puede tomar. */
    private static final Set<String> ESTADOS = Set.of("ACTIVO", "CRITICO", "AGOTADO");

    @Transactional(readOnly = true)
    public List<ProductoEnCatalogo> listar(String busqueda, Integer categoriaId,
                                           Integer proveedorId, String estadoStock,
                                           Integer limite) {
        int tope = (limite == null || limite <= 0) ? 50 : Math.min(limite, LIMITE_MAXIMO);
        return catalogo.listar(ContextoEmpresa.empresaActual(), busqueda,
                categoriaId, proveedorId, normalizarEstado(estadoStock), tope);
    }

    /**
     * Un estado que no existe se rechaza en vez de ignorarse.
     *
     * Ignorarlo devolvería el catálogo entero, y quien escribió
     * «AGOTADOS» en lugar de «AGOTADO» leería esa respuesta como que no
     * hay productos agotados. Un filtro que falla en silencio miente.
     */
    private static String normalizarEstado(String estadoStock) {
        if (estadoStock == null || estadoStock.isBlank()) {
            return null;
        }
        String limpio = estadoStock.trim().toUpperCase(Locale.ROOT);
        if (!ESTADOS.contains(limpio)) {
            throw new FiltroInvalidoException(
                    "El estado de existencia «" + estadoStock.trim() + "» no existe. "
                            + "Los valores admitidos son ACTIVO, CRITICO y AGOTADO.");
        }
        return limpio;
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

        // El proveedor se comprueba igual que la categoría. Quedó fuera en
        // la primera versión de este servicio, de modo que un producto
        // podía nacer apuntando al proveedor de otro comercio y el único
        // que lo frenaba era fk_productos_proveedor_emp, con un error de
        // motor en lugar de un mensaje.
        proveedores.findByEmpresaIdAndId(empresa, s.proveedorId())
                .orElseThrow(() -> new ReferenciaAjenaException(
                        "El proveedor indicado no existe en esta empresa."));

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

    /**
     * Edita un producto registrado (RF-15).
     *
     * Repite las comprobaciones de la creación porque la edición puede
     * mover el producto a otra categoría o a otro proveedor, y nada
     * impide que el identificador que llegue sea de otro comercio. Un
     * servicio que solo valida al crear deja abierta por la puerta de
     * atrás la misma fuga que cerró por la de delante.
     *
     * La existencia no se toca: la solicitud de edición ni siquiera la
     * trae, y el motivo está en Producto.reemplazarDatos.
     */
    @Transactional
    public Optional<Producto> actualizar(Integer productoId, SolicitudEdicionProducto s) {
        Integer empresa = ContextoEmpresa.empresaActual();

        Optional<Producto> encontrado = productos.findByEmpresaIdAndId(empresa, productoId);
        if (encontrado.isEmpty()) {
            return Optional.empty();
        }
        Producto p = encontrado.get();

        categorias.findByEmpresaIdAndId(empresa, s.categoriaId())
                .orElseThrow(() -> new ReferenciaAjenaException(
                        "La categoría indicada no existe en esta empresa."));
        proveedores.findByEmpresaIdAndId(empresa, s.proveedorId())
                .orElseThrow(() -> new ReferenciaAjenaException(
                        "El proveedor indicado no existe en esta empresa."));

        String codigo = normalizar(s.codigo());
        String sku = (s.sku() == null || s.sku().isBlank()) ? codigo : normalizar(s.sku());

        if (productos.existsByEmpresaIdAndCodigoAndIdNot(empresa, codigo, productoId)) {
            throw new NombreRepetidoException("Ya existe un producto con el código " + codigo + ".");
        }
        if (productos.existsByEmpresaIdAndSkuAndIdNot(empresa, sku, productoId)) {
            throw new NombreRepetidoException("Ya existe un producto con el SKU " + sku + ".");
        }

        p.reemplazarDatos(s.categoriaId(), s.proveedorId(), codigo, sku, s.nombre(),
                s.presentacion(), s.descripcion(), s.codigoBarras(), s.costo(),
                s.existenciaMinima() == null ? p.getExistenciaMinima() : s.existenciaMinima());
        return Optional.of(productos.save(p));
    }

    private static String normalizar(String s) {
        return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
    }
}

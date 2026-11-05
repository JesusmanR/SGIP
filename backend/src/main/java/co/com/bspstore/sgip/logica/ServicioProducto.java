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
    private final ServicioInventario inventario;
    private final ServicioPrecios precios;

    public ServicioProducto(ProductoRepositorio productos,
                            CategoriaRepositorio categorias,
                            ProveedorRepositorio proveedores,
                            ConsultaCatalogo catalogo,
                            ServicioInventario inventario,
                            ServicioPrecios precios) {
        this.productos = productos;
        this.categorias = categorias;
        this.proveedores = proveedores;
        this.catalogo = catalogo;
        this.inventario = inventario;
        this.precios = precios;
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
        String barras = normalizarCodigoBarras(s.codigoBarras());
        if (barras != null && productos.existsByEmpresaIdAndCodigoBarras(empresa, barras)) {
            throw new NombreRepetidoException(
                    "Ya existe un producto con el código de barras " + barras + ".");
        }
        precios.exigirPrecioDeAlta(s.costo(), s.contado(), s.credito());

        Producto p = new Producto(empresa, s.categoriaId(), s.proveedorId(),
                codigo, sku, s.nombre(), s.costo());
        p.describir(s.presentacion(), s.descripcion(), barras);
        p.fijarExistenciaMinima(s.existenciaMinima() == null ? 5 : s.existenciaMinima());
        Producto guardado = productos.save(p);
        precios.escribirPrecios(empresa, guardado.getId(), s.costo(), s.contado(), s.credito(), null);

        // El producto nace en cero y el kárdex lo sube, dentro de esta misma
        // transacción: si el movimiento falla, el alta se deshace entera
        // (RF-49: no hay existencia sin su movimiento).
        int inicial = s.existencia() == null ? 0 : s.existencia();
        if (inicial > 0) {
            inventario.registrarSaldoInicial(guardado.getId(), inicial)
                    .orElseThrow(() -> new IllegalStateException(
                            "El producto " + guardado.getId()
                                    + " no se encontró justo después de guardarlo."));
        }
        return guardado;
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
     *
     * El precio solo cambia si alguien lo escribió. Cambiar el costo no escribe
     * ninguna regla, y por eso se conserva lo que se escribió en su día: con un
     * porcentaje cambia el precio y con un precio cambia el margen (ADR-0025).
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
        String barras = normalizarCodigoBarras(s.codigoBarras());
        if (barras != null
                && productos.existsByEmpresaIdAndCodigoBarrasAndIdNot(empresa, barras, productoId)) {
            throw new NombreRepetidoException(
                    "Ya existe un producto con el código de barras " + barras + ".");
        }
        precios.comprobarPrecios(s.costo(), s.contado(), s.credito(), s.creditoIgualAlContado());

        p.reemplazarDatos(s.categoriaId(), s.proveedorId(), codigo, sku, s.nombre(),
                s.presentacion(), s.descripcion(), barras, s.costo(),
                s.existenciaMinima() == null ? p.getExistenciaMinima() : s.existenciaMinima());
        Producto guardado = productos.save(p);
        // Sin tocar `version`: la regla es una fila aparte, y un cambio solo de
        // precio no le quita a nadie los datos del producto que tenía abiertos.
        precios.escribirPrecios(empresa, productoId, s.costo(), s.contado(), s.credito(),
                s.creditoIgualAlContado());
        return Optional.of(guardado);
    }

    /**
     * Sin espacios al borde y, si queda vacío, nulo. No pasa a mayúsculas:
     * el lector entrega el código tal cual está impreso y, a diferencia
     * del código interno, no es un nombre que la persona escribe.
     *
     * El nulo importa más que el recorte: la clave única de la V33 deja
     * pasar todos los NULL que haga falta, pero dos cadenas vacías
     * chocarían entre sí, de modo que «sin etiqueta» no puede guardarse
     * como «».
     */
    private static String normalizarCodigoBarras(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private static String normalizar(String s) {
        return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
    }
}

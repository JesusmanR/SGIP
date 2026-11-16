package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.ProductoNuevo;
import co.com.bspstore.sgip.api.dto.SolicitudPrecio;
import co.com.bspstore.sgip.datos.RepositorioConsecutivos;
import co.com.bspstore.sgip.datos.RepositorioProductoNuevo;
import co.com.bspstore.sgip.datos.RepositorioProductoNuevo.NuevoProducto;
import co.com.bspstore.sgip.datos.RepositorioProductoNuevo.SinProveedorYSinCategoria;
import co.com.bspstore.sgip.modelo.TipoConsecutivo;
import co.com.bspstore.sgip.util.Hoy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;

/**
 * Crea el producto que no está en el catálogo y se vende en el acto (RF-42). Lo llama
 * {@link RegistradorDeVenta} dentro de la transacción de la venta; no hay endpoint aparte, de modo
 * que un producto nuevo nunca existe sin su venta, y si la venta falla no queda ni el producto, ni
 * su regla de precio, ni el número de su código.
 *
 * <h2>Lo que nace</h2>
 *
 * <ul>
 *   <li>Código {@code RAP-<n>}, con {@code n} del consecutivo {@code PRODUCTO_RAPIDO} de la empresa y
 *       sin ceros a la izquierda. Si otro producto ya tiene ese código (alguien lo escribió a mano) se
 *       salta al siguiente número. El SKU es el mismo código.</li>
 *   <li>Costo de {@value #COSTO_PROVISIONAL_TEXTO} y {@code costo_provisional = TRUE}: la columna exige
 *       un costo mayor que cero y el administrador lo corrige cuando lo sepa; editar el costo o
 *       aplicar una lista de precios apaga la marca.</li>
 *   <li>Existencia cero: su venta es un encargo.</li>
 *   <li>«Sin proveedor» y «Sin categoría» de la empresa. <b>No se crean aquí</b>: los siembran la V45 y
 *       el alta de la empresa. Si faltan, la empresa está mal sembrada y es un error de datos (500 con
 *       un registro que dice qué empresa), no algo que la venta repare a mitad de camino.</li>
 *   <li>Su precio, con una regla de precio fijo propia (ADR-0025) escrita por
 *       {@link ServicioPrecios#escribirPrecios}: la misma ruta del catálogo, para contado y crédito. El
 *       precio es SIN IVA, igual que el de la vista.</li>
 * </ul>
 *
 * <h2>Bloqueos</h2>
 *
 * El orden de la caja es turno, venta, productos (por id), saldo del cliente, consecutivo y kárdex.
 * Aquí se toma el consecutivo {@code PRODUCTO_RAPIDO}, que va antes que el de {@code VENTA} (el orden
 * global es {@code PRODUCTO_RAPIDO}, {@code VENTA}, {@code DEVOLUCION}); {@link RegistradorDeVenta} ya
 * lo tiene bloqueado cuando llama (lo toma para esperar a su hermana de la misma clave), de modo que
 * aquí no espera. Lo demás que se escribe es de una fila que nació dentro de esta transacción y que
 * nadie más puede haber bloqueado: el producto, su bloqueo de fila en {@code escribirPrecios} y sus
 * reglas. Las claves foráneas hacia «Sin proveedor» y «Sin categoría» toman un bloqueo compartido de
 * esas dos filas, que solo estorba a quien las edite, y esa edición no espera nada que esta venta
 * tenga, de modo que no hay ciclo. {@code escribirPrecios} no toma el bloqueo de la fila de empresa
 * (lo toman {@code crear}, {@code actualizar} y {@code reemplazar} de las reglas de ámbito, que esta
 * venta no usa).
 *
 * Cada producto nuevo cuesta un número fijo de consultas (su código, sus comprobaciones, su fila y su
 * precio) y el resto de la venta no cambia con él; por eso una venta admite a lo sumo
 * {@link co.com.bspstore.sgip.logica.venta.LimitesDeVenta#PRODUCTOS_NUEVOS}.
 */
@Service
public class ServicioProductoNuevo {

    private static final Logger LOG = LoggerFactory.getLogger(ServicioProductoNuevo.class);

    static final String COSTO_PROVISIONAL_TEXTO = "1,00";
    public static final BigDecimal COSTO_PROVISIONAL = new BigDecimal("1.00");

    /** Cuántos códigos ocupados a mano se saltan antes de rendirse. */
    private static final int INTENTOS_DE_CODIGO = 50;

    static final String CODIGO_DE_BARRAS_REPETIDO = "Ya existe un producto con ese código de barras.";

    /** El producto recién creado: lo que la venta necesita para ponerlo en su línea. */
    public record Creado(Integer id, String codigo, String nombre, BigDecimal costo) {}

    private final RepositorioProductoNuevo productos;
    private final RepositorioConsecutivos consecutivos;
    private final ServicioPrecios precios;
    private final Clock reloj;

    public ServicioProductoNuevo(RepositorioProductoNuevo productos, RepositorioConsecutivos consecutivos,
                                 ServicioPrecios precios, Clock reloj) {
        this.productos = productos;
        this.consecutivos = consecutivos;
        this.precios = precios;
        this.reloj = reloj;
    }

    /**
     * Crea el producto de la empresa y devuelve sus datos. Tiene que llamarse dentro de la transacción
     * de la venta ({@link Propagation#MANDATORY}). La autorización la exige quien llama, antes de
     * llamar: sin ella no se debe crear nada.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Creado crear(Integer empresaId, ProductoNuevo pedido) {
        ProductoNuevo p = NormalizadorDeVenta.productoNuevo(pedido);
        SinProveedorYSinCategoria genericos = productos.buscarSinProveedorYSinCategoria(empresaId);
        if (genericos.proveedorId() == null || genericos.categoriaId() == null) {
            LOG.error("La empresa {} no tiene su «Sin proveedor» o su «Sin categoría» (proveedor: {}, categoría: {}): "
                    + "ni la V45 ni el alta los sembraron.", empresaId, genericos.proveedorId() != null,
                    genericos.categoriaId() != null);
            throw new IllegalStateException("La empresa " + empresaId
                    + " no tiene su «Sin proveedor» o su «Sin categoría»: ni el alta ni la V45 los sembraron.");
        }
        if (p.codigoBarras() != null && productos.existeCodigoDeBarras(empresaId, p.codigoBarras())) {
            throw new NombreRepetidoException(CODIGO_DE_BARRAS_REPETIDO);
        }
        String codigo = siguienteCodigo(empresaId);
        Integer id = ServicioProducto.traduciendoElChoqueDelCodigoDeBarras(() -> productos.insertar(new NuevoProducto(
                empresaId, genericos.categoriaId(), genericos.proveedorId(), codigo, p.nombre(), p.codigoBarras(),
                COSTO_PROVISIONAL)));
        // La misma ruta del catálogo: una regla de precio fijo propia para el contado y, igual, para el crédito.
        precios.escribirPrecios(empresaId, id, COSTO_PROVISIONAL, new SolicitudPrecio(null, p.precioVenta()), null,
                null, Hoy.de(reloj));
        return new Creado(id, codigo, p.nombre(), COSTO_PROVISIONAL);
    }

    private String siguienteCodigo(Integer empresaId) {
        for (int intento = 0; intento < INTENTOS_DE_CODIGO; intento++) {
            String codigo = "RAP-" + consecutivos.tomarSiguiente(empresaId, TipoConsecutivo.PRODUCTO_RAPIDO);
            if (!productos.existeCodigo(empresaId, codigo)) {
                return codigo;
            }
        }
        LOG.error("La empresa {} tiene ocupados {} códigos rápidos seguidos.", empresaId, INTENTOS_DE_CODIGO);
        throw new VentaInvalidaException("No se pudo asignar un código al producto nuevo: hay demasiados códigos "
                + "«RAP-» ocupados. Avisa al administrador.");
    }
}

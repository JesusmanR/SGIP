package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.ProductoEnCatalogo;
import co.com.bspstore.sgip.api.dto.ProductoParaCaja;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.datos.ConsultaCatalogo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Buscar productos desde la caja (RF-31, RF-32, RNF-03): por código de barras, por código
 * interno y por texto, y siempre sin el costo.
 *
 * Lee de {@link ConsultaCatalogo}, que consulta la vista de precios (solo productos activos)
 * con el filtro de empresa de la sesión; la caja no hace ninguna consulta propia a la vista.
 * El precio es el de contado <b>sin IVA</b> que calculó la regla, y lo único que el servicio
 * decide es si el producto se puede vender (véase {@link #mapear}).
 *
 * <h2>Consultas</h2>
 *
 * Por código cuesta una consulta si es un código de barras y dos si es un código interno o no
 * existe; por texto, una. Ninguna depende de cuántos productos haya. Primero se prueba el
 * código de barras porque es lo que lee la pistola, y el interno solo si no hay coincidencia.
 */
@Service
public class ServicioCajaProductos {

    /** Lo más que devuelve una búsqueda por texto, sea cual sea el límite que se pida. */
    static final int LIMITE_MAXIMO = 20;
    static final int TEXTO_MAXIMO = 100;
    /** Lo que cabe en {@code productos.codigo} y {@code productos.codigo_barras}. */
    static final int CODIGO_MAXIMO = 60;

    /** Lo que cabe en {@code DECIMAL(14,2)} sin decimales y sin pasar de 12 cifras enteras. */
    private static final BigDecimal PRECIO_MAXIMO = new BigDecimal("999999999999");

    static final String SIN_PRECIO = "Este producto no tiene precio de venta";

    private final ConsultaCatalogo catalogo;

    public ServicioCajaProductos(ConsultaCatalogo catalogo) {
        this.catalogo = catalogo;
    }

    /**
     * El producto activo de la empresa cuyo código de barras o, si no hay ninguno, cuyo código
     * interno es {@code codigo}. Vacío si no existe, está inactivo o es de otra empresa: las
     * tres se ven igual desde fuera. 400 ({@link FiltroInvalidoException}) si el código está
     * en blanco o no cabe en la columna.
     */
    @Transactional(readOnly = true)
    public Optional<ProductoParaCaja> porCodigo(String codigo) {
        String limpio = codigo == null ? "" : codigo.trim();
        if (limpio.isEmpty()) {
            throw new FiltroInvalidoException("El código del producto no puede estar vacío.");
        }
        if (limpio.length() > CODIGO_MAXIMO) {
            throw new FiltroInvalidoException("El código del producto es demasiado largo.");
        }
        Integer empresa = ContextoEmpresa.empresaActual();
        Optional<ProductoEnCatalogo> encontrado = catalogo.porCodigoBarras(empresa, limpio);
        if (encontrado.isEmpty()) {
            encontrado = catalogo.porCodigo(empresa, limpio);
        }
        return encontrado.map(ServicioCajaProductos::mapear);
    }

    /**
     * Los productos activos cuyo nombre, código o SKU contienen el texto, por nombre. Devuelve
     * a lo sumo {@value #LIMITE_MAXIMO}: un límite menor o igual a cero pide el máximo, y uno
     * mayor se recorta. 400 si el texto está en blanco o pasa de {@value #TEXTO_MAXIMO} caracteres.
     */
    @Transactional(readOnly = true)
    public List<ProductoParaCaja> buscar(String texto, int limite) {
        String limpio = texto == null ? "" : texto.trim();
        if (limpio.isEmpty()) {
            throw new FiltroInvalidoException("El texto a buscar no puede estar vacío.");
        }
        if (limpio.length() > TEXTO_MAXIMO) {
            throw new FiltroInvalidoException("El texto a buscar admite hasta " + TEXTO_MAXIMO + " caracteres.");
        }
        int tope = (limite <= 0) ? LIMITE_MAXIMO : Math.min(limite, LIMITE_MAXIMO);
        return catalogo.listar(ContextoEmpresa.empresaActual(), limpio, null, null, null, tope).stream()
                .map(ServicioCajaProductos::mapear)
                .toList();
    }

    /**
     * Lista los campos de la caja uno a uno, sin copiar el registro del catálogo (que trae el
     * costo, el margen y el proveedor).
     *
     * Un producto es vendible si tiene un precio de contado mayor que cero y que cabe en la
     * venta (hasta 12 cifras enteras, lo que admiten las columnas de dinero). Sin precio, con
     * precio cero o con uno que no cabe no se puede poner una línea: la ficha se devuelve igual,
     * con {@code vendible = false}, para que quien vende vea que el producto existe y por qué
     * no se puede cobrar. El mismo texto sirve para los tres casos: para el mostrador, un
     * precio que no se puede cobrar es un producto sin precio de venta.
     */
    private static ProductoParaCaja mapear(ProductoEnCatalogo p) {
        BigDecimal precio = p.precioContado() == null ? null : p.precioContado().setScale(0, RoundingMode.HALF_UP);
        boolean vendible = precio != null
                && precio.signum() > 0
                && precio.compareTo(PRECIO_MAXIMO) <= 0;
        return new ProductoParaCaja(
                p.productoId(),
                p.codigo(),
                p.nombre(),
                p.presentacion(),
                p.codigoBarras(),
                p.existencia() == null ? 0 : p.existencia(),
                precio,
                p.tarifaContado(),
                p.tratamientoIva(),
                vendible,
                vendible ? null : SIN_PRECIO);
    }
}

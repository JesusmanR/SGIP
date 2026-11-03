package co.com.bspstore.sgip.api.dto;

import java.math.BigDecimal;

/**
 * El producto tal como lo ve quien vende: todo lo de {@link ProductoEnCatalogo}
 * salvo el costo, el proveedor y su identificador.
 *
 * Conserva {@code baseContado} y {@code baseCredito}: el RF-32 pide que la
 * pantalla diga qué base de cálculo se aplicó, y decir «sobre costo» o
 * «sobre precio» no revela la cifra del costo.
 *
 * {@link #de(ProductoEnCatalogo)} lista los campos permitidos uno a uno, y
 * no copia el registro completo para quitarle tres: así un campo nuevo en
 * {@code ProductoEnCatalogo} no llega a quien vende hasta que alguien lo
 * añada aquí a conciencia. Este es el sentido de la lista, no un descuido.
 */
public record ProductoParaVenta(
        Integer productoId,
        String codigo,
        String sku,
        String nombre,
        String presentacion,
        String descripcion,
        String codigoBarras,
        Integer categoriaId,
        String categoria,
        Integer existencia,
        Integer existenciaMinima,
        String estadoStock,
        BigDecimal precioContado,
        BigDecimal precioCredito,
        String tratamientoIva,
        BigDecimal tarifaContado,
        BigDecimal totalContado,
        BigDecimal totalCredito,
        String baseContado,
        String baseCredito,
        Integer tributacionSinConfigurar,
        Integer cercaUmbral,
        Integer cercaUmbralCredito,
        Integer cruzaUmbralEnCredito,
        Integer imagenPrincipalId,
        Integer tieneImagen
) implements VistaDeProducto {

    public static ProductoParaVenta de(ProductoEnCatalogo p) {
        return new ProductoParaVenta(
                p.productoId(),
                p.codigo(),
                p.sku(),
                p.nombre(),
                p.presentacion(),
                p.descripcion(),
                p.codigoBarras(),
                p.categoriaId(),
                p.categoria(),
                p.existencia(),
                p.existenciaMinima(),
                p.estadoStock(),
                p.precioContado(),
                p.precioCredito(),
                p.tratamientoIva(),
                p.tarifaContado(),
                p.totalContado(),
                p.totalCredito(),
                p.baseContado(),
                p.baseCredito(),
                p.tributacionSinConfigurar(),
                p.cercaUmbral(),
                p.cercaUmbralCredito(),
                p.cruzaUmbralEnCredito(),
                p.imagenPrincipalId(),
                p.tieneImagen());
    }
}

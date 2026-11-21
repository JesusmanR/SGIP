package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.modelo.TipoMedio;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Lo que el recibo lee de la venta (RF-70). */
@Repository
public class RepositorioRecibos {

    private final JdbcClient jdbc;

    public RepositorioRecibos(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record CabeceraDeRecibo(String empresa, String nit, boolean responsableIva, String consecutivo,
                                   LocalDateTime fecha, String cajero, String cliente, String tipoDocumento,
                                   String identificacion, BigDecimal subtotal, BigDecimal descuento, BigDecimal iva,
                                   BigDecimal total, String estadoPago, boolean contraEntrega, boolean domicilio,
                                   String direccion, String ciudad, String telefono, String recibe) {}

    public record LineaDeVenta(String nombre, int cantidad, BigDecimal precioUnitario, BigDecimal descuento,
                               BigDecimal iva, BigDecimal total, int cantidadEncargo, LocalDate entregaEstimada) {}

    public record PagoDeVenta(String medio, TipoMedio tipo, BigDecimal monto, BigDecimal recibido,
                              String referencia) {}

    public Optional<CabeceraDeRecibo> cabecera(Integer empresaId, Long ventaId) {
        throw new UnsupportedOperationException("pendiente");
    }

    public List<LineaDeVenta> lineas(Integer empresaId, Long ventaId) {
        throw new UnsupportedOperationException("pendiente");
    }

    public List<PagoDeVenta> pagos(Integer empresaId, Long ventaId) {
        throw new UnsupportedOperationException("pendiente");
    }
}

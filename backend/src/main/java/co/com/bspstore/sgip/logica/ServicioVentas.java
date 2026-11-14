package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.api.dto.VentaRegistrada;
import co.com.bspstore.sgip.util.Hoy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Registrar una venta en el mostrador (RF-41): la fachada. <b>No es transaccional</b>: valida la
 * forma de la solicitud, deduce de la carga qué excepciones hay que autorizar, valida el PIN del
 * administrador y solo entonces llama a {@link RegistradorDeVenta}, que abre la transacción de la
 * venta. Son dos beans para que el proxy aplique la transacción, y la autorización va antes por una
 * razón de fondo (ADR-0032): {@link ServicioAutorizacion#autorizar} corre en su propia transacción y
 * deja contados los intentos aunque la venta se revierta.
 *
 * <h2>La guardia</h2>
 *
 * La autorización se llama sin que el hilo tenga una transacción abierta, y por tanto sin tener
 * bloqueada ni referenciada por una clave foránea la fila de {@code usuarios} del vendedor o del
 * administrador. Si la tuviera, la transacción de dentro esperaría a la de fuera hasta el tiempo
 * límite de InnoDB y el intento no se contaría. Llamar a este servicio desde una transacción es un
 * error de programación, no un dato del usuario, y falla en el acto con {@link IllegalStateException}.
 */
@Service
public class ServicioVentas {

    private final ServicioAutorizacion autorizacion;
    private final RegistradorDeVenta registrador;
    private final Clock reloj;

    public ServicioVentas(ServicioAutorizacion autorizacion, RegistradorDeVenta registrador, Clock reloj) {
        this.autorizacion = autorizacion;
        this.registrador = registrador;
        this.reloj = reloj;
    }

    public VentaRegistrada registrar(SolicitudVenta solicitud) {
        return procesar(solicitud).venta();
    }

    /**
     * La venta y si ya existía: un reintento con la misma clave de idempotencia devuelve la venta
     * registrada (200) en lugar de crear otra (201).
     */
    public ResultadoDeVenta procesar(SolicitudVenta solicitud) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Registrar una venta no puede llamarse dentro de una transacción: "
                    + "el PIN se valida en su propia transacción antes de abrir la de la venta.");
        }
        SolicitudVenta normalizada = NormalizadorDeVenta.normalizar(solicitud, Hoy.de(reloj));
        Autorizacion autorizada = autorizacion.autorizar(normalizada.autorizacion(), excepcionesNecesarias(normalizada));
        try {
            return registrador.registrar(normalizada, autorizada);
        } catch (VentaDuplicadaException e) {
            // Otra petición con la misma clave confirmó primero: su venta es la respuesta.
            return registrador.existente(normalizada);
        }
    }

    /**
     * Lo que la carga pide autorizar: un descuento (de línea o sobre toda la compra), un encargo
     * (cualquier fecha de entrega o el motivo) y un producto nuevo, que además es un encargo.
     */
    private static Set<ExcepcionAutorizable> excepcionesNecesarias(SolicitudVenta s) {
        Set<ExcepcionAutorizable> necesarias = EnumSet.noneOf(ExcepcionAutorizable.class);
        if (s.descuentoTotal() != null || s.lineas().stream().anyMatch(l -> l.descuento() != null)) {
            necesarias.add(ExcepcionAutorizable.DESCUENTO);
        }
        if (s.motivoEncargo() != null || s.lineas().stream().anyMatch(l -> l.entregaEstimada() != null)) {
            necesarias.add(ExcepcionAutorizable.ENCARGO);
        }
        if (s.lineas().stream().map(LineaSolicitada::productoNuevo).anyMatch(Objects::nonNull)) {
            necesarias.add(ExcepcionAutorizable.PRODUCTO_NUEVO);
            necesarias.add(ExcepcionAutorizable.ENCARGO);
        }
        return necesarias;
    }
}

package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.DevolucionRegistrada;
import co.com.bspstore.sgip.api.dto.SolicitudDevolucion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Set;

/**
 * Devolver o anular una venta (RF-68): la fachada. <b>No es transaccional</b>: valida la forma de la
 * solicitud, valida el PIN del administrador (una devolución siempre lo pide, ADR-0032) y solo entonces
 * llama a {@link RegistradorDeDevolucion}, que abre la transacción. Son dos beans para que el proxy
 * aplique la transacción, y la autorización va antes por una razón de fondo:
 * {@link ServicioAutorizacion#autorizar} corre en su propia transacción y deja contados los intentos
 * aunque la devolución se revierta.
 *
 * <h2>La guardia</h2>
 *
 * La autorización se llama sin que el hilo tenga una transacción abierta, y por tanto sin tener
 * bloqueada ni referenciada por una clave foránea la fila de {@code usuarios} del cajero o del
 * administrador (la devolución guarda quién la hizo y quién la autorizó). Si la tuviera, la transacción
 * de dentro esperaría a la de fuera hasta el tiempo límite de InnoDB y el intento no se contaría. Llamar a
 * este servicio desde una transacción es un error de programación, no un dato del usuario, y falla en el
 * acto con {@link IllegalStateException}.
 *
 * La devolución no lleva clave de idempotencia: un doble envío de una devolución parcial registraría dos,
 * cada una dentro de lo que quedaba por devolver (la segunda no puede pasarse de lo vendido), y el de una
 * anulación recibe el 409 de «ya está anulada».
 */
@Service
public class ServicioDevoluciones {

    private static final Set<ExcepcionAutorizable> DEVOLUCION = Set.of(ExcepcionAutorizable.DEVOLUCION);

    private final ServicioAutorizacion autorizacion;
    private final RegistradorDeDevolucion registrador;

    public ServicioDevoluciones(ServicioAutorizacion autorizacion, RegistradorDeDevolucion registrador) {
        this.autorizacion = autorizacion;
        this.registrador = registrador;
    }

    public DevolucionRegistrada registrar(SolicitudDevolucion solicitud) {
        return procesar(solicitud).devolucion();
    }

    public ResultadoDeDevolucion procesar(SolicitudDevolucion solicitud) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Registrar una devolución no puede llamarse dentro de una transacción: "
                    + "el PIN se valida en su propia transacción antes de abrir la de la devolución.");
        }
        // La forma primero: un motivo vacío es un 400 aunque no haya PIN, y no cuenta un intento.
        SolicitudDevolucion normalizada = NormalizadorDeDevolucion.forma(solicitud);
        Autorizacion autorizada = autorizacion.autorizar(normalizada.autorizacion(), DEVOLUCION);
        return registrador.registrar(normalizada, autorizada);
    }
}

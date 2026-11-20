package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.EncargoPendiente;
import co.com.bspstore.sgip.api.dto.EntregaRegistrada;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioEncargos;
import co.com.bspstore.sgip.datos.RepositorioEncargos.EstadoDelEncargo;
import co.com.bspstore.sgip.datos.RepositorioEncargos.FilaDeEncargo;
import co.com.bspstore.sgip.util.Hoy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Los encargos (RF-42, RF-69): la lista del administrador de lo que se vendió sin existencia y la marca
 * de entrega a mano. Solo el administrador los ve (lo exige el controlador).
 *
 * <h2>El estado se calcula al consultar</h2>
 *
 * Un encargo está {@code PENDIENTE} mientras la existencia del producto siga en negativo y
 * {@code LLEGO} desde que es cero o más. No hay trigger ni se actualiza
 * {@code ventas.reposicion_pendiente}, que solo dice que la venta tuvo un encargo (el ADR-0014 lo
 * recoge). La existencia que se compara es la del producto <b>ahora</b>: no distingue de qué venta
 * saldrá la mercancía que llegue, y para decidir si un encargo ya puede entregarse no hace falta.
 *
 * <h2>La lista y la entrega</h2>
 *
 * Una consulta con tope ({@value #TOPE_DE_LA_LISTA}), del que prometió la fecha más cercana al más
 * lejano. La entrega queda con quién y cuándo (hora de Bogotá del reloj de la aplicación) y la segunda es
 * un 409, también si coinciden dos: la actualización es condicionada a que siga sin entregar y no se lee
 * nada antes. Marcar la entrega no toca el inventario: el encargo ya descontó su existencia al venderse.
 * Los datos personales del cliente son de esta ruta y no se escriben en ningún registro del servidor.
 */
@Service
public class ServicioEncargos {

    /** Lo más que devuelve la lista. */
    static final int TOPE_DE_LA_LISTA = 200;

    static final String NO_EXISTE = "El encargo no existe.";
    static final String YA_SE_ENTREGO = "Este encargo ya fue entregado.";
    static final String VENTA_ANULADA = "La venta de este encargo está anulada.";
    static final String YA_SE_DEVOLVIO = "El producto de este encargo ya se devolvió.";

    private final RepositorioEncargos encargos;
    private final Clock reloj;

    public ServicioEncargos(RepositorioEncargos encargos, Clock reloj) {
        this.encargos = encargos;
        this.reloj = reloj;
    }

    /**
     * Los encargos sin entregar, {@code PENDIENTE} o {@code LLEGO}, de la empresa de la sesión. El estado
     * es obligatorio: uno que no existe es un 400 y no una lista vacía.
     */
    @Transactional(readOnly = true)
    public List<EncargoPendiente> listar(String estado) {
        boolean llego = interpretar(estado);
        Integer empresa = ContextoEmpresa.empresaActual();
        return encargos.listar(empresa, llego, TOPE_DE_LA_LISTA).stream().map(this::pendiente).toList();
    }

    private EncargoPendiente pendiente(FilaDeEncargo f) {
        return new EncargoPendiente(f.itemId(), f.ventaId(), f.consecutivo(), f.fecha().atZone(Hoy.ZONA).toOffsetDateTime(),
                f.cliente(), f.telefono(), f.productoId(), f.codigo(), f.producto(), f.cantidad(), f.cantidadEncargo(),
                f.entregaEstimada(), f.existencia(), f.existencia() >= 0 ? "LLEGO" : "PENDIENTE",
                ServicioDomicilios.antiguedadEnDias(f.fecha(), reloj));
    }

    /** Verdadero para {@code LLEGO}, falso para {@code PENDIENTE}. */
    private static boolean interpretar(String texto) {
        String limpio = texto == null ? "" : texto.trim().toUpperCase(Locale.ROOT);
        return switch (limpio) {
            case "LLEGO" -> true;
            case "PENDIENTE" -> false;
            default -> throw new FiltroInvalidoException("Indica el estado de los encargos: PENDIENTE o LLEGO.");
        };
    }

    /**
     * Marca el encargo como entregado por quien está en sesión y ahora. 404 si la línea no es un encargo
     * de la empresa, 409 si ya estaba entregado o si la venta se anuló.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EntregaRegistrada entregar(Long itemId) {
        if (itemId == null || itemId <= 0) {
            throw new RecursoNoEncontradoException(NO_EXISTE);
        }
        Integer empresa = ContextoEmpresa.empresaActual();
        Integer quien = ContextoEmpresa.actual().map(UsuarioAutenticado::usuarioId).orElseThrow(() ->
                new IllegalStateException("No hay usuario autenticado en el contexto. Revise la cadena de seguridad."));
        LocalDateTime cuando = Hoy.ahora(reloj);
        int filas = encargos.marcarEntregado(empresa, itemId, cuando, quien);
        EstadoDelEncargo estado = encargos.estadoDelEncargo(empresa, itemId)
                .orElseThrow(() -> new RecursoNoEncontradoException(NO_EXISTE));
        if (filas == 0) {
            throw new EstadoNoAdmiteLaOperacionException(estado.entregado() ? YA_SE_ENTREGO
                    : estado.devuelto() ? YA_SE_DEVOLVIO : VENTA_ANULADA);
        }
        if (!estado.ventaVigente() || estado.devuelto()) {
            // Se marcó, pero la venta está anulada o el producto se devolvió: la transacción se deshace y no
            // queda marca.
            throw new EstadoNoAdmiteLaOperacionException(estado.ventaVigente() ? YA_SE_DEVOLVIO : VENTA_ANULADA);
        }
        return new EntregaRegistrada(itemId, cuando.atZone(Hoy.ZONA).toOffsetDateTime(), quien);
    }
}

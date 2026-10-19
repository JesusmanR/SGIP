package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.MovimientoDeInventario;
import co.com.bspstore.sgip.api.dto.SolicitudAjuste;
import co.com.bspstore.sgip.api.dto.SolicitudIngreso;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioInventario;
import co.com.bspstore.sgip.modelo.TipoMovimiento;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * El inventario (RF-48, RF-49).
 *
 * Todo cambio de existencia pasa por {@link #registrar}, que es privado.
 * No es una comodidad de organización: es la forma de cumplir el RF-49
 * —«registrar todo cambio de existencia con su valor anterior y
 * posterior, tipo de movimiento, motivo y usuario responsable»— sin
 * depender de que cada sitio futuro se acuerde de hacer las dos cosas.
 * Un método que suba la existencia sin escribir el movimiento no existe,
 * y el día que alguien lo necesite tendrá que añadirlo aquí, donde se
 * ve que las dos escrituras van juntas.
 *
 * Cuando el módulo de ventas descuente existencia, entrará por esta
 * misma puerta con tipo EGRESO.
 */
@Service
public class ServicioInventario {

    /**
     * El piso que la V9 puso en {@code productos} y la V18 en el kárdex.
     *
     * Está repetido aquí para poder rechazar con un mensaje que diga qué
     * pasó. La restricción de la base sigue siendo la que manda; esta
     * copia solo evita que el usuario reciba un error de integridad.
     */
    private static final int PISO_EXISTENCIA = -9999;

    private final RepositorioInventario inventario;

    public ServicioInventario(RepositorioInventario inventario) {
        this.inventario = inventario;
    }

    /**
     * Ingreso de mercancía (RF-48): sube la existencia y produce
     * exactamente un movimiento.
     */
    @Transactional
    public Optional<MovimientoDeInventario> ingresar(SolicitudIngreso solicitud) {
        return registrar(TipoMovimiento.INGRESO, solicitud.productoId(),
                solicitud.cantidad(), null,
                solicitud.documento(), solicitud.motivo(), null);
    }

    /**
     * Ajuste contra un conteo físico (RF-49).
     *
     * Devuelve vacío en dos casos que el controlador distingue: cuando el
     * producto no es de esta empresa —404— y cuando el conteo coincide
     * con el saldo, que no es un error y tampoco es un cambio. No se
     * registra nada porque no cambió nada, y la tabla no admite
     * movimientos de cantidad cero, con razón: una fila que dice que no
     * pasó nada no es un movimiento.
     */
    @Transactional
    public ResultadoAjuste ajustar(SolicitudAjuste solicitud) {
        Integer empresa = ContextoEmpresa.empresaActual();
        Optional<Integer> saldo = inventario.bloquearSaldo(empresa, solicitud.productoId());
        if (saldo.isEmpty()) {
            return ResultadoAjuste.productoAjeno();
        }
        int anterior = saldo.get();
        int contado = solicitud.existenciaContada();
        if (anterior == contado) {
            return ResultadoAjuste.sinCambio();
        }
        MovimientoDeInventario movimiento = escribir(empresa, solicitud.productoId(),
                TipoMovimiento.AJUSTE, Math.abs(contado - anterior), anterior, contado,
                solicitud.documento(), solicitud.motivo(), null);
        return ResultadoAjuste.ajustado(movimiento);
    }

    @Transactional(readOnly = true)
    public List<MovimientoDeInventario> kardex(Integer productoId, int limite) {
        return inventario.kardex(ContextoEmpresa.empresaActual(), productoId, limite);
    }

    @Transactional(readOnly = true)
    public List<MovimientoDeInventario> ultimos(int limite) {
        return inventario.ultimos(ContextoEmpresa.empresaActual(), limite);
    }

    /**
     * La comprobación del RF-49 para una referencia, expuesta para que la
     * pantalla de inventario pueda mostrarla y para que una prueba de
     * integración pueda afirmarla sobre datos reales.
     *
     * Devuelve vacío si el producto no es de esta empresa.
     */
    @Transactional(readOnly = true)
    public int sumaDeMovimientos(Integer productoId) {
        return inventario.sumaDeMovimientos(ContextoEmpresa.empresaActual(), productoId);
    }

    /**
     * El único camino por el que la existencia cambia.
     *
     * El orden de las tres operaciones importa y no es intercambiable:
     *
     * <ol>
     *   <li>Bloquear el saldo. Dos movimientos simultáneos sobre la misma
     *       referencia leerían el mismo anterior y escribirían dos filas
     *       que arrancan del mismo sitio; cada una coherente por separado,
     *       y la suma dejando de dar la existencia. Es la única
     *       incoherencia que las restricciones de la V23 no pueden ver.</li>
     *   <li>Escribir el movimiento. Si la fila no cuadra, la base la
     *       rechaza y la transacción se deshace entera, de modo que no
     *       queda una existencia cambiada sin su explicación.</li>
     *   <li>Fijar el saldo con el valor que el movimiento declara como
     *       posterior, no sumando: así las dos cifras salen del mismo
     *       cálculo.</li>
     * </ol>
     */
    private Optional<MovimientoDeInventario> registrar(TipoMovimiento tipo, Integer productoId,
                                                       int cantidad, Integer saldoContado,
                                                       String documento, String motivo,
                                                       Long recepcionId) {
        Integer empresa = ContextoEmpresa.empresaActual();
        Optional<Integer> saldo = inventario.bloquearSaldo(empresa, productoId);
        if (saldo.isEmpty()) {
            return Optional.empty();
        }
        int anterior = saldo.get();
        int posterior = saldoContado != null ? saldoContado : tipo.saldoTras(anterior, cantidad);
        return Optional.of(escribir(empresa, productoId, tipo, cantidad, anterior, posterior,
                documento, motivo, recepcionId));
    }

    private MovimientoDeInventario escribir(Integer empresa, Integer productoId,
                                            TipoMovimiento tipo, int cantidad,
                                            int anterior, int posterior,
                                            String documento, String motivo, Long recepcionId) {
        if (cantidad <= 0) {
            throw new MovimientoInvalidoException(
                    "Un movimiento de " + cantidad + " unidades no es un movimiento.");
        }
        if (tipo.exigeMotivo() && (motivo == null || motivo.isBlank())) {
            throw new MovimientoInvalidoException(
                    "Un " + tipo.name().toLowerCase() + " exige motivo: es quien explica "
                            + "por qué cambió el saldo sin que se moviera mercancía.");
        }
        if (posterior < PISO_EXISTENCIA) {
            throw new MovimientoInvalidoException(
                    "El saldo quedaría en " + posterior + ", por debajo del piso de "
                            + PISO_EXISTENCIA + ". Eso no es una deuda de reposición, "
                            + "es un error de digitación.");
        }

        Long id = inventario.insertarMovimiento(empresa, productoId, responsable(),
                tipo, cantidad, anterior, posterior, texto(documento), texto(motivo), recepcionId);
        inventario.fijarSaldo(empresa, productoId, posterior);

        // Se devuelve lo que quedó en la base y no lo que se acaba de
        // escribir: así la respuesta lleva la fecha que puso el motor y
        // el nombre del usuario, en lugar de lo que este método cree.
        return inventario.kardex(empresa, productoId, 1).stream()
                .filter(m -> m.movimientoId().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "El movimiento " + id + " no se encontró justo después de escribirlo."));
    }

    private Integer responsable() {
        return ContextoEmpresa.actual()
                .map(UsuarioAutenticado::usuarioId)
                .orElseThrow(() -> new IllegalStateException(
                        "Movimiento de inventario sin usuario en el contexto. "
                                + "Revise la cadena de seguridad."));
    }

    private static String texto(String valor) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.trim();
        return limpio.isEmpty() ? null : limpio;
    }

    /**
     * El resultado de un ajuste, que tiene tres desenlaces y no dos.
     *
     * Un Optional no alcanza: «no se registró nada» ocurre tanto cuando
     * el producto es ajeno —404— como cuando el conteo coincidía —204—, y
     * responder lo mismo en los dos casos le diría al usuario que su
     * producto no existe cuando lo que pasa es que la cuenta ya estaba
     * bien.
     */
    public record ResultadoAjuste(boolean existe, MovimientoDeInventario movimiento) {

        static ResultadoAjuste productoAjeno() {
            return new ResultadoAjuste(false, null);
        }

        static ResultadoAjuste sinCambio() {
            return new ResultadoAjuste(true, null);
        }

        static ResultadoAjuste ajustado(MovimientoDeInventario movimiento) {
            return new ResultadoAjuste(true, movimiento);
        }

        public boolean huboCambio() {
            return movimiento != null;
        }
    }
}

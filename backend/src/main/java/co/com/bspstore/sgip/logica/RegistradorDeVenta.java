package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.MedioDePago;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.api.dto.VentaRegistrada;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioClientes;
import co.com.bspstore.sgip.datos.RepositorioConsecutivos;
import co.com.bspstore.sgip.datos.RepositorioInventario;
import co.com.bspstore.sgip.datos.RepositorioInventario.Egreso;
import co.com.bspstore.sgip.datos.RepositorioVentas;
import co.com.bspstore.sgip.datos.RepositorioVentas.NuevaLinea;
import co.com.bspstore.sgip.datos.RepositorioVentas.NuevaVenta;
import co.com.bspstore.sgip.datos.RepositorioVentas.NuevoPago;
import co.com.bspstore.sgip.datos.RepositorioVentas.PrecioVigente;
import co.com.bspstore.sgip.datos.RepositorioVentas.ProductoBloqueado;
import co.com.bspstore.sgip.datos.RepositorioVentas.VentaPorClave;
import co.com.bspstore.sgip.logica.venta.CalculoDeVenta;
import co.com.bspstore.sgip.logica.venta.Descuento;
import co.com.bspstore.sgip.logica.venta.LineaCalculada;
import co.com.bspstore.sgip.logica.venta.LineaPedida;
import co.com.bspstore.sgip.logica.venta.VentaCalculada;
import co.com.bspstore.sgip.modelo.TipoConsecutivo;
import co.com.bspstore.sgip.modelo.TipoMedio;
import co.com.bspstore.sgip.util.Hoy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * La venta del mostrador dentro de UNA transacción (RF-41): descuenta las existencias, guarda la
 * venta con sus líneas y sus pagos, deja el kárdex, la numera y es idempotente. Es el bean
 * transaccional que {@link ServicioVentas} llama después de validar el PIN; son beans distintos
 * para que el proxy de Spring aplique la transacción.
 *
 * <h2>Orden global de bloqueos de la caja</h2>
 *
 * <ol>
 *   <li>el <b>turno</b> ({@code cierres_caja}), {@code FOR SHARE} por clave primaria en
 *       {@link ServicioTurnos#exigirTurnoAbierto()};</li>
 *   <li>la <b>venta</b> ({@code FOR UPDATE}): solo devoluciones y cobros contra entrega; esta
 *       operación crea la suya y no la bloquea;</li>
 *   <li>los <b>productos</b>, por {@code producto_id} ascendente con {@code FORCE INDEX (PRIMARY)};</li>
 *   <li>el <b>saldo del cliente</b>: el saldo a favor aún no está disponible;</li>
 *   <li>el <b>consecutivo</b>, {@code FOR UPDATE}, el último antes de insertar;</li>
 *   <li>el <b>kárdex</b>.</li>
 * </ol>
 *
 * Todo lo que se puede decidir sin tomar el consecutivo (el precio, la existencia, el cálculo, los
 * pagos) se decide antes: así el consecutivo se tiene el menor tiempo posible y la venta que va a
 * fallar no hace esperar a las demás. El {@code INSERT INTO ventas} y el de los pagos comprueban la
 * clave foránea compuesta hacia {@code cierres_caja}, y para ello InnoDB toma un bloqueo compartido
 * sobre la entrada de {@code uq_cierres_empresa}. Por eso <b>el cierre del turno toma la fila solo
 * por {@code PRIMARY}</b> (véase {@link ServicioTurnos}): si la tomara por ese índice, él y la venta
 * se esperarían uno a otro.
 *
 * <h2>READ COMMITTED</h2>
 *
 * La transacción corre en READ COMMITTED. Con REPEATABLE READ la lectura de la clave de idempotencia
 * vería la instantánea que se tomó al comprobar el turno, no la venta que la petición hermana acaba
 * de confirmar mientras esta esperaba los productos; y una lectura con bloqueo de una clave que no
 * existe toma un bloqueo de hueco, con el que dos ventas distintas se interbloquean. En READ
 * COMMITTED la lectura ve lo último confirmado y no hay bloqueos de hueco.
 *
 * <h2>Idempotencia</h2>
 *
 * La clave se comprueba después de tomar los bloqueos: la segunda petición, que esperó a la primera,
 * encuentra la venta y la devuelve sin recalcular nada. Si dos peticiones con la misma clave pero
 * distinta carga no comparten productos no se esperan, y la segunda choca al insertar con
 * {@code uq_ventas_idempotencia}: se descarta la transacción entera ({@link VentaDuplicadaException})
 * y la fachada responde con la venta existente, leída en una consulta nueva.
 */
@Service
public class RegistradorDeVenta {

    private static final Logger LOG = LoggerFactory.getLogger(RegistradorDeVenta.class);

    /** El piso que la V9 puso en {@code productos} y la V18 en el kárdex. */
    private static final int PISO_EXISTENCIA = -9999;

    /** Lo que cabe en {@code DECIMAL(14,2)} sin centavos: 12 dígitos. */
    private static final BigDecimal PRECIO_MAXIMO = new BigDecimal("999999999999");
    /** La suma de los subtotales tiene que dejar sitio al IVA (hasta el 100 %) dentro de DECIMAL(14,2). */
    private static final BigDecimal SUBTOTAL_MAXIMO = new BigDecimal("499999999999");
    private static final BigDecimal IMPORTE_MAXIMO = new BigDecimal("999999999999.99");

    static final String NO_VENDIBLE = "Este producto no se puede vender: no tiene un precio de venta válido.";
    static final String SALDO_NO_DISPONIBLE = "El saldo a favor aún no está disponible";
    static final String MEDIO_NO_DISPONIBLE = "El medio de pago no está disponible.";
    static final String PAGOS_NO_SUMAN = "La suma de los pagos debe ser igual al total de la venta.";
    static final String IMPORTE_DESMEDIDO = "El importe de la venta es demasiado grande para guardarse.";

    private final ServicioTurnos turnos;
    private final RepositorioVentas ventas;
    private final RepositorioConsecutivos consecutivos;
    private final RepositorioInventario inventario;
    private final RepositorioClientes clientes;
    private final Clock reloj;

    public RegistradorDeVenta(ServicioTurnos turnos, RepositorioVentas ventas, RepositorioConsecutivos consecutivos,
                              RepositorioInventario inventario, RepositorioClientes clientes, Clock reloj) {
        this.turnos = turnos;
        this.ventas = ventas;
        this.consecutivos = consecutivos;
        this.inventario = inventario;
        this.clientes = clientes;
        this.reloj = reloj;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResultadoDeVenta registrar(SolicitudVenta pedida, Autorizacion autorizacion) {
        Integer empresa = ContextoEmpresa.empresaActual();
        Integer cajero = cajeroEnSesion();
        LocalDateTime ahora = Hoy.ahora(reloj);
        SolicitudVenta s = NormalizadorDeVenta.normalizar(pedida, Hoy.de(reloj));
        String huella = NormalizadorDeVenta.huella(s);
        String clave = s.claveIdempotencia().toString();

        // 1. Las líneas repetidas ya vienen fusionadas y ordenadas por producto.
        // 2. El turno (FOR SHARE).
        Long turno = turnos.exigirTurnoAbierto();

        // 3. Los productos, en orden ascendente de id.
        List<Integer> ids = s.lineas().stream().map(LineaSolicitada::productoId).toList();
        Map<Integer, ProductoBloqueado> productos = new HashMap<>();
        for (ProductoBloqueado p : ventas.bloquearProductos(empresa, ids)) {
            productos.put(p.id(), p);
        }
        if (productos.size() != ids.size()) {
            throw new RecursoNoEncontradoException("El producto no existe.");
        }

        // 4. La clave de idempotencia, con los bloqueos ya tomados.
        Optional<VentaPorClave> existente = ventas.buscarPorClave(empresa, clave);
        if (existente.isPresent()) {
            return repetida(empresa, existente.get(), huella);
        }

        // 5. El saldo del cliente: el medio SALDO_A_FAVOR se rechaza más abajo (tarea 9).
        // 6. Los productos nuevos: tarea 8; la solicitud con uno no llega hasta aquí.
        Integer cliente = cliente(empresa, s.clienteId());

        // 7. El precio, con una sola consulta a la vista.
        List<LineaPedida> pedidas = lineasConPrecio(empresa, s, ids);
        topar(pedidas);

        // La existencia y el encargo.
        List<Existencia> existencias = repartirExistencias(s, productos, autorizacion);
        boolean hayEncargo = existencias.stream().anyMatch(e -> e.encargo() > 0);

        // El cálculo.
        VentaCalculada calculada = calcular(pedidas, s.descuentoTotal());
        if (calculada.tieneDescuento()) {
            autorizacion.exigir(ExcepcionAutorizable.DESCUENTO);
        }
        if (hayEncargo) {
            autorizacion.exigir(ExcepcionAutorizable.ENCARGO);
        }
        List<NuevaLinea> lineas = lineasAGuardar(calculada, productos, existencias, s);

        // Los pagos, contra los medios de la empresa.
        PagosValidados pagos = validarPagos(empresa, s.pagos(), calculada.total());

        // 8. El consecutivo (FOR UPDATE), el último bloqueo antes de insertar.
        String consecutivo = "V-%08d".formatted(consecutivos.tomarSiguiente(empresa, TipoConsecutivo.VENTA));

        // 9. La venta, las líneas y los pagos, y después el kárdex.
        boolean usoAutorizacion = calculada.tieneDescuento() || hayEncargo;
        Integer autorizadaPor = usoAutorizacion ? autorizacion.administradorId() : null;
        Long ventaId;
        try {
            ventaId = ventas.insertarVenta(new NuevaVenta(empresa, consecutivo, cliente, cajero, ahora, turno,
                    calculada.subtotal(), calculada.descuento(), calculada.iva(), hayEncargo, autorizadaPor,
                    hayEncargo ? s.motivoEncargo() : null,
                    autorizadaPor != null && autorizadaPor.equals(cajero), clave, huella,
                    s.descuentoTotal() == null ? null : s.descuentoTotal().tipo(),
                    s.descuentoTotal() == null ? null : s.descuentoTotal().valor()));
        } catch (DuplicateKeyException e) {
            if (e.getMessage() != null && e.getMessage().contains(RepositorioVentas.CLAVE_UNICA_DE_IDEMPOTENCIA)) {
                throw new VentaDuplicadaException(e);
            }
            throw e;
        }
        ventas.insertarLineas(empresa, ventaId, lineas);
        ventas.insertarPagos(empresa, ventaId, turno, cajero, ahora, pagos.pagos());
        List<Egreso> egresos = new ArrayList<>(existencias.size());
        for (Existencia e : existencias) {
            egresos.add(new Egreso(e.productoId(), e.cantidad(), e.anterior(), e.posterior(),
                    e.encargo() > 0 ? autorizacion.administradorId() : null));
        }
        inventario.registrarEgresos(empresa, cajero, consecutivo, egresos);

        return new ResultadoDeVenta(new VentaRegistrada(ventaId, consecutivo, calculada.total(), pagos.cambio(),
                List.copyOf(calculada.lineas())), false);
    }

    /**
     * La venta que ya existe para esa clave, leída en una transacción nueva. La llama la fachada
     * cuando {@link #registrar} descartó la suya por una venta hermana con la misma clave.
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public ResultadoDeVenta existente(SolicitudVenta pedida) {
        Integer empresa = ContextoEmpresa.empresaActual();
        SolicitudVenta s = NormalizadorDeVenta.normalizar(pedida, Hoy.de(reloj));
        VentaPorClave encontrada = ventas.buscarPorClave(empresa, s.claveIdempotencia().toString())
                .orElseThrow(() -> new IllegalStateException(
                        "La venta de la clave de idempotencia no aparece después de que otra la confirmara."));
        return repetida(empresa, encontrada, NormalizadorDeVenta.huella(s));
    }

    private ResultadoDeVenta repetida(Integer empresa, VentaPorClave existente, String huella) {
        if (!huella.equals(existente.huella())) {
            throw new ClaveDeIdempotenciaEnUsoException();
        }
        VentaRegistrada venta = ventas.leer(empresa, existente.ventaId()).orElseThrow(() -> new IllegalStateException(
                "La venta " + existente.ventaId() + " desapareció dentro de su propia transacción."));
        return new ResultadoDeVenta(venta, true);
    }

    // ------------------------------------------------------------ cliente

    private Integer cliente(Integer empresa, Integer pedido) {
        if (pedido == null) {
            return clientes.buscarConsumidorFinal(empresa).orElseThrow(() -> new IllegalStateException(
                    "La empresa " + empresa + " no tiene su Consumidor final: ni el alta ni la V41 lo sembraron.")).id();
        }
        return clientes.buscar(empresa, pedido)
                .orElseThrow(() -> new RecursoNoEncontradoException("El cliente no existe.")).id();
    }

    // ------------------------------------------------------------ precio

    private List<LineaPedida> lineasConPrecio(Integer empresa, SolicitudVenta s, List<Integer> ids) {
        Map<Integer, PrecioVigente> precios = new HashMap<>();
        for (PrecioVigente p : ventas.preciosVigentes(empresa, ids)) {
            precios.put(p.productoId(), p);
        }
        List<LineaPedida> pedidas = new ArrayList<>(s.lineas().size());
        for (LineaSolicitada l : s.lineas()) {
            PrecioVigente vigente = precios.get(l.productoId());
            LineaPedida p = vigente == null ? null : lineaVendible(l, vigente);
            if (p == null) {
                throw new VentaInvalidaException(NO_VENDIBLE);
            }
            pedidas.add(p);
        }
        return pedidas;
    }

    /**
     * La línea con el precio del servidor, o nulo si el producto no se puede vender: sin precio, con
     * precio cero o desmedido, o con una tarifa o un tratamiento que el cálculo no admite. Lo último
     * se averigua ensayando el propio cálculo con una unidad, de modo que la regla es una sola.
     */
    private static LineaPedida lineaVendible(LineaSolicitada l, PrecioVigente v) {
        if (v.precio() == null) {
            return null;
        }
        BigDecimal precio = v.precio().setScale(0, RoundingMode.HALF_UP);
        if (precio.signum() <= 0 || precio.compareTo(PRECIO_MAXIMO) > 0) {
            return null;
        }
        BigDecimal tarifa = v.tarifa() == null ? BigDecimal.ZERO : v.tarifa();
        try {
            CalculoDeVenta.calcular(List.of(new LineaPedida(l.productoId(), 1, precio, tarifa, v.tratamiento(), null)), null);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return new LineaPedida(l.productoId(), l.cantidad(), precio, tarifa, v.tratamiento(), l.descuento());
    }

    /** Que la suma de los subtotales quepa, con su IVA, en DECIMAL(14,2) antes de calcular. */
    private static void topar(List<LineaPedida> pedidas) {
        BigDecimal suma = BigDecimal.ZERO;
        for (LineaPedida p : pedidas) {
            suma = suma.add(p.precioUnitario().multiply(BigDecimal.valueOf(p.cantidad())));
            if (suma.compareTo(SUBTOTAL_MAXIMO) > 0) {
                throw new VentaInvalidaException(IMPORTE_DESMEDIDO);
            }
        }
    }

    private static VentaCalculada calcular(List<LineaPedida> pedidas, Descuento total) {
        try {
            return CalculoDeVenta.calcular(pedidas, total);
        } catch (IllegalArgumentException e) {
            // Lo que el cálculo rechaza así ya se comprobó antes; si algo se cuela, es un dato que
            // no se puede cobrar y no un 500. Se registra solo la clase, nunca el dato.
            LOG.warn("El cálculo de la venta rechazó una solicitud que pasó la frontera: {}", e.getClass().getSimpleName());
            throw new VentaInvalidaException(IMPORTE_DESMEDIDO);
        }
    }

    // ------------------------------------------------------------ existencia y encargo

    private record Existencia(Integer productoId, int cantidad, int anterior, int posterior, int encargo) {}

    private List<Existencia> repartirExistencias(SolicitudVenta s, Map<Integer, ProductoBloqueado> productos,
                                                 Autorizacion autorizacion) {
        boolean pideEncargo = autorizacion.cubre().contains(ExcepcionAutorizable.ENCARGO);
        List<Existencia> repartidas = new ArrayList<>(s.lineas().size());
        for (LineaSolicitada l : s.lineas()) {
            ProductoBloqueado p = productos.get(l.productoId());
            int cantidad = l.cantidad();
            int posterior = p.existencia() - cantidad;
            int encargo = 0;
            if (posterior < 0) {
                if (!pideEncargo) {
                    throw new ExistenciaInsuficienteException("No hay existencia suficiente de " + p.nombre() + ".");
                }
                if (posterior < PISO_EXISTENCIA) {
                    throw new ExistenciaInsuficienteException("La venta dejaría la existencia de " + p.nombre()
                            + " por debajo del límite permitido.");
                }
                if (l.entregaEstimada() == null) {
                    throw new VentaInvalidaException("Un encargo necesita la fecha estimada de entrega de cada producto.");
                }
                if (s.motivoEncargo() == null) {
                    throw new VentaInvalidaException("Un encargo necesita el motivo.");
                }
                encargo = cantidad - Math.max(p.existencia(), 0);
            }
            repartidas.add(new Existencia(p.id(), cantidad, p.existencia(), posterior, encargo));
        }
        return repartidas;
    }

    // ------------------------------------------------------------ lo que se guarda

    private static List<NuevaLinea> lineasAGuardar(VentaCalculada calculada, Map<Integer, ProductoBloqueado> productos,
                                                   List<Existencia> existencias, SolicitudVenta s) {
        List<NuevaLinea> lineas = new ArrayList<>(calculada.lineas().size());
        for (int i = 0; i < calculada.lineas().size(); i++) {
            LineaCalculada c = calculada.lineas().get(i);
            Existencia e = existencias.get(i);
            BigDecimal costo = productos.get(c.productoId()).costo();
            BigDecimal utilidad = c.base().subtract(costo.multiply(BigDecimal.valueOf(c.cantidad())));
            if (utilidad.abs().compareTo(IMPORTE_MAXIMO) > 0) {
                throw new VentaInvalidaException(IMPORTE_DESMEDIDO);
            }
            lineas.add(new NuevaLinea(c.productoId(), c.cantidad(), c.precioUnitario(), costo, c.tarifaIva(),
                    c.tratamientoIva(), c.subtotal(), c.descuento(), c.iva(), utilidad.setScale(2, RoundingMode.HALF_UP),
                    e.encargo(), e.encargo() > 0 ? s.lineas().get(i).entregaEstimada() : null));
        }
        return lineas;
    }

    // ------------------------------------------------------------ pagos

    private record PagosValidados(List<NuevoPago> pagos, BigDecimal cambio) {}

    private PagosValidados validarPagos(Integer empresa, List<PagoSolicitado> pedidos, BigDecimal total) {
        Map<Integer, MedioDePago> medios = new HashMap<>();
        for (MedioDePago m : ventas.mediosPorId(empresa, pedidos.stream().map(PagoSolicitado::medioId).distinct().toList())) {
            medios.put(m.id(), m);
        }
        List<NuevoPago> pagos = new ArrayList<>(pedidos.size());
        BigDecimal suma = BigDecimal.ZERO;
        BigDecimal cambio = BigDecimal.ZERO;
        for (PagoSolicitado p : pedidos) {
            MedioDePago medio = medios.get(p.medioId());
            if (medio == null) {
                throw new VentaInvalidaException(MEDIO_NO_DISPONIBLE);
            }
            if (medio.tipo() == TipoMedio.SALDO_A_FAVOR) {
                throw new VentaInvalidaException(SALDO_NO_DISPONIBLE);
            }
            if (!medio.activo()) {
                throw new VentaInvalidaException(MEDIO_NO_DISPONIBLE);
            }
            BigDecimal monto = p.monto().setScale(2, RoundingMode.UNNECESSARY);
            BigDecimal recibido = null;
            if (medio.tipo() == TipoMedio.EFECTIVO) {
                recibido = (p.recibido() == null ? p.monto() : p.recibido()).setScale(2, RoundingMode.UNNECESSARY);
                cambio = cambio.add(CalculoDeVenta.cambio(monto, recibido));
            } else {
                if (p.referencia() == null) {
                    throw new VentaInvalidaException("El pago con tarjeta o transferencia necesita su referencia.");
                }
                if (p.recibido() != null) {
                    throw new VentaInvalidaException("El efectivo recibido solo se pide en los pagos en efectivo.");
                }
            }
            suma = suma.add(monto);
            pagos.add(new NuevoPago(p.medioId(), monto, p.referencia(), recibido));
        }
        if (suma.compareTo(total) != 0) {
            throw new VentaInvalidaException(PAGOS_NO_SUMAN);
        }
        return new PagosValidados(List.copyOf(pagos), cambio.setScale(2, RoundingMode.UNNECESSARY));
    }

    private static Integer cajeroEnSesion() {
        return ContextoEmpresa.actual().map(UsuarioAutenticado::usuarioId).orElseThrow(() -> new IllegalStateException(
                "No hay usuario autenticado en el contexto. Revise la cadena de seguridad."));
    }
}

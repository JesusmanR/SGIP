package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.Cliente;
import co.com.bspstore.sgip.api.dto.Domicilio;
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
import co.com.bspstore.sgip.logica.ServicioProductoNuevo.Creado;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 *   <li>el <b>consecutivo</b>, {@code FOR UPDATE}, el último antes de insertar: primero el de
 *       {@code PRODUCTO_RAPIDO}, si la venta crea productos nuevos, y después el de {@code VENTA};</li>
 *   <li>el <b>kárdex</b>.</li>
 * </ol>
 *
 * <h2>Con productos nuevos (RF-42)</h2>
 *
 * El producto nuevo se crea después de bloquear los productos que ya existían y el saldo del cliente,
 * y antes del consecutivo de {@code VENTA}: bloquea el de {@code PRODUCTO_RAPIDO} (que ya va antes en
 * el orden global), crea el producto y su precio con {@link ServicioProductoNuevo} y lo suma a los
 * productos de la venta con existencia cero. Su fila nace dentro de esta transacción: nadie más la
 * puede haber bloqueado, así que no añade ninguna espera al orden de los productos (por id). El
 * consecutivo de los códigos rápidos lo toman solo las ventas con productos nuevos, siempre antes que
 * el de {@code VENTA} y después que cualquier producto, de modo que dos ventas con productos nuevos se
 * esperan en el mismo orden y no hay ciclo. Lo toma sin gastar números antes de repetir la
 * comprobación de la clave de idempotencia: dos envíos de la misma venta que solo traen productos
 * nuevos no comparten ningún producto bloqueado, y es ese consecutivo el que los pone en fila; el que
 * llega segundo ve la venta del primero y la devuelve sin crear un segundo producto.
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
 * La transacción corre en READ COMMITTED, y la razón de fondo es el precio. El costo y la existencia
 * salen frescos de la fila bloqueada ({@code FOR UPDATE} lee lo último confirmado), pero el precio
 * sale de la vista de precios con una lectura sin bloqueo. Con REPEATABLE READ esa lectura vendría de
 * la instantánea que se toma al leer el turno, antes de esperar los productos: una carga de precios
 * confirmada durante la espera daría el precio viejo con el costo nuevo. En READ COMMITTED todo se
 * lee en el mismo estado.
 *
 * Además, con REPEATABLE READ la lectura de la clave de idempotencia no vería la venta que la
 * petición hermana acaba de confirmar mientras esta esperaba, y una lectura con bloqueo de una clave
 * que no existe toma un bloqueo de hueco con el que dos ventas distintas se interbloquean.
 *
 * <h2>Un producto inactivo</h2>
 *
 * La vista de precios solo muestra productos activos, así que uno inactivo no trae precio y es «no
 * vendible» (400) sin necesidad de leer {@code productos.activo}.
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
    static final String TOTAL_POR_COBRAR_EN_CERO = "Una venta por cobrar necesita un total mayor que cero.";
    static final String IMPORTE_DESMEDIDO = "El importe de la venta es demasiado grande para guardarse.";

    private final ServicioTurnos turnos;
    private final RepositorioVentas ventas;
    private final RepositorioConsecutivos consecutivos;
    private final RepositorioInventario inventario;
    private final RepositorioClientes clientes;
    private final ServicioClientes servicioClientes;
    private final ServicioProductoNuevo productosNuevos;
    private final Clock reloj;

    public RegistradorDeVenta(ServicioTurnos turnos, RepositorioVentas ventas, RepositorioConsecutivos consecutivos,
                              RepositorioInventario inventario, RepositorioClientes clientes,
                              ServicioClientes servicioClientes, ServicioProductoNuevo productosNuevos, Clock reloj) {
        this.turnos = turnos;
        this.ventas = ventas;
        this.consecutivos = consecutivos;
        this.inventario = inventario;
        this.clientes = clientes;
        this.servicioClientes = servicioClientes;
        this.productosNuevos = productosNuevos;
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

        // 3. Los productos que ya existen, en orden ascendente de id (puede no haber ninguno: una
        //    venta solo de productos nuevos).
        List<Integer> idsDelCatalogo = s.lineas().stream().map(LineaSolicitada::productoId).filter(Objects::nonNull).toList();
        Map<Integer, ProductoBloqueado> productos = new HashMap<>();
        if (!idsDelCatalogo.isEmpty()) {
            for (ProductoBloqueado p : ventas.bloquearProductos(empresa, idsDelCatalogo)) {
                productos.put(p.id(), p);
            }
            if (productos.size() != idsDelCatalogo.size()) {
                throw new RecursoNoEncontradoException("El producto no existe.");
            }
        }

        // 4. La clave de idempotencia, con los bloqueos ya tomados.
        Optional<VentaPorClave> existente = ventas.buscarPorClave(empresa, clave);
        if (existente.isPresent()) {
            return repetida(empresa, existente.get(), huella);
        }

        // 5. El saldo del cliente: el medio SALDO_A_FAVOR se rechaza más abajo (tarea 9).
        Cliente cliente = cliente(empresa, s.clienteId());
        // Un domicilio exige un cliente identificado con sus datos: se mira antes de crear nada.
        Domicilio domicilio = s.domicilio();
        boolean contraEntrega = domicilio != null && domicilio.contraEntrega();
        String ciudadDelDomicilio = null;
        if (domicilio != null) {
            servicioClientes.exigirDatosDeDomicilio(cliente);
            ciudadDelDomicilio = ciudadDelDomicilio(empresa, domicilio);
        }

        // 6. Los productos nuevos: cada línea de producto nuevo se vuelve una línea del producto que
        //    acaba de nacer. Desde aquí la venta se lee de `r`, con todas sus líneas ya resueltas.
        SolicitudVenta r = s;
        if (s.lineas().stream().anyMatch(l -> l.productoNuevo() != null)) {
            autorizacion.exigir(ExcepcionAutorizable.PRODUCTO_NUEVO);
            // El consecutivo de los códigos rápidos, sin gastar números, para esperar a la hermana de la
            // misma clave: con ella ya confirmada, la clave se vuelve a mirar y se devuelve su venta.
            consecutivos.bloquear(empresa, TipoConsecutivo.PRODUCTO_RAPIDO);
            Optional<VentaPorClave> hermana = ventas.buscarPorClave(empresa, clave);
            if (hermana.isPresent()) {
                return repetida(empresa, hermana.get(), huella);
            }
            r = conProductosNuevos(empresa, s, productos);
        }

        // 7. El precio, con una sola consulta a la vista.
        List<Integer> ids = r.lineas().stream().map(LineaSolicitada::productoId).toList();
        List<LineaPedida> pedidas = lineasConPrecio(empresa, r, ids);
        topar(pedidas);

        // La existencia y el encargo.
        List<Existencia> existencias = repartirExistencias(r, productos, autorizacion);
        boolean hayEncargo = existencias.stream().anyMatch(e -> e.encargo() > 0);

        // El cálculo.
        VentaCalculada calculada = calcular(pedidas, s.descuentoTotal());
        if (calculada.tieneDescuento()) {
            autorizacion.exigir(ExcepcionAutorizable.DESCUENTO);
        }
        if (hayEncargo) {
            autorizacion.exigir(ExcepcionAutorizable.ENCARGO);
        }
        List<NuevaLinea> lineas = lineasAGuardar(calculada, productos, existencias, r);

        // Los pagos, contra los medios de la empresa. El contra entrega no trae ninguno: queda por cobrar.
        PagosValidados pagos = contraEntrega
                ? sinPagos(calculada.total())
                : validarPagos(empresa, s.pagos(), calculada.total());

        // 8. El consecutivo (FOR UPDATE), el último bloqueo antes de insertar.
        String consecutivo = "V-%08d".formatted(consecutivos.tomarSiguiente(empresa, TipoConsecutivo.VENTA));

        // 9. La venta, las líneas y los pagos, y después el kárdex.
        boolean usoAutorizacion = calculada.tieneDescuento() || hayEncargo;
        Integer autorizadaPor = usoAutorizacion ? autorizacion.administradorId() : null;
        Long ventaId;
        try {
            ventaId = ventas.insertarVenta(new NuevaVenta(empresa, consecutivo, cliente.id(), cajero, ahora, turno,
                    calculada.subtotal(), calculada.descuento(), calculada.iva(), hayEncargo, autorizadaPor,
                    hayEncargo ? s.motivoEncargo() : null,
                    autorizadaPor != null && autorizadaPor.equals(cajero), clave, huella,
                    s.descuentoTotal() == null ? null : s.descuentoTotal().tipo(),
                    s.descuentoTotal() == null ? null : s.descuentoTotal().valor(), domicilio != null, contraEntrega));
        } catch (DuplicateKeyException e) {
            if (e.getMessage() != null && e.getMessage().contains(RepositorioVentas.CLAVE_UNICA_DE_IDEMPOTENCIA)) {
                throw new VentaDuplicadaException(e);
            }
            throw e;
        }
        if (domicilio != null) {
            ventas.insertarDomicilio(empresa, ventaId, domicilio.direccion(), ciudadDelDomicilio, domicilio.telefono(),
                    domicilio.recibe());
        }
        ventas.insertarLineas(empresa, ventaId, lineas);
        if (!pagos.pagos().isEmpty()) {
            ventas.insertarPagos(empresa, ventaId, turno, cajero, ahora, pagos.pagos());
        }
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
     * La venta ya registrada con la clave de idempotencia de la solicitud, si la hay: la devuelve (el
     * 200 del reintento) o lanza el 409 de «la misma clave con otra carga». Es lo primero que hace la
     * fachada, antes de pedir un PIN y antes de mirar el turno o el día: un reintento de una venta
     * confirmada devuelve esa venta pase lo que pase después, y no cuenta un intento de PIN. Solo lee.
     * La comprobación bajo bloqueo de {@link #registrar} sigue siendo la que cubre la carrera.
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public Optional<ResultadoDeVenta> siYaExiste(SolicitudVenta pedida) {
        Integer empresa = ContextoEmpresa.empresaActual();
        SolicitudVenta s = NormalizadorDeVenta.forma(pedida);
        String huella = NormalizadorDeVenta.huella(s);
        return ventas.buscarPorClave(empresa, s.claveIdempotencia().toString())
                .map(existente -> repetida(empresa, existente, huella));
    }

    /**
     * La venta que ya existe para esa clave, leída en una transacción nueva. La llama la fachada
     * cuando {@link #registrar} descartó la suya por una venta hermana con la misma clave.
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public ResultadoDeVenta existente(SolicitudVenta pedida) {
        return siYaExiste(pedida).orElseThrow(() -> new IllegalStateException(
                "La venta de la clave de idempotencia no aparece después de que otra la confirmara."));
    }

    private ResultadoDeVenta repetida(Integer empresa, VentaPorClave existente, String huella) {
        if (!huella.equals(existente.huella())) {
            throw new ClaveDeIdempotenciaEnUsoException();
        }
        VentaRegistrada venta = ventas.leer(empresa, existente.ventaId()).orElseThrow(() -> new IllegalStateException(
                "La venta " + existente.ventaId() + " desapareció dentro de su propia transacción."));
        return new ResultadoDeVenta(venta, true);
    }

    // ------------------------------------------------------------ productos nuevos

    /**
     * La solicitud con cada línea de producto nuevo convertida en una línea del producto que se acaba
     * de crear (en el orden de la solicitud, para que los códigos sigan ese orden), y todas las líneas
     * por producto, como las que ya venían. El producto nuevo entra a los de la venta con existencia
     * cero y su costo provisional.
     */
    private SolicitudVenta conProductosNuevos(Integer empresa, SolicitudVenta s, Map<Integer, ProductoBloqueado> productos) {
        List<LineaSolicitada> resueltas = new ArrayList<>(s.lineas().size());
        for (LineaSolicitada l : s.lineas()) {
            if (l.productoNuevo() == null) {
                resueltas.add(l);
                continue;
            }
            Creado creado = productosNuevos.crear(empresa, l.productoNuevo());
            productos.put(creado.id(), new ProductoBloqueado(creado.id(), creado.nombre(), 0, creado.costo()));
            resueltas.add(new LineaSolicitada(creado.id(), null, l.cantidad(), l.descuento(), l.entregaEstimada()));
        }
        resueltas.sort(Comparator.comparing(LineaSolicitada::productoId));
        return new SolicitudVenta(s.claveIdempotencia(), s.clienteId(), List.copyOf(resueltas), s.descuentoTotal(),
                s.pagos(), s.motivoEncargo(), s.autorizacion(), s.domicilio());
    }

    // ------------------------------------------------------------ cliente

    private Cliente cliente(Integer empresa, Integer pedido) {
        if (pedido == null) {
            return clientes.buscarConsumidorFinal(empresa).orElseThrow(() -> new IllegalStateException(
                    "La empresa " + empresa + " no tiene su Consumidor final: ni el alta ni la V41 lo sembraron."));
        }
        return clientes.buscar(empresa, pedido)
                .orElseThrow(() -> new RecursoNoEncontradoException("El cliente no existe."));
    }

    /**
     * La ciudad del domicilio: la que se envió o, si vino vacía, la de la empresa («la ciudad cuando no
     * sea local», diseño §6). Sin ninguna de las dos es un 400, no una venta con la ciudad en blanco.
     */
    private String ciudadDelDomicilio(Integer empresa, Domicilio domicilio) {
        if (domicilio.ciudad() != null) {
            return domicilio.ciudad();
        }
        return clientes.ciudadDeLaEmpresa(empresa).map(String::trim).filter(c -> !c.isEmpty())
                .orElseThrow(() -> new DatosDeDomicilioIncompletosException(
                        "La ciudad del domicilio es obligatoria: no se envió y la empresa no tiene una por omisión."));
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

    /** Lo que se cobra después: sin pagos y sin cambio, y con un total que se pueda cobrar. */
    private static PagosValidados sinPagos(BigDecimal total) {
        if (total.signum() <= 0) {
            throw new VentaInvalidaException(TOTAL_POR_COBRAR_EN_CERO);
        }
        return new PagosValidados(List.of(), BigDecimal.ZERO.setScale(2));
    }

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

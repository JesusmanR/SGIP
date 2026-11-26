package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.Cliente;
import co.com.bspstore.sgip.api.dto.CotizacionDetalle;
import co.com.bspstore.sgip.api.dto.CotizacionEnLista;
import co.com.bspstore.sgip.api.dto.LineaCotizada;
import co.com.bspstore.sgip.api.dto.LineaDeCotizacion;
import co.com.bspstore.sgip.api.dto.PaginaDeCotizaciones;
import co.com.bspstore.sgip.api.dto.SolicitudCotizacion;
import co.com.bspstore.sgip.datos.RepositorioCalendarioHabil;
import co.com.bspstore.sgip.datos.RepositorioClientes;
import co.com.bspstore.sgip.datos.RepositorioConsecutivos;
import co.com.bspstore.sgip.datos.RepositorioCotizaciones;
import co.com.bspstore.sgip.datos.RepositorioCotizaciones.Cabecera;
import co.com.bspstore.sgip.datos.RepositorioCotizaciones.Filtro;
import co.com.bspstore.sgip.datos.RepositorioCotizaciones.NuevaCotizacion;
import co.com.bspstore.sgip.datos.RepositorioCotizaciones.NuevaLinea;
import co.com.bspstore.sgip.datos.RepositorioCotizaciones.Tomada;
import co.com.bspstore.sgip.datos.RepositorioVentas;
import co.com.bspstore.sgip.datos.RepositorioVentas.PrecioVigente;
import co.com.bspstore.sgip.logica.cotizacion.VigenciaHabil;
import co.com.bspstore.sgip.logica.venta.CalculoDeVenta;
import co.com.bspstore.sgip.logica.venta.LimitesDeVenta;
import co.com.bspstore.sgip.logica.venta.LineaCalculada;
import co.com.bspstore.sgip.logica.venta.LineaPedida;
import co.com.bspstore.sgip.logica.venta.LineaVendible;
import co.com.bspstore.sgip.logica.venta.VentaCalculada;
import co.com.bspstore.sgip.modelo.TipoConsecutivo;
import co.com.bspstore.sgip.util.Hoy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Crear y ver una cotización (RF-37). La cotización congela exactamente lo que hoy cobraría la caja: el precio, la
 * tarifa y el tratamiento de cada línea, con la misma regla de la venta ({@link LineaVendible}) y el mismo cálculo
 * ({@link CalculoDeVenta}), de modo que el impuesto sale redondeado por línea a pesos y cuadra con la venta en que
 * se convierta.
 *
 * <h2>Crear</h2>
 *
 * Corre en READ COMMITTED, como todo lo que numera. No mueve dinero ni inventario, así que no pide turno ni toma
 * bloqueos de productos. Todo lo que puede fallar se decide antes de tomar el consecutivo (el cliente, los
 * productos y sus precios, los topes, el cálculo y la vigencia); el consecutivo es lo último que se toma, justo
 * antes de insertar, y si el INSERT falla la transacción se revierte y el número queda libre.
 *
 * <h2>Leer</h2>
 *
 * El estado {@code VENCIDA} se calcula con {@code Hoy.de(reloj)}: una cotización vigente cuyo vencimiento ya pasó.
 * Una vencida muestra, junto al precio congelado, el precio de hoy de cada línea, o que ya no está disponible.
 *
 * <h2>Lista y búsqueda por número</h2>
 *
 * La lista muestra todas las cotizaciones de la empresa, no solo las del usuario, para que cualquier cajero
 * encuentre la que va a cobrar. Cuesta dos consultas (la página y el total) sin importar cuántas haya. El número
 * se escribe como sea ({@code 12}, {@code cot-12}, {@code COT-00000012}) y se convierte a {@code COT-00000012}
 * para buscarlo por igualdad exacta.
 *
 * <h2>Anular</h2>
 *
 * Toma la fila con {@code FOR UPDATE} antes de mirar su estado, de modo que no compite con la conversión en venta:
 * quien llega segundo espera y ve el estado ya confirmado. El permiso (el administrador, o el vendedor que la
 * creó) se comprueba antes que el estado.
 */
@Service
public class ServicioCotizaciones {

    private static final Logger LOG = LoggerFactory.getLogger(ServicioCotizaciones.class);

    /** Días que se suman a la ventana de festivos: sobra para los festivos de cualquier vigencia. */
    private static final int MARGEN_DE_FESTIVOS = 30;

    static final String PRODUCTO_NO_EXISTE = "El producto no existe.";
    static final String CLIENTE_NO_EXISTE = "El cliente no existe.";
    static final String COTIZACION_NO_EXISTE = "La cotización no existe.";
    static final String ESTADO_NO_VALIDO = "El estado no es válido.";
    static final String MOTIVO_NO_VALIDO = "El motivo es obligatorio y admite hasta 200 caracteres.";
    static final String SIN_PERMISO_PARA_ANULAR = "Solo el administrador o quien la creó la anula.";
    static final int TAMANO_MAXIMO = 100;
    static final int MOTIVO_MAXIMO = 200;
    private static final List<String> ESTADOS = List.of("VIGENTE", "VENCIDA", "CONVERTIDA", "ANULADA");
    /** Lo que se acepta como número: con o sin {@code COT-}, sin distinguir mayúsculas, y solo cifras después. */
    private static final Pattern NUMERO_ESCRITO = Pattern.compile("(?i)(?:COT-)?(\\d+)");
    static final String CANTIDAD_FUERA_DE_RANGO =
            "La cantidad de una línea va de 1 a " + LimitesDeVenta.CANTIDAD + " unidades.";

    private final RepositorioCotizaciones cotizaciones;
    private final RepositorioVentas ventas;
    private final RepositorioClientes clientes;
    private final RepositorioConsecutivos consecutivos;
    private final RepositorioCalendarioHabil calendario;
    private final Clock reloj;

    public ServicioCotizaciones(RepositorioCotizaciones cotizaciones, RepositorioVentas ventas,
                                RepositorioClientes clientes, RepositorioConsecutivos consecutivos,
                                RepositorioCalendarioHabil calendario, Clock reloj) {
        this.cotizaciones = cotizaciones;
        this.ventas = ventas;
        this.clientes = clientes;
        this.consecutivos = consecutivos;
        this.calendario = calendario;
        this.reloj = reloj;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CotizacionDetalle crear(Integer empresaId, Integer usuarioId, SolicitudCotizacion s) {
        LocalDate hoy = Hoy.de(reloj);

        // 1. El cliente de la empresa (404) o, sin cliente, el Consumidor final.
        Integer clienteId = cliente(empresaId, s.clienteId()).id();

        // 2. Un producto repetido se suma en una sola línea; las líneas quedan por producto.
        Map<Integer, Integer> cantidades = agrupar(s.lineas());
        List<Integer> ids = new ArrayList<>(cantidades.keySet());
        if (cotizaciones.productosDeLaEmpresa(empresaId, ids).size() != ids.size()) {
            throw new RecursoNoEncontradoException(PRODUCTO_NO_EXISTE);
        }

        // 3. El precio de hoy, con la regla de la caja: sin precio o inactivo no se cotiza (400).
        List<LineaPedida> pedidas = lineasConPrecio(empresaId, ids, cantidades);

        // 4. Los mismos topes de la venta y 5. el cálculo.
        LineaVendible.toparSubtotal(pedidas);
        VentaCalculada calculada = calcular(pedidas);

        // 6. La vigencia, en días hábiles de la empresa.
        int vigenciaDias = calendario.vigenciaDeLaEmpresa(empresaId);
        LocalDate vence = vencimiento(hoy, vigenciaDias);

        // 7. El consecutivo, lo último antes de insertar.
        String numero = "COT-%08d".formatted(consecutivos.tomarSiguiente(empresaId, TipoConsecutivo.COTIZACION));

        // 8 y 9. La cabecera y las líneas.
        Long id = cotizaciones.insertarCabecera(new NuevaCotizacion(empresaId, numero, clienteId, usuarioId, hoy, vence,
                vigenciaDias, calculada.subtotal(), calculada.iva(), texto(s.observaciones())));
        List<NuevaLinea> lineas = new ArrayList<>(calculada.lineas().size());
        for (LineaCalculada l : calculada.lineas()) {
            lineas.add(new NuevaLinea(l.productoId(), l.cantidad(), l.precioUnitario(), l.tarifaIva(),
                    l.tratamientoIva(), l.subtotal(), l.iva()));
        }
        cotizaciones.insertarLineas(empresaId, id, lineas);

        return leer(empresaId, id, hoy);
    }

    /** La cotización de la empresa con sus líneas; 404 si no existe o es de otra empresa. */
    @Transactional(readOnly = true)
    public CotizacionDetalle detalle(Integer empresaId, Long id) {
        return leer(empresaId, id, Hoy.de(reloj));
    }

    /**
     * Una página de las cotizaciones de la empresa, la más reciente primero. {@code estado} es uno de
     * {@code VIGENTE}, {@code VENCIDA}, {@code CONVERTIDA} o {@code ANULADA} (nulo o en blanco, todas) y calcula
     * {@code VENCIDA} con el día de hoy de Bogotá. {@code texto} busca por número exacto, por el comienzo de la
     * identificación del cliente o por una parte de su nombre, al pie de la letra y sin distinguir mayúsculas.
     * {@code pagina} empieza en 0 y {@code tamano} en 1, con 100 como máximo; fuera de rango es un 400, igual que
     * un estado que no existe.
     */
    @Transactional(readOnly = true)
    public PaginaDeCotizaciones listar(Integer empresaId, String estado, String texto, int pagina, int tamano) {
        if (pagina < 0) {
            throw new FiltroInvalidoException("La página no puede ser negativa.");
        }
        if (tamano < 1) {
            throw new FiltroInvalidoException("El tamaño de la página debe ser al menos 1.");
        }
        int aplicado = Math.min(tamano, TAMANO_MAXIMO);
        LocalDate hoy = Hoy.de(reloj);
        Filtro filtro = filtro(estado, texto);
        List<CotizacionEnLista> elementos = cotizaciones.listar(empresaId, hoy, filtro, aplicado,
                (long) pagina * aplicado);
        return new PaginaDeCotizaciones(elementos, pagina, aplicado, cotizaciones.contar(empresaId, hoy, filtro));
    }

    /**
     * La cotización de la empresa con ese número, escrito como sea: {@code 12}, {@code cot-12} o
     * {@code COT-00000012}. Un texto que no es un número, o un número que la empresa no tiene (incluido uno que
     * solo existe en otra empresa), es un 404.
     */
    @Transactional(readOnly = true)
    public CotizacionDetalle porNumero(Integer empresaId, String numero) {
        String normalizado = normalizarNumero(numero).orElseThrow(() ->
                new RecursoNoEncontradoException(COTIZACION_NO_EXISTE));
        Long id = cotizaciones.idPorNumero(empresaId, normalizado).orElseThrow(() ->
                new RecursoNoEncontradoException(COTIZACION_NO_EXISTE));
        return leer(empresaId, id, Hoy.de(reloj));
    }

    /**
     * Anula una cotización vigente o vencida y guarda quién, cuándo y por qué. Puede el administrador, o el
     * vendedor que la creó (403 si no). Una anulada o convertida es un 409; una de otra empresa o inexistente,
     * un 404. El motivo se recorta y debe quedar de 1 a 200 caracteres (400).
     *
     * La fila se toma con {@code FOR UPDATE} antes de mirar su estado, así que no compite con la conversión en
     * venta. Una vencida sigue guardada como {@code VIGENTE}, por lo que se anula igual que una vigente.
     */
    @Transactional
    public CotizacionDetalle anular(Integer empresaId, Integer usuarioId, boolean esAdministrador, Long id,
                                    String motivo) {
        String limpio = texto(motivo);
        if (limpio == null || limpio.length() > MOTIVO_MAXIMO) {
            throw new FiltroInvalidoException(MOTIVO_NO_VALIDO);
        }
        Tomada tomada = cotizaciones.tomar(empresaId, id)
                .orElseThrow(() -> new RecursoNoEncontradoException(COTIZACION_NO_EXISTE));
        if (!esAdministrador && !tomada.usuarioId().equals(usuarioId)) {
            throw new AccessDeniedException(SIN_PERMISO_PARA_ANULAR);
        }
        if (!"VIGENTE".equals(tomada.estado())) {
            throw new CotizacionNoAnulableException();
        }
        cotizaciones.anular(empresaId, id, usuarioId, Hoy.ahora(reloj), limpio);
        return leer(empresaId, id, Hoy.de(reloj));
    }

    // ------------------------------------------------------------ lista

    private static Filtro filtro(String estado, String texto) {
        String pedido = texto(estado);
        String normalizado = pedido == null ? null : pedido.toUpperCase(Locale.ROOT);
        if (normalizado != null && !ESTADOS.contains(normalizado)) {
            throw new FiltroInvalidoException(ESTADO_NO_VALIDO);
        }
        String buscado = texto(texto);
        if (buscado == null) {
            return new Filtro(normalizado, null, null, null);
        }
        String literal = escaparComodines(buscado);
        return new Filtro(normalizado, normalizarNumero(buscado).orElse(null), literal + "%", "%" + literal + "%");
    }

    /** El número escrito como {@code COT-%08d}; vacío si no es un número que pueda existir. */
    static Optional<String> normalizarNumero(String escrito) {
        if (escrito == null) {
            return Optional.empty();
        }
        Matcher m = NUMERO_ESCRITO.matcher(escrito.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        String cifras = m.group(1).replaceFirst("^0+", "");
        if (cifras.isEmpty() || cifras.length() > 8) {
            return Optional.empty();
        }
        return Optional.of("COT-%08d".formatted(Integer.parseInt(cifras)));
    }

    /** La barra invertida, el porcentaje y el guion bajo valen por sí mismos en un LIKE: se les antepone la barra. */
    private static String escaparComodines(String texto) {
        return texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ------------------------------------------------------------ crear

    private Cliente cliente(Integer empresaId, Integer pedido) {
        if (pedido == null) {
            return clientes.buscarConsumidorFinal(empresaId).orElseThrow(() -> new IllegalStateException(
                    "La empresa " + empresaId + " no tiene su Consumidor final: ni el alta ni la V41 lo sembraron."));
        }
        return clientes.buscar(empresaId, pedido)
                .orElseThrow(() -> new RecursoNoEncontradoException(CLIENTE_NO_EXISTE));
    }

    /** Las cantidades por producto, sumando las líneas repetidas y ordenadas por producto. */
    private static Map<Integer, Integer> agrupar(List<LineaCotizada> lineas) {
        Map<Integer, Long> sumas = new TreeMap<>();
        for (LineaCotizada l : lineas) {
            sumas.merge(l.productoId(), (long) l.cantidad(), Long::sum);
        }
        Map<Integer, Integer> cantidades = new TreeMap<>();
        for (Map.Entry<Integer, Long> e : sumas.entrySet()) {
            if (e.getValue() > LimitesDeVenta.CANTIDAD) {
                throw new VentaInvalidaException(CANTIDAD_FUERA_DE_RANGO);
            }
            cantidades.put(e.getKey(), e.getValue().intValue());
        }
        return cantidades;
    }

    private List<LineaPedida> lineasConPrecio(Integer empresaId, List<Integer> ids, Map<Integer, Integer> cantidades) {
        Map<Integer, PrecioVigente> precios = new HashMap<>();
        for (PrecioVigente p : ventas.preciosVigentes(empresaId, ids)) {
            precios.put(p.productoId(), p);
        }
        List<LineaPedida> pedidas = new ArrayList<>(ids.size());
        for (Integer producto : ids) {
            PrecioVigente vigente = precios.get(producto);
            LineaPedida p = vigente == null ? null
                    : LineaVendible.de(producto, cantidades.get(producto), null, vigente).orElse(null);
            if (p == null) {
                throw new VentaInvalidaException(LineaVendible.NO_VENDIBLE);
            }
            pedidas.add(p);
        }
        return pedidas;
    }

    /** El texto sin espacios sobrantes; vacío es nulo. */
    private static String texto(String valor) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.strip();
        return limpio.isEmpty() ? null : limpio;
    }

    private static VentaCalculada calcular(List<LineaPedida> pedidas) {
        try {
            return CalculoDeVenta.calcular(pedidas, null);
        } catch (IllegalArgumentException e) {
            // Lo que el cálculo rechaza así ya se comprobó antes; si algo se cuela, es un dato que no se puede
            // cotizar y no un 500. Se registra solo la clase, nunca el dato.
            LOG.warn("El cálculo de la cotización rechazó una solicitud que pasó la frontera: {}",
                    e.getClass().getSimpleName());
            throw new VentaInvalidaException(LineaVendible.IMPORTE_DESMEDIDO);
        }
    }

    /**
     * El día en que vence: {@code vigenciaDias} días hábiles después de {@code hoy}. Se piden los festivos de una
     * ventana que siempre cubre el vencimiento (cinco días hábiles caben en siete corridos, más un margen). Si algún
     * año del rango no tiene festivos cargados, no se bloquea: solo se saltan los fines de semana y el log avisa.
     */
    private LocalDate vencimiento(LocalDate hoy, int vigenciaDias) {
        LocalDate hasta = hoy.plusDays(vigenciaDias * 7L / 5 + MARGEN_DE_FESTIVOS);
        Set<LocalDate> festivos = calendario.festivosEntre(hoy, hasta);
        LocalDate vence = VigenciaHabil.vence(hoy, vigenciaDias, festivos);
        Set<Integer> cargados = calendario.aniosConFestivos(hoy.getYear(), vence.getYear());
        List<Integer> sinFestivos = new ArrayList<>();
        for (int anio = hoy.getYear(); anio <= vence.getYear(); anio++) {
            if (!cargados.contains(anio)) {
                sinFestivos.add(anio);
            }
        }
        if (!sinFestivos.isEmpty()) {
            LOG.warn("calendario_habil no tiene festivos cargados para {}: la vigencia de las cotizaciones solo "
                    + "salta fines de semana en esos años.", sinFestivos);
        }
        return vence;
    }

    // ------------------------------------------------------------ leer

    private CotizacionDetalle leer(Integer empresaId, Long id, LocalDate hoy) {
        Cabecera c = cotizaciones.cabecera(empresaId, id, hoy)
                .orElseThrow(() -> new RecursoNoEncontradoException(COTIZACION_NO_EXISTE));
        List<RepositorioCotizaciones.Linea> guardadas = cotizaciones.lineas(empresaId, id);
        boolean vencida = "VENCIDA".equals(c.estado());
        Map<Integer, PrecioVigente> deHoy = new HashMap<>();
        if (vencida) {
            List<Integer> productos = guardadas.stream().map(RepositorioCotizaciones.Linea::productoId).toList();
            for (PrecioVigente p : ventas.preciosVigentes(empresaId, productos)) {
                deHoy.put(p.productoId(), p);
            }
        }
        List<LineaDeCotizacion> lineas = new ArrayList<>(guardadas.size());
        for (RepositorioCotizaciones.Linea l : guardadas) {
            PrecioVigente vigente = deHoy.get(l.productoId());
            Optional<LineaPedida> vendibleHoy = vigente == null ? Optional.empty()
                    : LineaVendible.de(l.productoId(), l.cantidad(), null, vigente);
            lineas.add(new LineaDeCotizacion(l.productoId(), l.codigo(), l.nombre(), l.cantidad(), l.precioUnitario(),
                    l.tarifaIva(), l.tratamientoIva(), l.subtotal(), l.valorIva(),
                    vendibleHoy.map(LineaPedida::precioUnitario).orElse(null),
                    vencida ? vendibleHoy.isPresent() : l.activo()));
        }
        return new CotizacionDetalle(c.id(), c.numero(), c.estado(), c.fechaEmision(), c.fechaVencimiento(),
                c.vigenciaDias(), c.clienteId(), c.clienteNombre(), c.clienteIdentificacion(), c.usuarioId(),
                c.creadaPor(), lineas, c.subtotal(), c.iva(), c.total(), c.observaciones(), c.ventaId(),
                c.motivoAnulacion());
    }
}

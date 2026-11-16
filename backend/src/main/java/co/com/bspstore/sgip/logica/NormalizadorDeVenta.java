package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.Domicilio;
import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.api.dto.ProductoNuevo;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.logica.venta.Descuento;
import co.com.bspstore.sgip.logica.venta.LimitesDeVenta;
import co.com.bspstore.sgip.logica.venta.TipoDescuento;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * La forma de una solicitud de venta: lo que se puede comprobar sin la base. Valida (400 con texto
 * fijo, sin eco de lo enviado), limpia los textos, fusiona las líneas repetidas y las deja
 * ordenadas por producto, y calcula la huella de la carga.
 *
 * Es la frontera que {@code CalculoDeVenta} pide en su documentación: nada que llegue de aquí
 * al cálculo puede ser una cantidad fuera de rango, un descuento con más cifras de las que admite
 * ni un importe desmedido. Las anotaciones del DTO son la primera puerta cuando la solicitud llega
 * por la API; esta clase es la segunda, porque el servicio no depende de quién lo llame.
 *
 * Lo que depende de la base (que el producto exista, su precio, la existencia, los medios de pago,
 * que los pagos sumen el total) lo decide {@code RegistradorDeVenta}.
 */
final class NormalizadorDeVenta {

    static final String CLIENTE_PARA_DOMICILIO =
            "Un domicilio necesita un cliente identificado: el Consumidor final no sirve.";

    private static final int MAX_CIFRAS_ENTERAS = 12;
    private static final int MAX_DECIMALES = 2;
    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    private NormalizadorDeVenta() {}

    /** Hasta cuándo puede prometerse un encargo, contado desde hoy. */
    private static final int ANOS_MAXIMOS_DE_ENTREGA = 1;

    /**
     * La solicitud validada y normalizada, con todas las reglas: la forma ({@link #forma}) y las que
     * dependen del día ({@link #fechas}). {@code hoy} es el día de Bogotá.
     */
    static SolicitudVenta normalizar(SolicitudVenta s, LocalDate hoy) {
        return fechas(forma(s), hoy);
    }

    /**
     * Las reglas que dependen del día: la fecha de entrega de un encargo va de hoy a un año. Se
     * separan de la forma porque un reintento de una venta ya registrada tiene que reconocerse
     * aunque su fecha de entrega ya sea de ayer; solo una venta nueva las cumple.
     */
    static SolicitudVenta fechas(SolicitudVenta s, LocalDate hoy) {
        LocalDate limite = hoy.plusYears(ANOS_MAXIMOS_DE_ENTREGA);
        for (LineaSolicitada l : s.lineas()) {
            LocalDate entrega = l.entregaEstimada();
            if (entrega != null && entrega.isBefore(hoy)) {
                throw invalida("La fecha estimada de entrega no puede ser anterior a hoy.");
            }
            if (entrega != null && entrega.isAfter(limite)) {
                throw invalida("La fecha estimada de entrega no puede pasar de un año desde hoy.");
            }
        }
        return s;
    }

    /**
     * La solicitud validada y normalizada en su forma, sin las reglas que dependen del día: líneas
     * fusionadas y ordenadas por producto, textos sin espacios sobrantes (vacío es nulo) y el motivo
     * del encargo recortado. Es lo que la huella necesita.
     */
    static SolicitudVenta forma(SolicitudVenta s) {
        if (s == null) {
            throw invalida("La venta está vacía.");
        }
        if (s.claveIdempotencia() == null) {
            throw invalida("La clave de idempotencia es obligatoria.");
        }
        if (s.clienteId() != null && s.clienteId() <= 0) {
            throw invalida("El cliente no es válido.");
        }
        Domicilio domicilio = domicilio(s.domicilio(), s.clienteId());
        String motivo = texto(s.motivoEncargo());
        if (motivo != null && motivo.length() > LimitesDeVenta.MOTIVO_DEL_ENCARGO) {
            throw invalida("El motivo del encargo admite hasta " + LimitesDeVenta.MOTIVO_DEL_ENCARGO + " caracteres.");
        }
        validar(s.descuentoTotal());
        List<LineaSolicitada> lineas = fusionar(s.lineas());
        // El producto nuevo nace con existencia cero: toda su venta es un encargo y el encargo lleva
        // su motivo (la fecha de entrega, por línea, se exigió al fusionarlas).
        if (motivo == null && lineas.stream().anyMatch(l -> l.productoNuevo() != null)) {
            throw invalida("Un producto nuevo se vende como encargo y necesita el motivo.");
        }
        // Un domicilio contra entrega se cobra al entregarlo: no lleva pagos. Todo lo demás los lleva.
        List<PagoSolicitado> pagos = domicilio != null && domicilio.contraEntrega()
                ? sinPagos(s.pagos())
                : limpiar(s.pagos());
        return new SolicitudVenta(s.claveIdempotencia(), s.clienteId(), lineas, s.descuentoTotal(), pagos, motivo,
                s.autorizacion(), domicilio);
    }

    // ------------------------------------------------------------ líneas

    private static List<LineaSolicitada> fusionar(List<LineaSolicitada> pedidas) {
        if (pedidas == null || pedidas.isEmpty()) {
            throw invalida("La venta necesita al menos una línea.");
        }
        if (pedidas.size() > LimitesDeVenta.LINEAS) {
            throw invalida("La venta admite como máximo " + LimitesDeVenta.LINEAS + " líneas.");
        }
        Map<Integer, LineaSolicitada> porProducto = new TreeMap<>();
        List<LineaSolicitada> nuevas = new ArrayList<>();
        for (LineaSolicitada l : pedidas) {
            if (l == null) {
                throw invalida("Una línea de la venta está vacía.");
            }
            if ((l.productoId() == null) == (l.productoNuevo() == null)) {
                throw invalida("Cada línea lleva un producto o un producto nuevo, y no los dos ni ninguno.");
            }
            if (l.productoId() != null && l.productoId() <= 0) {
                throw invalida("El producto no es válido.");
            }
            if (l.cantidad() < 1 || l.cantidad() > LimitesDeVenta.CANTIDAD) {
                throw invalida("La cantidad de una línea va de 1 a " + LimitesDeVenta.CANTIDAD + " unidades.");
            }
            validar(l.descuento());
            if (l.productoNuevo() != null) {
                // Cada línea de producto nuevo crea el suyo: nunca se fusionan dos.
                if (l.entregaEstimada() == null) {
                    throw invalida("Un producto nuevo se vende como encargo y necesita la fecha estimada de entrega.");
                }
                if (nuevas.size() >= LimitesDeVenta.PRODUCTOS_NUEVOS) {
                    throw invalida("Una venta admite como máximo " + LimitesDeVenta.PRODUCTOS_NUEVOS
                            + " productos nuevos.");
                }
                nuevas.add(new LineaSolicitada(null, productoNuevo(l.productoNuevo()), l.cantidad(), l.descuento(),
                        l.entregaEstimada()));
                continue;
            }
            LineaSolicitada previa = porProducto.get(l.productoId());
            if (previa == null) {
                porProducto.put(l.productoId(), l);
                continue;
            }
            if (!mismoDescuento(previa.descuento(), l.descuento())) {
                throw invalida("Un mismo producto no puede repetirse con descuentos distintos.");
            }
            if (!Objects.equals(previa.entregaEstimada(), l.entregaEstimada())) {
                throw invalida("Un mismo producto no puede repetirse con fechas de entrega distintas.");
            }
            long suma = (long) previa.cantidad() + l.cantidad();
            if (suma > LimitesDeVenta.CANTIDAD) {
                throw invalida("La cantidad de una línea va de 1 a " + LimitesDeVenta.CANTIDAD + " unidades.");
            }
            porProducto.put(l.productoId(), new LineaSolicitada(l.productoId(), null, (int) suma,
                    previa.descuento(), previa.entregaEstimada()));
        }
        List<LineaSolicitada> lineas = new ArrayList<>(porProducto.values());
        lineas.addAll(nuevas);
        return List.copyOf(lineas);
    }

    // ------------------------------------------------------------ el domicilio

    /**
     * El domicilio con sus textos limpios: dirección, teléfono y quien recibe obligatorios (400 con el
     * nombre de lo que falta, sin el valor), ciudad opcional (vacía es nula y la venta toma la de la
     * empresa) y cada texto del tamaño de su columna. Exige un cliente identificado: sin él la venta
     * sería al Consumidor final, que no sirve para un domicilio; que el cliente tenga además sus datos
     * lo comprueba la venta contra la base.
     */
    static Domicilio domicilio(Domicilio d, Integer clienteId) {
        if (d == null) {
            return null;
        }
        if (clienteId == null) {
            throw new DatosDeDomicilioIncompletosException(CLIENTE_PARA_DOMICILIO);
        }
        String direccion = texto(d.direccion());
        String ciudad = texto(d.ciudad());
        String telefono = texto(d.telefono());
        String recibe = texto(d.recibe());
        List<String> faltan = new ArrayList<>();
        if (direccion == null) {
            faltan.add("la dirección");
        }
        if (telefono == null) {
            faltan.add("el teléfono");
        }
        if (recibe == null) {
            faltan.add("quien recibe");
        }
        if (!faltan.isEmpty()) {
            throw new DatosDeDomicilioIncompletosException("Para un domicilio falta " + String.join(", ", faltan) + ".");
        }
        acotar(direccion, 200, "La dirección");
        acotar(ciudad, 80, "La ciudad");
        acotar(telefono, 20, "El teléfono");
        acotar(recibe, 120, "El nombre de quien recibe");
        return new Domicilio(direccion, ciudad, telefono, recibe, d.contraEntrega());
    }

    private static void acotar(String texto, int maximo, String que) {
        if (texto != null && texto.length() > maximo) {
            throw invalida(que + " admite hasta " + maximo + " caracteres.");
        }
    }

    private static List<PagoSolicitado> sinPagos(List<PagoSolicitado> pedidos) {
        if (pedidos != null && !pedidos.isEmpty()) {
            throw invalida("Un domicilio contra entrega no lleva pagos: el dinero se cobra al entregarlo.");
        }
        return List.of();
    }

    // ------------------------------------------------------------ el producto nuevo

    /**
     * El producto nuevo con sus textos limpios: nombre obligatorio y del tamaño de su columna, código
     * de barras opcional (en blanco es nulo) y precio de venta SIN IVA, un entero positivo de hasta 12
     * cifras. El precio se mira con {@code scale()} y {@code precision()}, que no operan: un
     * {@code 1E+99999999} no cuelga el hilo. Lo usan la forma de la solicitud y el servicio que crea el
     * producto, de modo que la regla es una sola y los textos no repiten lo enviado.
     */
    static ProductoNuevo productoNuevo(ProductoNuevo p) {
        if (p == null) {
            throw invalida("El producto nuevo está vacío.");
        }
        String nombre = texto(p.nombre());
        if (nombre == null) {
            throw invalida("El producto nuevo necesita un nombre.");
        }
        if (nombre.length() > LimitesDeVenta.NOMBRE_DEL_PRODUCTO) {
            throw invalida("El nombre del producto admite hasta " + LimitesDeVenta.NOMBRE_DEL_PRODUCTO + " caracteres.");
        }
        String barras = texto(p.codigoBarras());
        if (barras != null && barras.length() > LimitesDeVenta.CODIGO_DE_BARRAS) {
            throw invalida("El código de barras admite hasta " + LimitesDeVenta.CODIGO_DE_BARRAS + " caracteres.");
        }
        BigDecimal precio = p.precioVenta();
        if (precio == null || !cabe(precio) || precio.signum() <= 0 || !enteroEnPesos(precio)) {
            throw invalida("El precio de venta del producto nuevo va sin IVA, en pesos enteros, mayor que cero y de hasta "
                    + MAX_CIFRAS_ENTERAS + " dígitos.");
        }
        return new ProductoNuevo(nombre, precio.setScale(0, java.math.RoundingMode.UNNECESSARY), barras);
    }

    private static boolean mismoDescuento(Descuento a, Descuento b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.tipo() == b.tipo() && a.valor().compareTo(b.valor()) == 0;
    }

    // ------------------------------------------------------------ descuentos

    /**
     * Un descuento que se envía es de verdad: tipo y valor, mayor que cero, de hasta 12 cifras enteras
     * y dos decimales, y en porcentaje no más de 100. Se mira con {@code scale()} y {@code precision()},
     * que no operan: un {@code 1E+99999999} no cuelga el hilo.
     */
    private static void validar(Descuento d) {
        if (d == null) {
            return;
        }
        if (d.tipo() == null || d.valor() == null) {
            throw invalida("El descuento necesita un tipo y un valor.");
        }
        if (!cabe(d.valor())) {
            throw invalida("El descuento admite hasta doce enteros y dos decimales.");
        }
        if (d.valor().signum() <= 0) {
            throw invalida("El descuento debe ser mayor que cero.");
        }
        if (d.tipo() == TipoDescuento.PORCENTAJE && d.valor().compareTo(CIEN) > 0) {
            throw invalida("Un descuento en porcentaje no puede pasar de 100.");
        }
        if (d.tipo() == TipoDescuento.PESOS && !enteroEnPesos(d.valor())) {
            throw invalida("Un descuento en pesos debe ser un monto entero.");
        }
    }

    // ------------------------------------------------------------ pagos

    private static List<PagoSolicitado> limpiar(List<PagoSolicitado> pedidos) {
        if (pedidos == null || pedidos.isEmpty()) {
            throw invalida("La venta necesita al menos un pago.");
        }
        if (pedidos.size() > LimitesDeVenta.PAGOS) {
            throw invalida("La venta admite como máximo " + LimitesDeVenta.PAGOS + " pagos.");
        }
        List<PagoSolicitado> limpios = new ArrayList<>(pedidos.size());
        for (PagoSolicitado p : pedidos) {
            if (p == null) {
                throw invalida("Un pago de la venta está vacío.");
            }
            if (p.medioId() == null || p.medioId() <= 0) {
                throw invalida("El pago necesita un medio de pago.");
            }
            BigDecimal monto = enPesos(p.monto(), "El monto del pago");
            BigDecimal recibido = p.recibido() == null ? null : enPesos(p.recibido(), "El efectivo recibido");
            String referencia = texto(p.referencia());
            if (referencia != null && referencia.length() > LimitesDeVenta.REFERENCIA) {
                throw invalida("La referencia admite hasta " + LimitesDeVenta.REFERENCIA + " caracteres.");
            }
            limpios.add(new PagoSolicitado(p.medioId(), monto, recibido, referencia));
        }
        return List.copyOf(limpios);
    }

    /** Un monto en pesos enteros, mayor que cero y de hasta 12 cifras, sin decimales ni exponente. */
    private static BigDecimal enPesos(BigDecimal valor, String que) {
        if (valor == null || !cabe(valor) || valor.signum() <= 0 || !enteroEnPesos(valor)) {
            throw invalida(que + " va en pesos enteros, mayor que cero y de hasta " + MAX_CIFRAS_ENTERAS + " dígitos.");
        }
        return valor.setScale(0, java.math.RoundingMode.UNNECESSARY);
    }

    // ------------------------------------------------------------ números

    /** A lo sumo dos decimales y doce cifras enteras, mirando solo la escala y la precisión. */
    private static boolean cabe(BigDecimal valor) {
        return valor.scale() <= MAX_DECIMALES
                && (long) valor.precision() - valor.scale() <= MAX_CIFRAS_ENTERAS;
    }

    /** Sin centavos distintos de cero. Solo se llama con un número que ya {@link #cabe}. */
    private static boolean enteroEnPesos(BigDecimal valor) {
        return valor.signum() == 0 || valor.stripTrailingZeros().scale() <= 0;
    }

    private static String texto(String valor) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.trim();
        return limpio.isEmpty() ? null : limpio;
    }

    private static VentaInvalidaException invalida(String mensaje) {
        return new VentaInvalidaException(mensaje);
    }

    // ------------------------------------------------------------ la huella

    /**
     * El SHA-256 de la carga normalizada, en hexadecimal. Sin el PIN ni quién autoriza: la
     * autorización no es parte de la venta sino de la petición, y dos envíos de la misma venta
     * pueden llevar a administradores distintos. Líneas y pagos entran en el orden de la solicitud
     * normalizada (las líneas, por producto), de modo que partir una línea en dos o reordenarlas
     * no cambia la huella.
     */
    static String huella(SolicitudVenta n) {
        StringBuilder canon = new StringBuilder("venta1|");
        canon.append("cliente=").append(n.clienteId() == null ? "-" : n.clienteId().toString()).append('|');
        canon.append("descuento=").append(descuento(n.descuentoTotal())).append('|');
        canon.append("motivo=").append(n.motivoEncargo() == null ? "-" : n.motivoEncargo()).append('|');
        List<String> nuevas = new ArrayList<>();
        for (LineaSolicitada l : n.lineas()) {
            String cola = l.cantidad() + ":" + descuento(l.descuento()) + ":"
                    + (l.entregaEstimada() == null ? "-" : l.entregaEstimada().toString()) + "|";
            if (l.productoNuevo() != null) {
                ProductoNuevo p = l.productoNuevo();
                nuevas.add("LN:" + campo(p.nombre()) + ":" + plano(p.precioVenta()) + ":" + campo(p.codigoBarras()) + ":" + cola);
            } else {
                canon.append("L:").append(l.productoId()).append(':').append(cola);
            }
        }
        // Los productos nuevos entran después de las líneas del catálogo, y solo si los hay: la
        // huella de una venta sin ellos es la misma de siempre. Van ordenados por su propio texto
        // (como las líneas del catálogo van por producto), de modo que su orden no importa.
        nuevas.stream().sorted().forEach(canon::append);
        for (PagoSolicitado p : n.pagos()) {
            canon.append("P:").append(p.medioId()).append(':').append(plano(p.monto())).append(':')
                    .append(p.recibido() == null ? "-" : plano(p.recibido())).append(':')
                    .append(p.referencia() == null ? "-" : p.referencia()).append('|');
        }
        // El domicilio entra con todos sus campos, también la marca de contra entrega, y solo si lo hay.
        if (n.domicilio() != null) {
            Domicilio d = n.domicilio();
            canon.append("DOM:").append(campo(d.direccion())).append(':').append(campo(d.ciudad())).append(':')
                    .append(campo(d.telefono())).append(':').append(campo(d.recibe())).append(':')
                    .append(d.contraEntrega()).append('|');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canon.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("La JVM no trae SHA-256.", e);
        }
    }

    /**
     * Un texto libre en el canon: su longitud por delante, para que un «:» o un «|» dentro del texto no
     * pueda hacer pasar dos cargas distintas por la misma. Nulo y vacío no se confunden con ningún texto.
     */
    private static String campo(String texto) {
        return texto == null ? "-" : texto.length() + "#" + texto;
    }

    private static String descuento(Descuento d) {
        return d == null ? "-" : d.tipo() + "=" + plano(d.valor());
    }

    private static String plano(BigDecimal valor) {
        return valor.signum() == 0 ? "0" : valor.stripTrailingZeros().toPlainString();
    }
}

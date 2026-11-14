package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
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

    static final String PRODUCTO_NUEVO_NO_DISPONIBLE = "Los productos nuevos aún no están disponibles";
    static final String DOMICILIO_NO_DISPONIBLE = "Los domicilios aún no están disponibles";

    private static final int MAX_CIFRAS_ENTERAS = 12;
    private static final int MAX_DECIMALES = 2;
    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    private NormalizadorDeVenta() {}

    /**
     * La solicitud validada y normalizada: líneas fusionadas y ordenadas por producto, textos sin
     * espacios sobrantes (vacío es nulo) y el motivo del encargo recortado. {@code hoy} es el día
     * de Bogotá, contra el que se compara la fecha de entrega.
     */
    static SolicitudVenta normalizar(SolicitudVenta s, LocalDate hoy) {
        if (s == null) {
            throw invalida("La venta está vacía.");
        }
        if (s.claveIdempotencia() == null) {
            throw invalida("La clave de idempotencia es obligatoria.");
        }
        if (s.clienteId() != null && s.clienteId() <= 0) {
            throw invalida("El cliente no es válido.");
        }
        if (s.domicilio() != null) {
            throw invalida(DOMICILIO_NO_DISPONIBLE);
        }
        String motivo = texto(s.motivoEncargo());
        if (motivo != null && motivo.length() > LimitesDeVenta.MOTIVO_DEL_ENCARGO) {
            throw invalida("El motivo del encargo admite hasta " + LimitesDeVenta.MOTIVO_DEL_ENCARGO + " caracteres.");
        }
        validar(s.descuentoTotal());
        List<LineaSolicitada> lineas = fusionar(s.lineas(), hoy);
        List<PagoSolicitado> pagos = limpiar(s.pagos());
        return new SolicitudVenta(s.claveIdempotencia(), s.clienteId(), lineas, s.descuentoTotal(), pagos, motivo,
                s.autorizacion(), null);
    }

    /** Firma vacía de la ronda 1. */
    static SolicitudVenta forma(SolicitudVenta s) {
        throw new UnsupportedOperationException("pendiente");
    }

    /** Firma vacía de la ronda 1. */
    static SolicitudVenta fechas(SolicitudVenta s, LocalDate hoy) {
        throw new UnsupportedOperationException("pendiente");
    }

    // ------------------------------------------------------------ líneas

    private static List<LineaSolicitada> fusionar(List<LineaSolicitada> pedidas, LocalDate hoy) {
        if (pedidas == null || pedidas.isEmpty()) {
            throw invalida("La venta necesita al menos una línea.");
        }
        if (pedidas.size() > LimitesDeVenta.LINEAS) {
            throw invalida("La venta admite como máximo " + LimitesDeVenta.LINEAS + " líneas.");
        }
        Map<Integer, LineaSolicitada> porProducto = new TreeMap<>();
        for (LineaSolicitada l : pedidas) {
            if (l == null) {
                throw invalida("Una línea de la venta está vacía.");
            }
            if ((l.productoId() == null) == (l.productoNuevo() == null)) {
                throw invalida("Cada línea lleva un producto o un producto nuevo, y no los dos ni ninguno.");
            }
            if (l.productoNuevo() != null) {
                throw invalida(PRODUCTO_NUEVO_NO_DISPONIBLE);
            }
            if (l.productoId() <= 0) {
                throw invalida("El producto no es válido.");
            }
            if (l.cantidad() < 1 || l.cantidad() > LimitesDeVenta.CANTIDAD) {
                throw invalida("La cantidad de una línea va de 1 a " + LimitesDeVenta.CANTIDAD + " unidades.");
            }
            validar(l.descuento());
            if (l.entregaEstimada() != null && l.entregaEstimada().isBefore(hoy)) {
                throw invalida("La fecha estimada de entrega no puede ser anterior a hoy.");
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
        return List.copyOf(porProducto.values());
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
        for (LineaSolicitada l : n.lineas()) {
            canon.append("L:").append(l.productoId()).append(':').append(l.cantidad()).append(':')
                    .append(descuento(l.descuento())).append(':')
                    .append(l.entregaEstimada() == null ? "-" : l.entregaEstimada().toString()).append('|');
        }
        for (PagoSolicitado p : n.pagos()) {
            canon.append("P:").append(p.medioId()).append(':').append(plano(p.monto())).append(':')
                    .append(p.recibido() == null ? "-" : plano(p.recibido())).append(':')
                    .append(p.referencia() == null ? "-" : p.referencia()).append('|');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canon.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("La JVM no trae SHA-256.", e);
        }
    }

    private static String descuento(Descuento d) {
        return d == null ? "-" : d.tipo() + "=" + plano(d.valor());
    }

    private static String plano(BigDecimal valor) {
        return valor.signum() == 0 ? "0" : valor.stripTrailingZeros().toPlainString();
    }
}

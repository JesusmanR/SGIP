package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.Reembolso;
import co.com.bspstore.sgip.api.dto.SolicitudDevolucion;
import co.com.bspstore.sgip.api.dto.UnidadDevuelta;
import co.com.bspstore.sgip.logica.venta.LimitesDeVenta;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * La forma de una solicitud de devolución (RF-68), antes de pedir el PIN y de abrir la transacción: lo
 * que se puede decidir sin la base de datos. Rechaza con {@link VentaInvalidaException} (400, texto fijo,
 * sin eco del dato) y devuelve la solicitud limpia: el motivo sin espacios sobrantes, las unidades
 * repetidas fusionadas y ordenadas por producto, y los reembolsos en pesos enteros con la referencia en
 * blanco vuelta nula.
 *
 * Una lista de unidades vacía es una anulación y es válida; que la lista falte, no: anular toda una venta
 * se pide a propósito. La lista de reembolsos que falta es una lista vacía (la venta contra entrega sin
 * cobrar no tiene nada que reembolsar). Si los reembolsos suman lo que valen las unidades es cosa de la
 * transacción, que es quien conoce el valor.
 */
final class NormalizadorDeDevolucion {

    private static final int MAX_CIFRAS_ENTERAS = 12;

    private NormalizadorDeDevolucion() {}

    static SolicitudDevolucion forma(SolicitudDevolucion s) {
        if (s == null) {
            throw invalida("La devolución está vacía.");
        }
        if (s.ventaId() == null || s.ventaId() <= 0) {
            throw invalida("La venta no es válida.");
        }
        String motivo = texto(s.motivo());
        if (motivo == null) {
            throw invalida("La devolución necesita el motivo.");
        }
        if (motivo.length() > LimitesDeVenta.MOTIVO_DE_LA_DEVOLUCION) {
            throw invalida("El motivo admite hasta " + LimitesDeVenta.MOTIVO_DE_LA_DEVOLUCION + " caracteres.");
        }
        return new SolicitudDevolucion(s.ventaId(), unidades(s.unidades()), motivo, reembolsos(s.reembolsos()),
                s.autorizacion());
    }

    private static List<UnidadDevuelta> unidades(List<UnidadDevuelta> pedidas) {
        if (pedidas == null) {
            throw invalida("Indica las unidades devueltas; una lista vacía anula la venta entera.");
        }
        if (pedidas.size() > LimitesDeVenta.LINEAS) {
            throw invalida("La devolución admite como máximo " + LimitesDeVenta.LINEAS + " productos.");
        }
        Map<Integer, Integer> porProducto = new TreeMap<>();
        for (UnidadDevuelta u : pedidas) {
            if (u == null) {
                throw invalida("Una unidad devuelta está vacía.");
            }
            if (u.productoId() == null || u.productoId() <= 0) {
                throw invalida("El producto no es válido.");
            }
            if (u.cantidad() < 1) {
                throw invalida("La cantidad devuelta debe ser de al menos una unidad.");
            }
            long suma = (long) porProducto.getOrDefault(u.productoId(), 0) + u.cantidad();
            if (suma > LimitesDeVenta.CANTIDAD) {
                throw invalida("La cantidad devuelta admite hasta " + LimitesDeVenta.CANTIDAD + " unidades.");
            }
            porProducto.put(u.productoId(), (int) suma);
        }
        List<UnidadDevuelta> fusionadas = new ArrayList<>(porProducto.size());
        porProducto.forEach((producto, cantidad) -> fusionadas.add(new UnidadDevuelta(producto, cantidad)));
        return List.copyOf(fusionadas);
    }

    private static List<Reembolso> reembolsos(List<Reembolso> pedidos) {
        if (pedidos == null || pedidos.isEmpty()) {
            return List.of();
        }
        if (pedidos.size() > LimitesDeVenta.PAGOS) {
            throw invalida("La devolución admite como máximo " + LimitesDeVenta.PAGOS + " reembolsos.");
        }
        List<Reembolso> limpios = new ArrayList<>(pedidos.size());
        for (Reembolso r : pedidos) {
            if (r == null) {
                throw invalida("Un reembolso está vacío.");
            }
            if (r.medioId() == null || r.medioId() <= 0) {
                throw invalida("El reembolso necesita un medio de pago.");
            }
            String referencia = texto(r.referencia());
            if (referencia != null && referencia.length() > LimitesDeVenta.REFERENCIA) {
                throw invalida("La referencia admite hasta " + LimitesDeVenta.REFERENCIA + " caracteres.");
            }
            limpios.add(new Reembolso(r.medioId(), enPesos(r.monto()), referencia));
        }
        return List.copyOf(limpios);
    }

    /** Un monto en pesos enteros, mayor que cero y de hasta 12 cifras, sin decimales ni exponente. */
    private static BigDecimal enPesos(BigDecimal valor) {
        if (valor == null || valor.signum() <= 0) {
            throw montoInvalido();
        }
        // Se mira la forma sin ceros sobrantes antes de reescalar: 100.00 es válido, 100.50 no, y 1E+20 se
        // rechaza por grande sin llegar a expandirse.
        BigDecimal sinCeros = valor.stripTrailingZeros();
        if (sinCeros.scale() > 0 || (long) sinCeros.precision() - sinCeros.scale() > MAX_CIFRAS_ENTERAS) {
            throw montoInvalido();
        }
        return valor.setScale(0, RoundingMode.UNNECESSARY);
    }

    private static VentaInvalidaException montoInvalido() {
        return invalida("El monto del reembolso va en pesos enteros, mayor que cero y de hasta "
                + MAX_CIFRAS_ENTERAS + " dígitos.");
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
}

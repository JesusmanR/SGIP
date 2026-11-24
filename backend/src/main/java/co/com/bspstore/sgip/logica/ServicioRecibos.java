package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.Recibo;
import co.com.bspstore.sgip.api.dto.Recibo.LineaDeRecibo;
import co.com.bspstore.sgip.api.dto.Recibo.PagoDeRecibo;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.datos.RepositorioRecibos;
import co.com.bspstore.sgip.datos.RepositorioRecibos.CabeceraDeRecibo;
import co.com.bspstore.sgip.datos.RepositorioRecibos.LineaDeVenta;
import co.com.bspstore.sgip.datos.RepositorioRecibos.PagoDeVenta;
import co.com.bspstore.sgip.modelo.TipoMedio;
import co.com.bspstore.sgip.util.Hoy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * El recibo de una venta de la empresa de la sesión (RF-70), en JSON para reimprimirlo en el mostrador y, con
 * {@code ReciboPdf}, en PDF para enviarlo. Solo lee: tres consultas, las mismas con una línea que con doscientas.
 * El PDF no se genera aquí a propósito: se arma después de esta lectura, con la transacción ya cerrada.
 *
 * <ul>
 *   <li>El IVA se discrimina si la empresa es responsable de IVA (RF-34) o si la venta ya guardó IVA (una venta
 *       hecha cuando lo era, reimpresa después de que dejó de serlo, lo sigue mostrando porque el total lo lleva);
 *       si no, {@code iva} es nulo, en el total y en cada línea.</li>
 *   <li>La venta de otra empresa es, para quien pregunta, una venta que no existe (404): el consecutivo se repite
 *       entre empresas y la consulta siempre lleva la empresa de la sesión.</li>
 *   <li>Nunca lleva el costo ni la utilidad, tampoco para el administrador.</li>
 * </ul>
 *
 * La empresa del recibo (nombre, tipo de documento y número, dirección y teléfono) es la copia que la venta guardó
 * el día que se hizo, no los datos de hoy (V48); la consulta de la venta ya la trae, sin una consulta más.
 *
 * Las marcas salen en este orden: los encargos (uno por cada línea con unidades por encargar), el domicilio con sus
 * datos de entrega y, mientras el domicilio no se haya cobrado, «Pago contra entrega — por cobrar». Las fechas de
 * un documento van en dd/mm/aaaa (RNF-16).
 */
@Service
public class ServicioRecibos {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final RepositorioRecibos repositorio;

    public ServicioRecibos(RepositorioRecibos repositorio) {
        this.repositorio = repositorio;
    }

    @Transactional(readOnly = true)
    public Recibo recibo(Long ventaId) {
        Integer empresa = ContextoEmpresa.empresaActual();
        CabeceraDeRecibo v = repositorio.cabecera(empresa, ventaId)
                .orElseThrow(() -> new RecursoNoEncontradoException(ServicioConsultaDeVentas.VENTA_NO_EXISTE));
        List<LineaDeVenta> vendidas = repositorio.lineas(empresa, ventaId);
        List<PagoDeVenta> pagados = repositorio.pagos(empresa, ventaId);

        // el IVA ya cobrado se muestra aunque la empresa haya dejado de ser responsable: el total lo lleva
        boolean conIva = v.responsableIva() || v.iva().signum() > 0;
        List<LineaDeRecibo> lineas = new ArrayList<>(vendidas.size());
        for (LineaDeVenta l : vendidas) {
            lineas.add(new LineaDeRecibo(l.nombre(), l.cantidad(), l.precioUnitario(), l.descuento(),
                    conIva ? l.iva() : null, l.total()));
        }
        List<PagoDeRecibo> pagos = new ArrayList<>(pagados.size());
        for (PagoDeVenta p : pagados) {
            pagos.add(new PagoDeRecibo(p.medio(), p.tipo(), p.monto(), p.recibido(), p.referencia()));
        }
        return new Recibo(v.empresa(), v.nit(), v.consecutivo(), v.fecha().atZone(Hoy.ZONA).toOffsetDateTime(),
                v.cajero(), v.cliente(), v.tipoDocumento() + " " + v.identificacion(), List.copyOf(lineas),
                v.subtotal(), v.descuento(), conIva ? v.iva() : null, v.total(), List.copyOf(pagos),
                cambio(pagados), marcas(v, vendidas), v.tipoDocumentoEmpresa(), v.direccionEmpresa(),
                v.telefonoEmpresa());
    }

    /** Lo recibido en efectivo menos lo cobrado en efectivo; el saldo a favor y las tarjetas no dan cambio. */
    private static BigDecimal cambio(List<PagoDeVenta> pagos) {
        BigDecimal cambio = BigDecimal.ZERO;
        for (PagoDeVenta p : pagos) {
            if (p.tipo() == TipoMedio.EFECTIVO && p.recibido() != null) {
                cambio = cambio.add(p.recibido().subtract(p.monto()));
            }
        }
        return cambio.setScale(2, RoundingMode.HALF_UP);
    }

    private static List<String> marcas(CabeceraDeRecibo v, List<LineaDeVenta> lineas) {
        List<String> marcas = new ArrayList<>();
        for (LineaDeVenta l : lineas) {
            if (l.cantidadEncargo() > 0 && l.entregaEstimada() != null) {
                marcas.add("Encargo — entrega estimada: " + l.entregaEstimada().format(FECHA));
            }
        }
        if (v.domicilio()) {
            marcas.add("Domicilio");
            if (v.direccion() != null) {
                marcas.add("Entrega: " + v.direccion() + ", " + v.ciudad() + " · Tel. " + v.telefono()
                        + " · Recibe: " + v.recibe());
            }
        }
        if (v.contraEntrega() && "PENDIENTE".equals(v.estadoPago())) {
            marcas.add("Pago contra entrega — por cobrar");
        }
        return List.copyOf(marcas);
    }
}

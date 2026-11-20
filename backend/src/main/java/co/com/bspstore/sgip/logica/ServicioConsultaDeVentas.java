package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.VentaConsultada;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.datos.RepositorioDevoluciones;
import co.com.bspstore.sgip.datos.RepositorioDevoluciones.CabeceraDeVenta;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consultar una venta por su consecutivo (RF-68): lo que el mostrador necesita para devolverla. La venta, con
 * su cliente y lo reembolsado sin saldo, sus líneas con lo ya devuelto de cada una y sus pagos con su monto: tres
 * consultas, las mismas con una línea que con diez.
 *
 * Solo lee y es de la empresa de la sesión: el consecutivo se repite entre empresas (cada una numera desde uno),
 * y la venta de otra empresa es, para quien pregunta, una venta que no existe (404). Nunca devuelve el costo ni la
 * utilidad, tampoco al administrador.
 */
@Service
public class ServicioConsultaDeVentas {

    static final String VENTA_NO_EXISTE = "La venta no existe.";

    private final RepositorioDevoluciones repositorio;

    public ServicioConsultaDeVentas(RepositorioDevoluciones repositorio) {
        this.repositorio = repositorio;
    }

    @Transactional(readOnly = true)
    public VentaConsultada porConsecutivo(String consecutivo) {
        String buscado = consecutivo == null ? "" : consecutivo.trim();
        if (buscado.isEmpty()) {
            throw new RecursoNoEncontradoException(VENTA_NO_EXISTE);
        }
        Integer empresa = ContextoEmpresa.empresaActual();
        CabeceraDeVenta v = repositorio.cabeceraPorConsecutivo(empresa, buscado)
                .orElseThrow(() -> new RecursoNoEncontradoException(VENTA_NO_EXISTE));
        return new VentaConsultada(v.ventaId(), v.consecutivo(), v.estado(), v.estadoPago(), v.total(),
                new VentaConsultada.Cliente(v.clienteId(), v.cliente(), v.consumidorFinal()),
                repositorio.lineasConsultadas(empresa, v.ventaId()), repositorio.pagosConsultados(empresa, v.ventaId()),
                v.reembolsadoSinSaldo());
    }
}

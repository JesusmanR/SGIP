package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.OrdenDeCompra;
import co.com.bspstore.sgip.api.dto.SolicitudOrdenCompra;
import co.com.bspstore.sgip.api.dto.SolicitudRecepcion;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioCompras;
import co.com.bspstore.sgip.datos.RepositorioCompras.LineaBloqueada;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Compras: órdenes y recepciones (RF-22, RF-23).
 *
 * Las dos operaciones tienen una propiedad cada una que conviene no
 * perder de vista.
 *
 * <p><b>Registrar una orden no toca el inventario.</b> Es el criterio
 * del RF-22 —«se verifica que el inventario disponible no cambia tras
 * registrarla»— y aquí se cumple por omisión: esta clase no tiene cómo
 * cambiar una existencia. No llama al servicio de inventario en el alta,
 * y si alguien lo intentara, se vería.
 *
 * <p><b>Recibir sí lo toca, y solo a través del punto único.</b> Cada
 * línea recibida entra por
 * {@link ServicioInventario#ingresarPorRecepcion}, que es el mismo
 * camino que usa el ingreso manual. Compras no escribe movimientos: los
 * pide. Así el RF-49 sigue valiendo sin que este módulo tenga que
 * acordarse de nada.
 */
@Service
public class ServicioCompras {

    private static final String ABIERTA = "ABIERTA";
    private static final String PARCIAL = "PARCIAL";
    private static final String RECIBIDA = "RECIBIDA";
    private static final String ANULADA = "ANULADA";

    private final RepositorioCompras compras;
    private final ServicioInventario inventario;

    public ServicioCompras(RepositorioCompras compras, ServicioInventario inventario) {
        this.compras = compras;
        this.inventario = inventario;
    }

    @Transactional(readOnly = true)
    public List<OrdenDeCompra> listar(String estado, int limite) {
        Integer empresa = ContextoEmpresa.empresaActual();
        return compras.listarCabeceras(empresa, estado, limite).stream()
                .map(c -> armar(empresa, c))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<OrdenDeCompra> buscar(Long ordenId) {
        Integer empresa = ContextoEmpresa.empresaActual();
        return compras.buscarCabecera(empresa, ordenId).map(c -> armar(empresa, c));
    }

    /**
     * Registra una orden en estado ABIERTA (RF-22).
     *
     * El consecutivo repetido se comprueba antes de escribir para poder
     * decirlo; la clave única `(empresa_id, consecutivo)` sigue debajo
     * por si dos altas simultáneas pasan la comprobación a la vez.
     *
     * Las líneas repetidas se rechazan aquí y no se dejan a
     * `uq_oci_orden_producto`: dos líneas del mismo producto en la misma
     * orden casi siempre son un dedazo del formulario, y el mensaje
     * tiene que decir qué producto para que quien lo escribió lo
     * encuentre.
     *
     * Las referencias a productos de otra empresa las rechaza
     * `fk_oci_producto_emp` desde la V24, y la transacción se deshace
     * entera. No se comprueba además aquí a propósito: sería una consulta
     * por línea para adelantarse a un caso que solo ocurre si alguien
     * manipula la petición, y la restricción no se puede saltar.
     */
    @Transactional
    public OrdenDeCompra registrar(SolicitudOrdenCompra solicitud) {
        Integer empresa = ContextoEmpresa.empresaActual();
        String consecutivo = solicitud.consecutivo().trim();

        if (compras.existeConsecutivo(empresa, consecutivo)) {
            throw new RegistroRepetidoException(
                    "Ya hay una orden de compra con el consecutivo " + consecutivo + ".");
        }
        Set<Integer> vistos = new HashSet<>();
        for (var linea : solicitud.items()) {
            if (!vistos.add(linea.productoId())) {
                // 409 y no 400, como cualquier choque de clave única: es
                // lo que `uq_oci_orden_producto` responderá si algo se
                // cuela por otra vía. RecepcionInvalidaException habría
                // sido un abuso: habla de entregas contra una orden, y
                // aquí la orden todavía no existe.
                throw new RegistroRepetidoException(
                        "El producto " + linea.productoId() + " aparece en dos líneas de la "
                                + "orden. Suma las cantidades en una sola.");
            }
        }

        Long ordenId = compras.insertarOrden(empresa, solicitud.proveedorId(), responsable(),
                consecutivo, texto(solicitud.observacion()));
        for (var linea : solicitud.items()) {
            compras.insertarItem(empresa, ordenId, linea.productoId(),
                    linea.cantidad(), linea.costoUnitario());
        }
        return buscar(ordenId).orElseThrow(() -> new IllegalStateException(
                "La orden " + ordenId + " no se encontró justo después de crearla."));
    }

    /**
     * Registra una entrega contra una orden (RF-23).
     *
     * El orden de los pasos es el que hace cumplir el requisito:
     *
     * <ol>
     *   <li>Bloquear la orden y sus líneas. Dos entregas simultáneas
     *       leerían el mismo acumulado y las dos cabrían por separado;
     *       juntas pasarían de lo pedido.</li>
     *   <li>Comprobarlo todo antes de escribir nada: el estado de la
     *       orden, que cada producto esté pedido, y que lo que llega
     *       quepa en lo que falta. Un rechazo a mitad dejaría media
     *       entrega registrada si la transacción no cubriera todo, y
     *       cubriéndolo todo —que lo hace— deja al usuario un error
     *       sobre una línea cuando el problema era otra.</li>
     *   <li>Escribir la recepción, sumar lo recibido en cada línea y
     *       pedir el INGRESO al servicio de inventario.</li>
     *   <li>Recalcular el estado de la orden con lo que queda
     *       pendiente.</li>
     * </ol>
     *
     * Vacío si la orden no es de esta empresa, para que el controlador
     * responda 404.
     */
    @Transactional
    public Optional<OrdenDeCompra> recibir(Long ordenId, SolicitudRecepcion solicitud) {
        Integer empresa = ContextoEmpresa.empresaActual();

        Optional<String> estado = compras.bloquearOrden(empresa, ordenId);
        if (estado.isEmpty()) {
            return Optional.empty();
        }
        exigirQueAdmitaEntregas(estado.get(), ordenId);

        List<LineaBloqueada> lineas = compras.bloquearLineas(empresa, ordenId);
        Map<Integer, LineaBloqueada> porProducto = lineas.stream()
                .collect(Collectors.toMap(LineaBloqueada::productoId, Function.identity()));

        // Todo se comprueba antes de escribir una sola fila.
        Set<Integer> vistos = new HashSet<>();
        for (var entrega : solicitud.items()) {
            LineaBloqueada linea = porProducto.get(entrega.productoId());
            if (linea == null) {
                throw new RecepcionInvalidaException(
                        "La orden no pide el producto " + entrega.productoId() + ".");
            }
            if (!vistos.add(entrega.productoId())) {
                throw new RecepcionInvalidaException(
                        "El producto " + entrega.productoId() + " aparece en dos líneas de la "
                                + "entrega. Suma las cantidades en una sola.");
            }
            if (entrega.cantidadRecibida() > linea.pendiente()) {
                throw new RecepcionInvalidaException(
                        "Del producto " + entrega.productoId() + " se pidieron "
                                + linea.cantidad() + ", ya se recibieron "
                                + linea.cantidadRecibida() + " y ahora llegan "
                                + entrega.cantidadRecibida() + ": solo caben "
                                + linea.pendiente() + ".");
            }
        }

        Long recepcionId = compras.insertarRecepcion(empresa, ordenId, responsable(),
                texto(solicitud.observacion()));
        String documento = texto(solicitud.documento());
        for (var entrega : solicitud.items()) {
            LineaBloqueada linea = porProducto.get(entrega.productoId());
            compras.sumarRecibido(empresa, linea.itemId(), entrega.cantidadRecibida());
            // El movimiento lo escribe el servicio de inventario, no este.
            inventario.ingresarPorRecepcion(entrega.productoId(), entrega.cantidadRecibida(),
                            documento, recepcionId)
                    .orElseThrow(() -> new IllegalStateException(
                            "El producto " + entrega.productoId() + " es de una línea de esta "
                                    + "orden y el inventario no lo encontró. "
                                    + "Revise fk_oci_producto_emp."));
        }

        compras.fijarEstado(empresa, ordenId, estadoTras(lineas, solicitud));
        return buscar(ordenId);
    }

    /**
     * El estado que le queda a la orden.
     *
     * Se calcula sobre las líneas que se leyeron bloqueadas más lo que
     * esta entrega suma, en lugar de volver a consultarlas: las filas
     * están bloqueadas, de modo que nadie las ha cambiado, y una consulta
     * más solo añadiría una ocasión de leer algo distinto.
     *
     * RECIBIDA cuando no queda nada pendiente en ninguna línea; PARCIAL
     * en cuanto algo se recibió y falta algo. No vuelve a ABIERTA: una
     * orden que recibió algo ya no está intacta, y el estado tiene que
     * poder distinguirlo.
     */
    private String estadoTras(List<LineaBloqueada> lineas, SolicitudRecepcion solicitud) {
        Map<Integer, Integer> llega = solicitud.items().stream()
                .collect(Collectors.toMap(SolicitudRecepcion.LineaRecepcion::productoId,
                        SolicitudRecepcion.LineaRecepcion::cantidadRecibida,
                        Integer::sum));
        boolean algoPendiente = false;
        for (LineaBloqueada linea : lineas) {
            int recibido = linea.cantidadRecibida() + llega.getOrDefault(linea.productoId(), 0);
            if (recibido < linea.cantidad()) {
                algoPendiente = true;
            }
        }
        return algoPendiente ? PARCIAL : RECIBIDA;
    }

    private void exigirQueAdmitaEntregas(String estado, Long ordenId) {
        if (RECIBIDA.equals(estado)) {
            throw new RecepcionInvalidaException(
                    "La orden " + ordenId + " ya está recibida por completo.");
        }
        if (ANULADA.equals(estado)) {
            throw new RecepcionInvalidaException(
                    "La orden " + ordenId + " está anulada: no admite entregas.");
        }
        if (!ABIERTA.equals(estado) && !PARCIAL.equals(estado)) {
            throw new RecepcionInvalidaException(
                    "La orden " + ordenId + " está en estado " + estado
                            + " y no admite entregas.");
        }
    }

    private OrdenDeCompra armar(Integer empresa, RepositorioCompras.Cabecera c) {
        return new OrdenDeCompra(c.ordenId(), c.consecutivo(), c.proveedorId(), c.proveedor(),
                c.usuarioId(), c.usuario(), c.estado(), c.fecha(), c.observacion(), c.total(),
                compras.lineasDe(empresa, c.ordenId()));
    }

    private Integer responsable() {
        return ContextoEmpresa.actual()
                .map(UsuarioAutenticado::usuarioId)
                .orElseThrow(() -> new IllegalStateException(
                        "Operación de compras sin usuario en el contexto. "
                                + "Revise la cadena de seguridad."));
    }

    private static String texto(String valor) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.trim();
        return limpio.isEmpty() ? null : limpio;
    }
}

package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.CotizacionDetalle;
import co.com.bspstore.sgip.api.dto.PaginaDeCotizaciones;
import co.com.bspstore.sgip.api.dto.SolicitudAnulacion;
import co.com.bspstore.sgip.api.dto.SolicitudCotizacion;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.logica.ServicioCotizaciones;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Las cotizaciones (RF-37). Las usan el administrador y el vendedor; el cliente y el super administrador reciben
 * 403.
 *
 * <ul>
 *   <li>{@code POST}: crea una cotización con los precios de hoy, que el servidor toma de la vista de precios y
 *       congela. 201 con la cotización completa. No hace falta turno abierto: no mueve dinero ni inventario.</li>
 *   <li>{@code GET}: la lista paginada de la empresa, la más reciente primero. {@code estado} (VIGENTE, VENCIDA,
 *       CONVERTIDA o ANULADA) y {@code q} (número, o nombre o identificación del cliente) filtran;
 *       {@code pagina} empieza en 0 y {@code tamano} vale 20 si no se pide, con 100 como máximo.</li>
 *   <li>{@code GET /{id}}: la cotización con sus líneas. 404 si no es de la empresa de la sesión. Una vencida trae
 *       además el precio de hoy de cada línea.</li>
 *   <li>{@code GET /numero/{numero}}: lo mismo, por número escrito como sea ({@code 12}, {@code cot-12},
 *       {@code COT-00000012}). 404 si no existe en la empresa.</li>
 *   <li>{@code POST /{id}/anulacion}: anula una vigente o vencida con un motivo. La puede anular el administrador o
 *       el vendedor que la creó (403 si no); una anulada o convertida es un 409.</li>
 * </ul>
 *
 * La empresa y el usuario salen de la sesión; el cuerpo nunca los trae.
 */
@RestController
@RequestMapping("/api/v1/cotizaciones")
@PreAuthorize("hasAnyRole('ADMINISTRADOR', 'VENDEDOR')")
public class CotizacionesControlador {

    private final ServicioCotizaciones servicio;

    public CotizacionesControlador(ServicioCotizaciones servicio) {
        this.servicio = servicio;
    }

    @PostMapping
    public ResponseEntity<CotizacionDetalle> crear(@Valid @RequestBody SolicitudCotizacion solicitud) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(servicio.crear(ContextoEmpresa.empresaActual(), usuarioActual(), solicitud));
    }

    @GetMapping
    public PaginaDeCotizaciones lista(@RequestParam(required = false) String estado,
                                      @RequestParam(required = false) String q,
                                      @RequestParam(defaultValue = "0") int pagina,
                                      @RequestParam(defaultValue = "20") int tamano) {
        return servicio.listar(ContextoEmpresa.empresaActual(), estado, q, pagina, tamano);
    }

    @GetMapping("/{id}")
    public CotizacionDetalle detalle(@PathVariable Long id) {
        return servicio.detalle(ContextoEmpresa.empresaActual(), id);
    }

    @GetMapping("/numero/{numero}")
    public CotizacionDetalle porNumero(@PathVariable String numero) {
        return servicio.porNumero(ContextoEmpresa.empresaActual(), numero);
    }

    @PostMapping("/{id}/anulacion")
    public CotizacionDetalle anular(@PathVariable Long id, @Valid @RequestBody SolicitudAnulacion solicitud) {
        return servicio.anular(ContextoEmpresa.empresaActual(), usuarioActual(), ContextoEmpresa.esAdministrador(), id,
                solicitud.motivo());
    }

    private static Integer usuarioActual() {
        return ContextoEmpresa.actual().map(UsuarioAutenticado::usuarioId).orElseThrow(() ->
                new IllegalStateException("No hay usuario autenticado en el contexto. Revise la cadena de seguridad."));
    }
}

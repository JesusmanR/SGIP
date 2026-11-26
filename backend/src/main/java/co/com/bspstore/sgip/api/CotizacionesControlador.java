package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.CotizacionDetalle;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Las cotizaciones (RF-37). Las usan el administrador y el vendedor; el cliente y el super administrador reciben
 * 403.
 *
 * <ul>
 *   <li>{@code POST}: crea una cotización con los precios de hoy, que el servidor toma de la vista de precios y
 *       congela. 201 con la cotización completa. No hace falta turno abierto: no mueve dinero ni inventario.</li>
 *   <li>{@code GET /{id}}: la cotización con sus líneas. 404 si no es de la empresa de la sesión. Una vencida trae
 *       además el precio de hoy de cada línea.</li>
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
        Integer usuario = ContextoEmpresa.actual().map(UsuarioAutenticado::usuarioId).orElseThrow(() ->
                new IllegalStateException("No hay usuario autenticado en el contexto. Revise la cadena de seguridad."));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(servicio.crear(ContextoEmpresa.empresaActual(), usuario, solicitud));
    }

    @GetMapping("/{id}")
    public CotizacionDetalle detalle(@PathVariable Long id) {
        return servicio.detalle(ContextoEmpresa.empresaActual(), id);
    }
}

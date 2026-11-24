package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.EmpresaResumen;
import co.com.bspstore.sgip.api.dto.SolicitudDatosEmpresa;
import co.com.bspstore.sgip.logica.ServicioEmpresa;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Los datos de contacto de la empresa, editados por su administrador.
 *
 * Solo el PUT. {@code GET /api/v1/empresa} y el logo siguen en {@link ImagenControlador}, donde nacieron.
 * El administrador manda únicamente los cinco datos de contacto: cualquier otro campo (el NIT, la razón
 * social, el identificador de una empresa) es un 400 que lo nombra, y la empresa es siempre la de la
 * sesión. El NIT o la CC y la razón social los cambia solo la plataforma
 * ({@code PUT /api/v1/plataforma/empresas/{id}/identidad}).
 */
@RestController
@RequestMapping("/api/v1/empresa")
public class EmpresaControlador {

    private final ServicioEmpresa servicio;

    public EmpresaControlador(ServicioEmpresa servicio) {
        this.servicio = servicio;
    }

    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @PutMapping
    public EmpresaResumen actualizar(@Valid @RequestBody SolicitudDatosEmpresa solicitud) {
        solicitud.exigirSoloCamposConocidos();
        return servicio.actualizarDatos(solicitud);
    }
}

package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.EstadoDePrueba;
import co.com.bspstore.sgip.logica.ServicioPrueba;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * El contador de la prueba gratuita (RF-73).
 *
 * Solo el administrador y el vendedor de la empresa: son los dos actores que el
 * requisito nombra. El cliente no administra la cuenta y el super administrador
 * no tiene empresa (RN-17), así que los dos reciben 403.
 *
 * Es un GET, de modo que {@code FiltroEmpresaActiva} lo deja pasar también en
 * modo consulta: justo entonces la pantalla necesita saber que la prueba terminó.
 */
@RestController
@RequestMapping("/api/v1/empresa/prueba")
@PreAuthorize("hasAnyRole('ADMINISTRADOR', 'VENDEDOR')")
public class PruebaControlador {

    private final ServicioPrueba servicio;

    public PruebaControlador(ServicioPrueba servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public EstadoDePrueba ver() {
        return EstadoDePrueba.de(servicio.situacion());
    }
}

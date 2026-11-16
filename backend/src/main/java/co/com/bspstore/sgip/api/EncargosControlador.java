package co.com.bspstore.sgip.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Firma mínima del par rojo/verde de la tarea 8: las rutas llegan con el verde.
 */
@RestController
@RequestMapping("/api/v1/caja/encargos")
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class EncargosControlador {
}

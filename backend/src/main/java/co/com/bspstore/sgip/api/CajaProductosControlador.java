package co.com.bspstore.sgip.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Esqueleto para el rojo: sin rutas todavía. */
@RestController
@RequestMapping("/api/v1/caja/productos")
@PreAuthorize("hasAnyRole('ADMINISTRADOR', 'VENDEDOR')")
public class CajaProductosControlador {
}

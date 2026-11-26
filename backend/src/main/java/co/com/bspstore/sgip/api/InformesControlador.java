package co.com.bspstore.sgip.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Los informes de la empresa (RF-57, RF-58, RF-59, RF-69).
 *
 * Solo el administrador de la empresa: el inventario valorizado, las ventas netas y lo que no rota son datos
 * de gestión que un vendedor no necesita para vender y un cliente no debe ver (RN-17). El
 * {@code @PreAuthorize} va en la clase para que una ruta nueva no pueda quedar abierta por olvido. Los
 * informes son de lectura: una empresa en modo consulta los ve y los descarga.
 *
 * Rutas: {@code GET /api/v1/informes/<informe>} y {@code GET /api/v1/informes/<informe>/excel}.
 */
@RestController
@RequestMapping("/api/v1/informes")
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class InformesControlador {

    /**
     * Provisional: la tarea 2 lo reemplaza por el informe de verdad. Existe para que la autorización de la
     * clase tenga una ruta real que probar y no un endpoint de prueba.
     */
    @GetMapping("/inventario-valorizado")
    public Map<String, Object> inventarioValorizado() {
        return Map.of();
    }
}

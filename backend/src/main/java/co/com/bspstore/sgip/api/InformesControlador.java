package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.InformeInventario;
import co.com.bspstore.sgip.logica.informes.ServicioInformeInventario;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

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

    private final ServicioInformeInventario inventario;

    public InformesControlador(ServicioInformeInventario inventario) {
        this.inventario = inventario;
    }

    /**
     * El inventario valorizado: los totales, la sección de lo vendido sin existencia y una página de la
     * tabla. {@code tamano} por omisión es 50 y el máximo 200; {@code categoriaId} y {@code q} (código o
     * nombre) son filtros opcionales.
     */
    @GetMapping("/inventario-valorizado")
    public InformeInventario inventarioValorizado(
            @RequestParam(required = false) Integer categoriaId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "50") int tamano) {
        return inventario.pagina(categoriaId, q, pagina, tamano);
    }

    /**
     * El mismo informe como .xlsx, completo y sin paginar. Se escribe directamente en la respuesta, en el
     * hilo de la petición, y las cabeceras de descarga salen con el primer byte (ver {@link Descarga}): si la
     * lectura de la base falla antes, el manejador de errores contesta su problema normal.
     */
    @GetMapping("/inventario-valorizado/excel")
    public void inventarioValorizadoExcel(
            @RequestParam(required = false) Integer categoriaId,
            @RequestParam(required = false) String q,
            HttpServletResponse respuesta) throws IOException {
        Descarga salida = new Descarga(respuesta, inventario.nombreDeArchivo());
        inventario.escribirExcel(categoriaId, q, salida);
        salida.aplicarCabeceras();
    }
}

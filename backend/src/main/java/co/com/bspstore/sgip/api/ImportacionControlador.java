package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.ResumenCarga;
import co.com.bspstore.sgip.logica.ServicioImportacion;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Importación de listas de precios (RF-24, RF-26, RF-27).
 *
 * Son dos pasos y dos peticiones, no una. Subir el archivo lo lee, lo
 * valida y lo deja registrado; aplicar es otra llamada que la persona
 * hace después de mirar la vista previa. Juntarlos en un solo endpoint
 * ahorraría una petición y quitaría lo único que impide que una lista
 * con la columna equivocada reescriba el costo de todo el catálogo.
 *
 * Solo el administrador. Cambiar los costos cambia todos los precios de
 * venta de la empresa, de contado y a crédito, y con ellos el
 * tratamiento tributario de cada referencia que ande cerca del umbral.
 */
@RestController
@RequestMapping("/api/v1/importaciones")
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class ImportacionControlador {

    private final ServicioImportacion servicio;

    public ImportacionControlador(ServicioImportacion servicio) {
        this.servicio = servicio;
    }

    /**
     * Sube una lista, la valida y la registra sin aplicar nada.
     *
     * El tamaño se toma del propio archivo recibido y no de lo que
     * declare el cliente: un cliente puede mentir, y el tope del RF-24
     * es lo que protege la memoria de este proceso.
     */
    @PostMapping
    public ResumenCarga cargar(@RequestParam("proveedor") Integer proveedorId,
                               @RequestParam("archivo") MultipartFile archivo)
            throws IOException {
        return servicio.cargar(proveedorId, archivo.getOriginalFilename(),
                archivo.getSize(), archivo.getInputStream());
    }

    /** El resumen de una carga con su muestra para la vista previa. */
    @GetMapping("/{cargaId}")
    public ResponseEntity<ResumenCarga> ver(@PathVariable Long cargaId) {
        return servicio.buscar(cargaId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Descarta una carga validada sin aplicarla.
     *
     * Es la otra mitad de la decisión que la P-07 pone al pie de la
     * vista previa, y tiene que ser una acción: una carga que se queda
     * en VALIDADA para siempre es una que alguien aplica tres semanas
     * después, cuando sus costos ya no son los del proveedor.
     */
    @PostMapping("/{cargaId}/rechazar")
    public ResponseEntity<ResumenCarga> rechazar(@PathVariable Long cargaId) {
        return servicio.rechazar(cargaId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Aplica los costos aceptados de una carga ya validada (RF-27). */
    @PostMapping("/{cargaId}/aplicar")
    public ResponseEntity<ResumenCarga> aplicar(@PathVariable Long cargaId) {
        return servicio.aplicar(cargaId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

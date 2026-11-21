package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.Recibo;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.api.dto.VentaConsultada;
import co.com.bspstore.sgip.api.dto.VentaRegistrada;
import co.com.bspstore.sgip.logica.ResultadoDeVenta;
import co.com.bspstore.sgip.logica.ServicioConsultaDeVentas;
import co.com.bspstore.sgip.logica.ServicioRecibos;
import co.com.bspstore.sgip.logica.ServicioVentas;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 * Las ventas del mostrador. Las usan el administrador y el vendedor; el cliente y el super administrador reciben
 * 403.
 *
 * <ul>
 *   <li>{@code POST} (RF-41): registra una venta. 201 con la venta nueva, o 200 con la misma venta si la clave
 *       de idempotencia ya la había registrado.</li>
 *   <li>{@code GET ?consecutivo=V-00000001} (RF-68): la venta con sus líneas, lo devuelto de cada una y sus pagos,
 *       para devolverla. 404 si no es de la empresa de la sesión. Nunca lleva costo ni utilidad.</li>
 *   <li>{@code GET /{id}/recibo} y {@code GET /{id}/recibo.pdf} (RF-70): el recibo de una venta ya registrada,
 *       en JSON para reimprimirlo y en PDF de tirilla de 80 mm. Las mismas reglas que la consulta: 404 si la venta
 *       no es de la empresa de la sesión, y nunca lleva costo ni utilidad.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/caja/ventas")
@PreAuthorize("hasAnyRole('ADMINISTRADOR', 'VENDEDOR')")
public class VentasControlador {

    private final ServicioVentas servicio;
    private final ServicioConsultaDeVentas consulta;
    private final ServicioRecibos recibos;

    public VentasControlador(ServicioVentas servicio, ServicioConsultaDeVentas consulta, ServicioRecibos recibos) {
        this.servicio = servicio;
        this.consulta = consulta;
        this.recibos = recibos;
    }

    @PostMapping
    public ResponseEntity<VentaRegistrada> registrar(@Valid @RequestBody SolicitudVenta solicitud) {
        ResultadoDeVenta resultado = servicio.procesar(solicitud);
        return ResponseEntity.status(resultado.yaExistia() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(resultado.venta());
    }

    @GetMapping
    public VentaConsultada consultar(@RequestParam String consecutivo) {
        return consulta.porConsecutivo(consecutivo);
    }

    @GetMapping("/{id}/recibo")
    public Recibo recibo(@PathVariable Long id) {
        return recibos.recibo(id);
    }

    /**
     * El PDF no lleva {@code produces}: si lo llevara, un {@code Accept} que no lo admita convertiría en 406 los
     * errores, que son JSON ({@code ManejadorErrores}). El tipo se pone en la respuesta, y nada se guarda en caché:
     * es un documento de una venta de la empresa.
     */
    @GetMapping("/{id}/recibo.pdf")
    public ResponseEntity<byte[]> reciboPdf(@PathVariable Long id) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename("recibo-" + id + ".pdf").build().toString())
                .cacheControl(CacheControl.noStore())
                .body(recibos.pdf(id));
    }
}

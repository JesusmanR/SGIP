package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.EmpresaResumen;
import co.com.bspstore.sgip.api.dto.ImagenResumen;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.datos.ProductoImagenRepositorio;
import co.com.bspstore.sgip.logica.AlmacenArchivos;
import co.com.bspstore.sgip.logica.ReferenciaAjenaException;
import co.com.bspstore.sgip.logica.ServicioImagenes;
import co.com.bspstore.sgip.modelo.Empresa;
import co.com.bspstore.sgip.modelo.ProductoImagen;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ImagenControlador {

    private final ServicioImagenes servicio;
    private final ProductoImagenRepositorio imagenes;
    private final AlmacenArchivos almacen;

    public ImagenControlador(ServicioImagenes servicio,
                             ProductoImagenRepositorio imagenes,
                             AlmacenArchivos almacen) {
        this.servicio = servicio;
        this.imagenes = imagenes;
        this.almacen = almacen;
    }

    // ── Imágenes de producto ─────────────────────────────────────────
    @GetMapping("/productos/{productoId}/imagenes")
    public List<ImagenResumen> listar(@PathVariable Integer productoId) {
        return servicio.listar(productoId).stream().map(ImagenResumen::de).toList();
    }

    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @PostMapping("/productos/{productoId}/imagenes")
    public ResponseEntity<ImagenResumen> subir(@PathVariable Integer productoId,
                                               @RequestParam("archivo") MultipartFile archivo) {
        ImagenResumen creada = ImagenResumen.de(servicio.subirDeProducto(productoId, archivo));
        return ResponseEntity.created(java.net.URI.create(creada.url())).body(creada);
    }

    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @PostMapping("/imagenes/{imagenId}/principal")
    public ImagenResumen marcarPrincipal(@PathVariable Integer imagenId) {
        return ImagenResumen.de(servicio.marcarPrincipal(imagenId));
    }

    /**
     * Sirve la imagen principal de un producto. Existe porque el listado
     * del catálogo dice si hay imagen pero no cuál: la ruta no sale al
     * exterior, de modo que el cliente pide «la del producto 11» y el
     * servidor resuelve el archivo.
     */
    @GetMapping("/productos/{productoId}/imagen")
    public ResponseEntity<InputStreamResource> verDeProducto(@PathVariable Integer productoId)
            throws IOException {
        return imagenes.findByEmpresaIdAndProductoIdAndPrincipalTrue(
                        ContextoEmpresa.empresaActual(), productoId)
                .map(i -> {
                    try {
                        return archivo(i.getRuta());
                    } catch (IOException e) {
                        // La fila existe pero el archivo no: el disco y la
                        // base se desincronizaron. Es un 404 para el cliente
                        // y un problema de operación para quien administra.
                        return ResponseEntity.<InputStreamResource>notFound().build();
                    }
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Sirve el archivo. La ruta en disco sale de la fila, que lleva su
     * empresa; el cliente solo manda un identificador. No hay ninguna
     * cadena suya que llegue al sistema de archivos.
     */
    @GetMapping("/imagenes/{imagenId}")
    public ResponseEntity<InputStreamResource> ver(@PathVariable Integer imagenId) throws IOException {
        ProductoImagen i = imagenes.findByEmpresaIdAndId(ContextoEmpresa.empresaActual(), imagenId)
                .orElseThrow(() -> new ReferenciaAjenaException(
                        "La imagen indicada no existe en esta empresa."));
        return archivo(i.getRuta());
    }

    // ── Logo de la empresa ───────────────────────────────────────────
    @GetMapping("/empresa")
    public EmpresaResumen empresa() {
        return EmpresaResumen.de(servicio.empresaActual());
    }

    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @PostMapping("/empresa/logo")
    public EmpresaResumen subirLogo(@RequestParam("archivo") MultipartFile archivo) {
        return EmpresaResumen.de(servicio.subirLogo(archivo));
    }

    @GetMapping("/empresa/logo")
    public ResponseEntity<InputStreamResource> verLogo() throws IOException {
        Empresa e = servicio.empresaActual();
        if (e.getLogoRuta() == null) {
            return ResponseEntity.notFound().build();
        }
        return archivo(e.getLogoRuta());
    }

    private ResponseEntity<InputStreamResource> archivo(String ruta) throws IOException {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(almacen.tipoDe(ruta)))
                // Privado: la respuesta depende de quién la pide, de modo
                // que una caché compartida no debe guardarla.
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
                .body(new InputStreamResource(almacen.abrir(ruta)));
    }
}

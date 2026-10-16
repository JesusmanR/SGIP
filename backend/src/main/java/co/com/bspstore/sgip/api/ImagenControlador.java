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
import org.springframework.web.bind.annotation.DeleteMapping;
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
    @DeleteMapping("/imagenes/{imagenId}")
    public ResponseEntity<Void> eliminar(@PathVariable Integer imagenId) {
        servicio.eliminar(imagenId);
        return ResponseEntity.noContent().build();
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
    public ResponseEntity<InputStreamResource> verDeProducto(@PathVariable Integer productoId) {
        var principal = imagenes.findByEmpresaIdAndProductoIdAndPrincipalTrue(
                ContextoEmpresa.empresaActual(), productoId);
        if (principal.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            // Vigente y no inmutable: cuál es la principal cambia.
            return archivoVigente(principal.get().getRuta());
        } catch (IOException e) {
            // La fila existe pero el archivo no: el disco y la base se
            // desincronizaron. Para el cliente es un 404; para quien
            // administra, un problema que merece mirarse.
            return ResponseEntity.notFound().build();
        }
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
        return archivoInmutable(i.getRuta());
    }

    /**
     * La versión pequeña de una imagen, para el recuadro del catálogo y
     * la galería.
     *
     * Tiene dirección propia, la de SU imagen, y no la del producto. Esa
     * diferencia es la que arregla de raíz la miniatura equivocada: al
     * cambiar cuál es la principal, el catálogo recibe otro
     * identificador y pide otra dirección, de modo que la caché no
     * tiene forma de servir la anterior. Y como estos bytes no cambian
     * nunca, cachearlos una semana es correcto y además ahorra descargar
     * la imagen entera para pintarla en cuarenta píxeles.
     */
    @GetMapping("/imagenes/{imagenId}/miniatura")
    public ResponseEntity<byte[]> miniatura(@PathVariable Integer imagenId) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
                .body(servicio.miniaturaDe(imagenId));
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
        // El logo se reemplaza sin que cambie esta dirección.
        return archivoVigente(e.getLogoRuta());
    }

    /**
     * Un identificador de imagen apunta siempre al mismo archivo: esos
     * bytes no van a cambiar nunca, y si la imagen se borra, la dirección
     * deja de existir. Guardarla una semana es correcto y ahorra la
     * descarga de toda la galería cada vez que se abre.
     *
     * Privada: la respuesta depende de quién la pide, de modo que una
     * caché compartida no debe guardarla.
     */
    private ResponseEntity<InputStreamResource> archivoInmutable(String ruta) throws IOException {
        return archivo(ruta, CacheControl.maxAge(Duration.ofDays(7)).cachePrivate());
    }

    /**
     * Estas dos direcciones —la imagen principal de un producto y el
     * logo de la empresa— no nombran un archivo: nombran un papel. Al
     * subir otra foto o cambiar cuál es la principal, la dirección sigue
     * siendo la misma y el contenido es otro.
     *
     * Aquí estaba el defecto de la miniatura que no cambiaba. Se servían
     * con la caché de siete días de arriba, así que el navegador tenía
     * permiso explícito para no volver a preguntar: el front pedía la
     * imagen de nuevo tras subirla, la caché le devolvía los bytes
     * anteriores, y el resultado era una miniatura que no se actualizaba
     * —o que mostraba otra imagen— sin que nada fallara en el servidor.
     *
     * Una dirección cuyo contenido depende del estado no se puede cachear
     * por tiempo. La alternativa habría sido un ETag y revalidación, que
     * para una miniatura de 100 KB no compensa la complicación.
     */
    private ResponseEntity<InputStreamResource> archivoVigente(String ruta) throws IOException {
        return archivo(ruta, CacheControl.noStore());
    }

    private ResponseEntity<InputStreamResource> archivo(String ruta, CacheControl politica)
            throws IOException {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(almacen.tipoDe(ruta)))
                .cacheControl(politica)
                .body(new InputStreamResource(almacen.abrir(ruta)));
    }
}

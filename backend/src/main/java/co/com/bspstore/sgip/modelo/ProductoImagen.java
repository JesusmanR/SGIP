package co.com.bspstore.sgip.modelo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Una imagen de producto.
 *
 * No mapea principal_unica: es una columna generada por el motor, el
 * truco que hace cumplir «una sola principal por producto» sin
 * disparador. Mapearla como escribible permitiría contradecirla.
 */
@Entity
@Table(name = "producto_imagenes")
public class ProductoImagen {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "imagen_id")
    private Integer id;

    @Column(name = "empresa_id", nullable = false)
    private Integer empresaId;

    @Column(name = "producto_id", nullable = false)
    private Integer productoId;

    @Column(name = "ruta", nullable = false, length = 255)
    private String ruta;

    @Column(name = "orden", nullable = false)
    private short orden;

    @Column(name = "principal", nullable = false)
    private boolean principal;

    @Column(name = "bytes")
    private Integer bytes;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private LocalDateTime creadoEn;

    protected ProductoImagen() {
        // Requerido por JPA
    }

    public ProductoImagen(Integer empresaId, Integer productoId, String ruta,
                          short orden, boolean principal, Integer bytes) {
        this.empresaId = empresaId;
        this.productoId = productoId;
        this.ruta = ruta;
        this.orden = orden;
        this.principal = principal;
        this.bytes = bytes;
    }

    public void dejarDeSerPrincipal() {
        this.principal = false;
    }

    public void hacerPrincipal() {
        this.principal = true;
    }

    public Integer getId() { return id; }
    public Integer getEmpresaId() { return empresaId; }
    public Integer getProductoId() { return productoId; }
    public String getRuta() { return ruta; }
    public short getOrden() { return orden; }
    public boolean isPrincipal() { return principal; }
    public Integer getBytes() { return bytes; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
}

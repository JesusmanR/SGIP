package co.com.bspstore.sgip.modelo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "categorias")
public class Categoria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "categoria_id")
    private Integer id;

    /**
     * Desde la V10 cada categoría pertenece a una empresa, y el nombre es
     * único dentro de ella y no en toda la plataforma: dos comercios
     * pueden tener su propia categoría «Bebidas» sin estorbarse.
     */
    @Column(name = "empresa_id", nullable = false)
    private Integer empresaId;

    @Column(name = "nombre", nullable = false, length = 80)
    private String nombre;

    @Column(name = "descripcion", length = 200)
    private String descripcion;

    @Column(name = "activa", nullable = false)
    private boolean activa = true;

    protected Categoria() {
        // Requerido por JPA
    }

    public Categoria(Integer empresaId, String nombre, String descripcion) {
        this.empresaId = empresaId;
        this.nombre = nombre;
        this.descripcion = descripcion;
    }

    public void renombrar(String nombre, String descripcion) {
        this.nombre = nombre == null ? null : nombre.trim();
        this.descripcion = descripcion;
    }

    public void desactivar() {
        this.activa = false;
    }

    public Integer getId() { return id; }
    public Integer getEmpresaId() { return empresaId; }
    public String getNombre() { return nombre; }
    public String getDescripcion() { return descripcion; }
    public boolean isActiva() { return activa; }
}
